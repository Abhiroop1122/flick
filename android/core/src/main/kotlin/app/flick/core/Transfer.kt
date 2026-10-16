package app.flick.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

sealed interface TransferState {
    val finished: Boolean get() = false

    data object Connecting : TransferState
    data object WaitingForAccept : TransferState
    data class Sending(
        val sent: Long, val total: Long, val fileIndex: Int, val fileCount: Int, val bytesPerSecond: Double,
    ) : TransferState {
        /** 0..1, or null when sizes are unknown. */
        val fraction: Float? get() = if (total > 0) (sent.toFloat() / total).coerceIn(0f, 1f) else null
    }
    data class Done(val count: Int, val bytes: Long, val millis: Long) : TransferState { override val finished get() = true }
    data object Declined : TransferState { override val finished get() = true }
    data class Failed(val reason: FailReason, val detail: String = "") : TransferState { override val finished get() = true }
    data object Cancelled : TransferState { override val finished get() = true }
}

enum class FailReason {
    UNREACHABLE,   // computer not answering (asleep, Flick closed, different Wi-Fi)
    NO_ANSWER,     // nobody clicked Accept in time
    BUSY,
    NO_SPACE,
    SECURITY,      // certificate didn't match
    INTERRUPTED,   // connection dropped or cancelled on the computer
    FILE_ACCESS,   // couldn't read a file on the phone
    UNKNOWN,
}

/**
 * One send: ask the computer, wait for Accept, then stream every payload.
 * Pure Kotlin so the exact same code runs in the app and in the JVM end-to-end tests.
 */
class Transfer(
    private val client: FlickClient,
    val receiver: Receiver,
    private val sender: SenderInfo,
    private val payloads: List<Payload>,
    private val text: String? = null,
    private val thumbnail: () -> String? = { null },
    private val onTrustKey: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<TransferState>(TransferState.Connecting)
    val state: StateFlow<TransferState> = _state.asStateFlow()

    val itemCount get() = payloads.size
    val totalBytes get() = payloads.sumOf { maxOf(0L, it.size) }

    suspend fun run(): TransferState {
        var session: String? = null
        var token: String? = null
        try {
            val req = AskRequest(
                sender = sender,
                items = payloads.map { ItemInfo(it.name, it.size, it.mime) },
                text = if (payloads.isEmpty()) text else null,
                thumb = if (payloads.isNotEmpty()) runCatching(thumbnail).getOrNull() else null,
            )
            _state.value = TransferState.WaitingForAccept
            when (val res = client.ask(receiver, req)) {
                is AskResult.Declined -> return finish(TransferState.Declined)
                is AskResult.TimedOut -> return finish(TransferState.Failed(FailReason.NO_ANSWER))
                is AskResult.Busy -> return finish(TransferState.Failed(FailReason.BUSY))
                is AskResult.NoSpace -> return finish(TransferState.Failed(FailReason.NO_SPACE))
                is AskResult.Failed -> return finish(TransferState.Failed(FailReason.UNKNOWN, "HTTP ${res.code} ${res.message}"))
                is AskResult.TextDelivered -> {
                    res.trustKey?.let(onTrustKey)
                    return finish(TransferState.Done(0, 0, 0))
                }
                is AskResult.Accepted -> {
                    res.trustKey?.let(onTrustKey)
                    session = res.session
                    token = res.token
                }
            }

            val started = clock()
            val total = totalBytes
            var before = 0L
            var rate = 0.0
            var lastT = started
            var lastB = 0L
            var lastEmit = 0L
            payloads.forEachIndexed { i, p ->
                _state.value = TransferState.Sending(before, total, i, payloads.size, rate)
                var thisFile = 0L
                client.upload(receiver, session!!, token!!, i, p) { n ->
                    thisFile = n
                    val now = clock()
                    val sent = before + n
                    if (now - lastT >= 500) {
                        val inst = (sent - lastB) * 1000.0 / (now - lastT)
                        rate = if (rate == 0.0) inst else rate * 0.6 + inst * 0.4
                        lastT = now; lastB = sent
                    }
                    if (now - lastEmit >= 80) {
                        lastEmit = now
                        _state.value = TransferState.Sending(sent, maxOf(total, sent), i, payloads.size, rate)
                    }
                }
                before += thisFile
            }
            return finish(TransferState.Done(payloads.size, before, clock() - started))
        } catch (e: CancellationException) {
            _state.value = TransferState.Cancelled
            val s = session
            val t = token
            if (s != null && t != null) withContext(NonCancellable) { client.cancel(receiver, s, t) }
            throw e
        } catch (e: Exception) {
            return finish(TransferState.Failed(classify(e), e.message ?: e.javaClass.simpleName))
        }
    }

    private fun finish(s: TransferState): TransferState {
        _state.value = s
        return s
    }

    companion object {
        fun classify(e: Throwable): FailReason {
            var c: Throwable? = e
            while (c != null) {
                when (c) {
                    is PayloadReadException -> return FailReason.FILE_ACCESS
                    is FingerprintMismatch -> return FailReason.SECURITY
                    is UploadRejected -> return FailReason.INTERRUPTED
                    is ConnectException, is NoRouteToHostException -> return FailReason.UNREACHABLE
                    is SocketTimeoutException -> return FailReason.UNREACHABLE
                    is SecurityException -> return FailReason.FILE_ACCESS
                }
                c = c.cause
            }
            return when (e) {
                is SSLException -> FailReason.SECURITY
                is IOException -> FailReason.INTERRUPTED
                else -> FailReason.UNKNOWN
            }
        }
    }
}
