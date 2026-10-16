package app.flick.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.net.ssl.SSLContext
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two-way features against the real desktop receiver: pairing, the live link,
 * clipboard from computer to phone, and files from computer to phone.
 */
class LinkTest {
    companion object {
        private lateinit var host: String
        private var port = 0
        private lateinit var receiver: Receiver
        private val client = FlickClient()
        private lateinit var http: OkHttpClient

        @BeforeClass @JvmStatic
        fun setup() {
            val hp = System.getProperty("flick.e2e.host").orEmpty()
            assumeTrue("set -Dflick.e2e.host to run end-to-end tests", hp.isNotBlank())
            host = hp.substringBefore(':')
            port = hp.substringAfter(':').toInt()
            receiver = runBlocking {
                var found: Receiver? = null
                val scanner = UdpScanner({ Probe(id = "link-test", name = "JVM") }, { listOf(InetAddress.getByName(host)) }, { found = it }, 300)
                scanner.start(this)
                withTimeout(5000) { while (found == null) delay(50) }
                scanner.stop()
                found!!
            }
            val tm = PinningTrustManager(receiver.fingerprint)
            val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
            http = OkHttpClient.Builder().sslSocketFactory(ctx.socketFactory, tm).hostnameVerifier { _, _ -> true }.build()
        }

        /** Calls one of the receiver's test hooks (it plays the person at the computer). */
        fun hook(method: String, path: String, vararg query: Pair<String, String>): String {
            val url = HttpUrl.Builder().scheme("https").host(host).port(port).encodedPath(path)
            query.forEach { (k, v) -> url.addQueryParameter(k, v) }
            val req = Request.Builder().url(url.build())
            if (method == "POST") req.post(ByteArray(0).toRequestBody(null))
            return http.newCall(req.build()).execute().use { it.body!!.string() }
        }
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private fun pairNew(name: String): PhoneAuth = runBlocking {
        val id = UUID.randomUUID().toString()
        val res = client.pair(receiver, SenderInfo(id = id, name = name, model = "JVM"))
        val key = (res as AskResult.TextDelivered).trustKey
        assertNotNull(key, "pairing must return a trust key")
        PhoneAuth(id, key, name, "JVM")
    }

    /** Opens the live link and returns a channel of its events. */
    private fun CoroutineScope.openLink(a: PhoneAuth): Channel<LinkEvent> {
        val ch = Channel<LinkEvent>(Channel.UNLIMITED)
        launch { runCatching { client.link(receiver, a) { ch.send(it) } }.onFailure { ch.close(it) } }
        return ch
    }

    private suspend fun Channel<LinkEvent>.next(type: String, ms: Long = 5000): LinkEvent =
        withTimeout(ms) { while (true) { val e = receive(); if (e.type == type) return@withTimeout e }; error("unreachable") }

    @Test fun pairedPhoneGetsTheComputersClipboard() = runBlocking {
        val a = pairNew("Clip phone")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = scope.openLink(a)
        val hello = events.next("hello")
        assertEquals(receiver.id, hello.id)
        assertEquals(true, hello.clipboard)

        val text = "Copied on the PC ✓ — https://example.com/a?b=c\nsecond line 😀"
        hook("POST", "/v1/test/clip", "text" to text)
        assertEquals(text, events.next("clip").text)

        hook("POST", "/v1/test/clip", "text" to text) // same text again: not re-sent
        assertNull(withTimeoutOrNull(800) { events.next("clip") })
        hook("POST", "/v1/test/clip", "text" to "new text")
        assertEquals("new text", events.next("clip").text)
        scope.cancel()
    }

    @Test fun phoneClipboardReachesTheComputerAndOtherPhones() = runBlocking {
        val a = pairNew("Copying phone")
        val b = pairNew("Other phone")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val eventsA = scope.openLink(a)
        val eventsB = scope.openLink(b)
        eventsA.next("hello"); eventsB.next("hello")

        val text = "Copied on the phone ✓ https://piqit.in/look?id=42 😀"
        assertNull(client.sendClip(receiver, a, text))
        assertTrue(hook("GET", "/v1/test/lastclip").contains("Copied on the phone ✓"), "computer clipboard")
        assertEquals(text, eventsB.next("clip").text, "the other connected phone gets it too")
        // The computer's clipboard watcher now sees the same text: it must not bounce back.
        hook("POST", "/v1/test/clip", "text" to text)
        assertNull(withTimeoutOrNull(800) { eventsA.next("clip") }, "no echo to the phone that copied it")
        scope.cancel()
    }

    @Test fun phoneClipboardNeedsPairingAndSharingOn() = runBlocking {
        val stranger = PhoneAuth(UUID.randomUUID().toString(), "a".repeat(64), "Stranger")
        assertNotNull(client.sendClip(receiver, stranger, "nope"))
        assertTrue(!hook("GET", "/v1/test/lastclip").contains("nope"))

        val a = pairNew("Clip phone 2")
        hook("POST", "/v1/test/share", "on" to "0")
        val err = client.sendClip(receiver, a, "while off")
        hook("POST", "/v1/test/share", "on" to "1")
        assertEquals("clipboard sharing is off on this computer", err)
    }

    @Test fun unpairedPhoneCannotConnect() {
        runBlocking {
            assertFailsWith<LinkRejected> {
                client.link(receiver, PhoneAuth(UUID.randomUUID().toString(), "f".repeat(64), "Stranger")) {}
            }
        }
    }

    @Test fun computerSendsFilesToPhone() = runBlocking {
        val a = pairNew("Receiving phone")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = scope.openLink(a)
        events.next("hello")
        val dir = Files.createTempDirectory("flick-pc-files").toFile()
        val big = Random(7).nextBytes(3_000_000)
        val f1 = File(dir, "Holiday video.mp4").apply { writeBytes(big) }
        val f2 = File(dir, "notes.txt").apply { writeText("from the computer") }
        val id = Flick.json.parseToJsonElement(hook("POST", "/v1/test/offer", "phone" to a.phoneId, "path" to f1.path, "path" to f2.path))
            .let { it as kotlinx.serialization.json.JsonObject }["id"].toString().trim('"')

        val offer = events.next("offer").offer!!
        assertEquals(id, offer.id)
        assertEquals(listOf("Holiday video.mp4", "notes.txt"), offer.items.map { it.name })
        assertEquals("video/mp4", offer.items[0].mime)
        assertEquals(3_000_017L, offer.total)
        assertEquals("2 items", offer.description)

        client.offerStatus(receiver, a, offer.id, "accepted")
        val got = offer.items.indices.map { i ->
            ByteArrayOutputStream().also { out -> client.downloadOffered(receiver, a, offer.id, i, out) {} }.toByteArray()
        }
        assertEquals(sha(big), sha(got[0]))
        assertEquals("from the computer", String(got[1]))
        client.offerStatus(receiver, a, offer.id, "done")
        delay(200)
        val st = hook("GET", "/v1/test/offer", "id" to offer.id)
        assertTrue(st.contains("\"state\":\"done\"") && st.contains("\"sent\":3000017"), st)
        scope.cancel()
    }

    @Test fun phoneCanDeclineAndComputerCanCancel() = runBlocking {
        val a = pairNew("Busy phone")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val events = scope.openLink(a)
        events.next("hello")
        val f = File.createTempFile("flick", ".jpg").apply { writeBytes(Random(1).nextBytes(1000)) }

        hook("POST", "/v1/test/offer", "phone" to a.phoneId, "path" to f.path)
        val o1 = events.next("offer").offer!!
        client.offerStatus(receiver, a, o1.id, "declined")
        delay(200)
        assertTrue(hook("GET", "/v1/test/offer", "id" to o1.id).contains("\"state\":\"declined\""))

        hook("POST", "/v1/test/offer", "phone" to a.phoneId, "path" to f.path)
        val o2 = events.next("offer").offer!!
        hook("POST", "/v1/test/cancel", "id" to o2.id)
        assertEquals(o2.id, events.next("cancel").offerId)
        // a cancelled offer can't be downloaded any more
        assertFailsWith<java.io.IOException> { client.downloadOffered(receiver, a, o2.id, 0, ByteArrayOutputStream()) {} }
        scope.cancel()
    }

    @Test fun offersOnlyGoToConnectedPhones() {
        val a = pairNew("Offline phone")
        val f = File.createTempFile("flick", ".txt").apply { writeText("x") }
        val res = hook("POST", "/v1/test/offer", "phone" to a.phoneId, "path" to f.path)
        assertTrue(res.contains("isn't connected"), res)
    }

    @Test fun pairingCanBeDeclined() {
        runBlocking {
            val res = client.pair(receiver, SenderInfo(id = UUID.randomUUID().toString(), name = "Please decline me"))
            assertIs<AskResult.Declined>(res)
        }
    }
}
