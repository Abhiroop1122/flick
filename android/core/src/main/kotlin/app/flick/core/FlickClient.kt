package app.flick.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

sealed interface AskResult {
    data class Accepted(val session: String, val token: String, val trustKey: String?) : AskResult
    data class TextDelivered(val trustKey: String?) : AskResult
    data object Declined : AskResult
    data object TimedOut : AskResult
    data object Busy : AskResult
    data object NoSpace : AskResult
    data class Failed(val code: Int, val message: String) : AskResult
}

class FingerprintMismatch(expected: String, actual: String) :
    CertificateException("Certificate fingerprint changed (expected ${expected.take(12)}…, got ${actual.take(12)}…)")

/**
 * Only accepts the one certificate whose SHA-256 matches [expected].
 * With [expected] = null it accepts any certificate once and reports it (trust on first use).
 */
class PinningTrustManager(
    private val expected: String?,
    private val onSeen: ((String) -> Unit)? = null,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("client certificates not supported")

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
        val fp = sha256Hex(leaf.encoded)
        onSeen?.invoke(fp)
        if (expected != null && !fp.equals(expected, ignoreCase = true)) throw FingerprintMismatch(expected, fp)
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    companion object {
        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

class FlickClient(base: OkHttpClient? = null) {
    private val base: OkHttpClient = base ?: OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false) // an ask must never be sent twice
        .build()

    private val pinned = java.util.concurrent.ConcurrentHashMap<String, OkHttpClient>()

    private fun buildClient(expected: String?, onSeen: ((String) -> Unit)?): OkHttpClient {
        val tm = PinningTrustManager(expected, onSeen)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
        return base.newBuilder()
            .sslSocketFactory(ctx.socketFactory, tm)
            .hostnameVerifier { _, _ -> true } // identity is proven by the pinned certificate, not a host name
            .build()
    }

    private fun clientFor(fp: String): OkHttpClient =
        pinned.getOrPut(fp.lowercase()) { buildClient(fp.lowercase(), null) }

    private fun url(host: String, port: Int, path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val b = HttpUrl.Builder().scheme("https").host(host).port(port).encodedPath(path)
        query.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    /** GET /v1/info. With [expectedFp] null, pins whatever certificate the computer presents. */
    suspend fun info(host: String, port: Int, expectedFp: String? = null): Receiver {
        var seen = ""
        val client = if (expectedFp.isNullOrBlank()) buildClient(null) { seen = it } else clientFor(expectedFp)
        val res = client.newCall(Request.Builder().url(url(host, port, "/v1/info")).build()).await()
        res.use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            val here = Flick.json.decodeFromString(Here.serializer(), it.body!!.string())
            val fp = if (expectedFp.isNullOrBlank()) seen else expectedFp.lowercase()
            return Receiver(here.id, here.name, here.os, host, port, fp, via = "direct")
        }
    }

    suspend fun ask(r: Receiver, req: AskRequest): AskResult {
        val body = Flick.json.encodeToString(AskRequest.serializer(), req).toRequestBody(JSON)
        val client = clientFor(r.fingerprint).newBuilder().readTimeout(135, TimeUnit.SECONDS).build()
        val res = client.newCall(Request.Builder().url(url(r.host, r.port, "/v1/ask")).post(body).build()).await()
        res.use {
            val text = it.body?.string().orEmpty()
            val parsed = runCatching { Flick.json.decodeFromString(AskResponse.serializer(), text) }.getOrNull()
            return when {
                it.code == 200 && parsed?.accepted == true && parsed.session != null && parsed.token != null ->
                    AskResult.Accepted(parsed.session, parsed.token, parsed.trustKey)
                it.code == 200 && parsed?.accepted == true -> AskResult.TextDelivered(parsed.trustKey)
                it.code == 403 -> AskResult.Declined
                it.code == 408 -> AskResult.TimedOut
                it.code == 429 -> AskResult.Busy
                it.code == 507 -> AskResult.NoSpace
                else -> AskResult.Failed(it.code, parsed?.reason ?: text.take(200))
            }
        }
    }

    /**
     * PUT /v1/upload. Streams the payload (chunked, so a content provider that misreports
     * a file's size can't break the transfer). [onBytes] gets the running byte count.
     */
    suspend fun upload(
        r: Receiver, session: String, token: String, index: Int, payload: Payload,
        onBytes: (Long) -> Unit,
    ): UploadResponse {
        val body = PayloadBody(payload, onBytes)
        val req = Request.Builder()
            .url(url(r.host, r.port, "/v1/upload", mapOf("session" to session, "index" to index.toString())))
            .header("X-Flick-Token", token)
            .put(body)
            .build()
        val res = clientFor(r.fingerprint).newCall(req).await()
        res.use {
            val text = it.body?.string().orEmpty()
            val parsed = runCatching { Flick.json.decodeFromString(UploadResponse.serializer(), text) }.getOrNull()
            if (!it.isSuccessful) throw UploadRejected(it.code, parsed?.error ?: "HTTP ${it.code}")
            return parsed ?: UploadResponse(ok = true)
        }
    }

    // ------------------------------------------------------------ pairing & live link

    /** Asks the computer to pair. On "Connect" the result is TextDelivered(trustKey). */
    suspend fun pair(r: Receiver, sender: SenderInfo): AskResult = ask(r, AskRequest(sender = sender, pair = true))

    private fun Request.Builder.auth(a: PhoneAuth) = header("X-Flick-Phone", a.phoneId).header("X-Flick-Key", a.key)

    /**
     * Holds the live connection open and calls [onEvent] for each event until the computer
     * goes away (returns normally or throws). Throws [LinkRejected] if the computer no longer
     * trusts this phone. Cancelling the coroutine closes the connection.
     */
    suspend fun link(r: Receiver, a: PhoneAuth, onEvent: suspend (LinkEvent) -> Unit) = withContext(Dispatchers.IO) {
        val client = clientFor(r.fingerprint).newBuilder().readTimeout(70, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url(url(r.host, r.port, "/v1/link", mapOf("name" to a.name, "model" to a.model)))
            .auth(a).build()
        val call = client.newCall(req)
        val handle = coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { res ->
                if (res.code == 403) throw LinkRejected()
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                val src = res.body!!.source()
                while (true) {
                    ensureActive()
                    val line = src.readUtf8Line() ?: break
                    if (line.isBlank()) continue
                    val ev = runCatching { Flick.json.decodeFromString(LinkEvent.serializer(), line) }.getOrNull() ?: continue
                    onEvent(ev)
                }
            }
        } finally {
            handle.dispose()
        }
    }

    /** Downloads one offered file into [out]; [onBytes] gets the running count. Returns bytes written. */
    suspend fun downloadOffered(
        r: Receiver, a: PhoneAuth, offerId: String, index: Int, out: java.io.OutputStream, onBytes: (Long) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val client = clientFor(r.fingerprint).newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
        val req = Request.Builder()
            .url(url(r.host, r.port, "/v1/offer/file", mapOf("offer" to offerId, "index" to index.toString())))
            .auth(a).build()
        val call = client.newCall(req)
        val handle = coroutineContext.job.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { res ->
                if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
                val body = res.body!!
                val expected = body.contentLength()
                var total = 0L
                body.byteStream().use { input ->
                    val buf = ByteArray(256 * 1024)
                    while (true) {
                        ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                        onBytes(total)
                    }
                }
                if (expected >= 0 && total != expected) throw IOException("incomplete download")
                total
            }
        } finally {
            handle.dispose()
        }
    }

    /**
     * Puts [text] (copied on this phone) on the computer's clipboard.
     * Returns null on success, or a short reason the computer gave.
     */
    suspend fun sendClip(r: Receiver, a: PhoneAuth, text: String): String? {
        val body = Flick.json.encodeToString(ClipBody.serializer(), ClipBody(text)).toRequestBody(JSON)
        val req = Request.Builder().url(url(r.host, r.port, "/v1/clip")).auth(a).post(body).build()
        return try {
            clientFor(r.fingerprint).newCall(req).await().use { res ->
                if (res.isSuccessful) null
                else runCatching { Flick.json.decodeFromString(ErrorBody.serializer(), res.body!!.string()).error }
                    .getOrNull() ?: "HTTP ${res.code}"
            }
        } catch (e: Exception) { // network errors, and anything odd about the address
            if (e is kotlinx.coroutines.CancellationException) throw e
            e.message ?: "not reachable"
        }
    }

    /** Tells the computer what happened to an offer: accepted, declined, done, failed, cancelled. */
    suspend fun offerStatus(r: Receiver, a: PhoneAuth, offerId: String, state: String, reason: String = "") {
        val body = Flick.json.encodeToString(OfferStatus.serializer(), OfferStatus(state, reason)).toRequestBody(JSON)
        val req = Request.Builder()
            .url(url(r.host, r.port, "/v1/offer/status", mapOf("offer" to offerId)))
            .auth(a).post(body).build()
        runCatching { clientFor(r.fingerprint).newCall(req).await().close() }
    }

    suspend fun cancel(r: Receiver, session: String, token: String) {
        val req = Request.Builder()
            .url(url(r.host, r.port, "/v1/cancel", mapOf("session" to session)))
            .header("X-Flick-Token", token)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        runCatching { clientFor(r.fingerprint).newCall(req).await().close() }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

class UploadRejected(val code: Int, message: String) : IOException(message)

/** The computer doesn't trust this phone any more (it was forgotten there). */
class LinkRejected : IOException("This computer no longer trusts this phone")

/** The phone could not read the file it was asked to send (permission revoked, file deleted…). */
class PayloadReadException(cause: Throwable) : IOException("Could not read file: ${cause.message}", cause)

private class PayloadBody(private val p: Payload, private val onBytes: (Long) -> Unit) : RequestBody() {
    override fun contentType() = p.mime.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
    override fun contentLength(): Long = -1 // chunked: see FlickClient.upload
    override fun isOneShot() = true
    override fun writeTo(sink: BufferedSink) {
        // Anything other than an IOException thrown here would crash OkHttp's dispatcher thread.
        val stream = try {
            p.open()
        } catch (e: IOException) {
            throw PayloadReadException(e)
        } catch (e: RuntimeException) {
            throw PayloadReadException(e)
        }
        stream.use { input ->
            val buf = ByteArray(128 * 1024)
            var total = 0L
            while (true) {
                val n = try {
                    input.read(buf)
                } catch (e: RuntimeException) {
                    throw PayloadReadException(e)
                }
                if (n < 0) break
                sink.write(buf, 0, n)
                total += n
                onBytes(total)
            }
        }
    }
}

/** Suspends until the call completes; cancelling the coroutine cancels the HTTP call. */
suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (cont.isActive) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (cont.isActive) cont.resume(response) else response.close()
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
