package app.flick.core

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Runs the Android app's real networking code against the real desktop receiver
 * (desktop/test/run-server.js). Start with scripts/e2e.sh.
 */
class EndToEndTest {
    companion object {
        private lateinit var host: String
        private var port = 0
        private lateinit var saveDir: File
        private lateinit var receiver: Receiver
        private val client = FlickClient()

        @BeforeClass @JvmStatic
        fun setup() {
            val hp = System.getProperty("flick.e2e.host").orEmpty()
            assumeTrue("set -Dflick.e2e.host to run end-to-end tests", hp.isNotBlank())
            host = hp.substringBefore(':')
            port = hp.substringAfter(':').toInt()
            saveDir = File(System.getProperty("flick.e2e.dir"))
            receiver = discover()
        }

        private fun discover(): Receiver = runBlocking {
            var found: Receiver? = null
            val scanner = UdpScanner(
                probe = { Probe(id = "test-phone", name = "JVM test") },
                targets = { listOf(InetAddress.getByName(host)) },
                onFound = { found = it },
                intervalMs = 300,
            )
            scanner.start(this)
            withTimeout(5000) { while (found == null) delay(50) }
            scanner.stop()
            found!!
        }
    }

    private class Bytes(override val name: String, val data: ByteArray, override val size: Long = data.size.toLong(), override val mime: String = "application/octet-stream") : Payload {
        override fun open(): InputStream = ByteArrayInputStream(data)
    }

    private class Slow(override val name: String, val total: Int) : Payload {
        override val size = total.toLong()
        override val mime = "video/mp4"
        override fun open(): InputStream = object : InputStream() {
            var left = total
            override fun read(): Int = throw UnsupportedOperationException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                Thread.sleep(20)
                val n = minOf(len, left, 64 * 1024)
                left -= n
                return n
            }
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun sender(name: String, trustKey: String? = null) = SenderInfo(id = "phone-" + name.hashCode(), name = name, model = "JVM", trustKey = trustKey)
    private fun send(name: String, payloads: List<Payload>, text: String? = null, r: Receiver = receiver, trustKey: String? = null, onTrust: (String) -> Unit = {}) =
        runBlocking { Transfer(client, r, sender(name, trustKey), payloads, text, onTrustKey = onTrust).run() }

    @Test fun discoveryReturnsPinnedFingerprint() {
        assertEquals("Test Receiver", receiver.name)
        assertEquals(64, receiver.fingerprint.length)
        val info = runBlocking { client.info(host, port, null) }
        assertEquals(receiver.fingerprint, info.fingerprint, "TOFU fingerprint must match the one announced over UDP")
        assertEquals(receiver.id, info.id)
    }

    @Test fun sendsFilesIntactAndSafely() {
        val big = Random(1).nextBytes(5_000_000)
        val small = "hello flick".toByteArray()
        val state = send("Pixel", listOf(
            Bytes("holiday.jpg", big, mime = "image/jpeg"),
            Bytes("notes.txt", small, mime = "text/plain"),
            Bytes("../../evil/escape.sh", small),
        ))
        assertIs<TransferState.Done>(state)
        assertEquals(3, state.count)
        assertEquals(sha(big), sha(File(saveDir, "holiday.jpg").readBytes()))
        assertEquals("hello flick", File(saveDir, "notes.txt").readText())
        assertTrue(File(saveDir, "escape.sh").exists(), "path components must be stripped")
        assertTrue(saveDir.listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun duplicateNamesDoNotOverwrite() {
        send("Pixel", listOf(Bytes("same.txt", "one".toByteArray())))
        send("Pixel", listOf(Bytes("same.txt", "two".toByteArray())))
        assertEquals("one", File(saveDir, "same.txt").readText())
        assertEquals("two", File(saveDir, "same (1).txt").readText())
    }

    @Test fun wrongOrUnknownSizesStillArriveWhole() {
        val data = Random(2).nextBytes(300_000)
        val s1 = send("Pixel", listOf(Bytes("liar.bin", data, size = 10)))
        val s2 = send("Pixel", listOf(Bytes("unknown.bin", data, size = -1)))
        assertIs<TransferState.Done>(s1); assertIs<TransferState.Done>(s2)
        assertEquals(sha(data), sha(File(saveDir, "liar.bin").readBytes()))
        assertEquals(sha(data), sha(File(saveDir, "unknown.bin").readBytes()))
    }

    @Test fun declineIsReported() {
        assertIs<TransferState.Declined>(send("Please decline", listOf(Bytes("x.txt", "x".toByteArray()))))
        assertTrue(!File(saveDir, "x.txt").exists(), "nothing may be written before Accept")
    }

    @Test fun linkIsDelivered() {
        assertIs<TransferState.Done>(send("Pixel", emptyList(), text = "https://example.com/flick"))
    }

    @Test fun nobodyAnsweringTimesOut() {
        val s = send("ignore me", listOf(Bytes("never.txt", "x".toByteArray())))
        assertEquals(TransferState.Failed(FailReason.NO_ANSWER, ""), (s as TransferState.Failed).copy(detail = ""))
    }

    @Test fun alwaysAcceptSkipsThePopupNextTime() {
        var key: String? = null
        assertIs<TransferState.Done>(send("trust me", listOf(Bytes("t1.txt", "1".toByteArray()))) { key = it })
        assertNotNull(key)
        // Same phone id ("trust me" hashes the same); the scripted receiver would never answer an
        // untrusted "ignore" ask, so success here proves it was auto-accepted with the key.
        val s = runBlocking {
            Transfer(client, receiver, SenderInfo(id = "phone-" + "trust me".hashCode(), name = "ignore (trusted)", trustKey = key),
                listOf(Bytes("t2.txt", "2".toByteArray()))).run()
        }
        assertIs<TransferState.Done>(s)
        // A forged key must not be enough.
        val forged = runBlocking {
            Transfer(client, receiver, SenderInfo(id = "phone-" + "trust me".hashCode(), name = "ignore (forged)", trustKey = "0".repeat(64)),
                listOf(Bytes("t3.txt", "3".toByteArray()))).run()
        }
        assertIs<TransferState.Failed>(forged)
    }

    @Test fun wrongCertificateIsRefused() {
        val s = send("Pixel", listOf(Bytes("mitm.txt", "x".toByteArray())), r = receiver.copy(fingerprint = "ab".repeat(32)))
        assertEquals(FailReason.SECURITY, (s as TransferState.Failed).reason)
    }

    @Test fun unreachableComputerIsReported() {
        val s = send("Pixel", listOf(Bytes("y.txt", "y".toByteArray())), r = receiver.copy(port = 47499))
        assertEquals(FailReason.UNREACHABLE, (s as TransferState.Failed).reason)
    }

    @Test fun cancellingMidTransferCleansUp() = runBlocking {
        val t = Transfer(client, receiver, sender("Pixel"), listOf(Slow("movie.mp4", 20_000_000)))
        val job = async { runCatching { t.run() } }
        withTimeout(10_000) { while (t.state.value !is TransferState.Sending) delay(20) }
        delay(400)
        job.cancel()
        job.join()
        assertIs<TransferState.Cancelled>(t.state.value)
        delay(800)
        assertTrue(!File(saveDir, "movie.mp4").exists())
        assertTrue(saveDir.listFiles()!!.none { it.name.endsWith(".part") }, "partial file must be deleted")
    }
}
