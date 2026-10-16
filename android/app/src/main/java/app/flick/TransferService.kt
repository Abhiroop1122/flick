package app.flick

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import app.flick.core.FailReason
import app.flick.core.Receiver
import app.flick.core.Transfer
import app.flick.core.TransferState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One send that is running or recently finished, keyed by computer id. */
class Outgoing(val receiver: Receiver, val transfer: Transfer, val label: String, val job: Job, val items: List<Shared>)

/** App-wide view of sends, observed by the share sheet and the home screen. */
object TransferHub {
    private val _active = MutableStateFlow<Map<String, Outgoing>>(emptyMap())
    val active: StateFlow<Map<String, Outgoing>> = _active

    @OptIn(ExperimentalCoroutinesApi::class)
    fun stateFor(receiverId: String): Flow<TransferState?> =
        _active.flatMapLatest { it[receiverId]?.transfer?.state ?: flowOf(null) }

    /** Live state of every send, by computer id. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val states: Flow<Map<String, TransferState>> = _active.flatMapLatest { m ->
        if (m.isEmpty()) flowOf(emptyMap())
        else combine(m.map { (id, o) -> o.transfer.state.map { id to it } }) { it.toMap() }
    }

    fun isBusy(receiverId: String) = _active.value[receiverId]?.transfer?.state?.value?.finished == false

    internal fun put(o: Outgoing) = _active.update { it + (o.receiver.id to o) }
    internal fun clear(receiverId: String) = _active.update { it - receiverId }
    fun cancel(receiverId: String) { _active.value[receiverId]?.job?.cancel() }

    /** Forget finished results so a fresh share sheet starts clean. */
    fun clearFinished() = _active.update { m -> m.filterValues { it.transfer.state.value.finished.not() } }
}

/**
 * Runs sends in a foreground service so they survive the share sheet closing,
 * with a progress notification and a Cancel action.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = mutableSetOf<String>() // main thread only
    private var foregroundId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopIfIdle(); return START_NOT_STICKY }
        if (intent.action == ACTION_CANCEL) {
            intent.getStringExtra(EXTRA_ID)?.let(TransferHub::cancel)
            stopIfIdle()
            return START_NOT_STICKY
        }
        val app = FlickApp.get(this)
        val r = Receiver(
            id = intent.getStringExtra(EXTRA_ID) ?: return START_NOT_STICKY,
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            os = intent.getStringExtra(EXTRA_OS).orEmpty(),
            host = intent.getStringExtra(EXTRA_HOST) ?: return START_NOT_STICKY,
            port = intent.getIntExtra(EXTRA_PORT, 0),
            fingerprint = intent.getStringExtra(EXTRA_FP) ?: return START_NOT_STICKY,
        )
        val uris = IntentCompat.getParcelableArrayListExtra(intent, EXTRA_URIS, Uri::class.java).orEmpty()
        val names = intent.getStringArrayExtra(EXTRA_NAMES).orEmpty()
        val sizes = intent.getLongArrayExtra(EXTRA_SIZES) ?: LongArray(0)
        val mimes = intent.getStringArrayExtra(EXTRA_MIMES).orEmpty()
        val items = uris.mapIndexed { i, u ->
            Shared(u, names.getOrElse(i) { "file" }, sizes.getOrElse(i) { -1L }, mimes.getOrElse(i) { "application/octet-stream" })
                .also { it.resolver = contentResolver }
        }
        val text = intent.getStringExtra(EXTRA_TEXT)
        val label = Payloads.describeLabel(items, text)

        // Must be in the foreground within a few seconds of starting.
        val note = progress(r, label, null)
        if (foregroundId == 0) {
            foregroundId = notifId(r.id)
            ServiceCompat.startForeground(this, foregroundId, note,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } else notify(notifId(r.id), note)

        if (TransferHub.isBusy(r.id)) return START_NOT_STICKY
        val transfer = Transfer(
            client = app.client,
            receiver = r,
            sender = app.store.sender(r.id),
            payloads = items,
            text = text,
            thumbnail = { Payloads.thumbnailBase64(contentResolver, items) },
            onTrustKey = {
                app.store.setTrustKey(r.id, it)
                // Paired now: stay connected so this computer can send files and its clipboard back.
                android.os.Handler(mainLooper).post { LinkService.start(this) }
            },
        )
        running += r.id
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val watcher = launch {
                var last = 0L
                transfer.state.collect { s ->
                    val now = System.currentTimeMillis()
                    if (!s.finished && now - last > 700) { last = now; notify(notifId(r.id), progress(r, label, s)) }
                }
            }
            val result = try { transfer.run() } catch (e: CancellationException) { TransferState.Cancelled }
            watcher.cancel()
            withContext(NonCancellable + Dispatchers.Main) { finished(r, label, result, items) }
        }
        TransferHub.put(Outgoing(r, transfer, label, job, items))
        job.start()
        return START_NOT_STICKY
    }

    private fun finished(r: Receiver, label: String, s: TransferState, items: List<Shared>) {
        val app = FlickApp.get(this)
        // Files picked in Flick's own file picker were kept readable for the send; let them go.
        for (it in items) {
            it.closeUnused()
            runCatching { contentResolver.releasePersistableUriPermission(it.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (s is TransferState.Done) {
            app.store.remember(r, used = true)
            Shortcuts.publish(this, r)
        }
        val nm = NotificationManagerCompat.from(this)
        nm.cancel(notifId(r.id))
        val title: String
        val body: String
        when (s) {
            is TransferState.Done -> { title = "Sent to ${r.name}"; body = label.replaceFirstChar { it.uppercase() } }
            is TransferState.Declined -> { title = "${r.name} declined"; body = "${label.replaceFirstChar { it.uppercase() }} wasn't sent." }
            is TransferState.Cancelled -> { title = "Cancelled"; body = "Stopped sending to ${r.name}." }
            is TransferState.Failed -> { title = "Couldn't send to ${r.name}"; body = failText(s.reason) }
            else -> { title = r.name; body = "" }
        }
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF2F6BFF.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .apply { if (s is TransferState.Done || s is TransferState.Cancelled) setTimeoutAfter(6_000) }
            .build()
        running -= r.id
        if (foregroundId == notifId(r.id)) {
            // hand the foreground role to another running send, or leave the foreground
            val next = running.firstOrNull()
            if (next != null) {
                foregroundId = notifId(next)
                val o = TransferHub.active.value[next]
                if (o != null) ServiceCompat.startForeground(this, foregroundId, progress(o.receiver, o.label, o.transfer.state.value),
                    if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
            }
        }
        notify(resultId(r.id), n)
        stopIfIdle()
    }

    private fun stopIfIdle() {
        if (running.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundId = 0
            stopSelf()
        }
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+: data-sync services get a daily time budget. Stop cleanly if it runs out.
        TransferHub.active.value.keys.forEach(TransferHub::cancel)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun progress(r: Receiver, label: String, s: TransferState?): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF2F6BFF.toInt())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openApp())
            .addAction(0, "Cancel", cancelIntent(r.id))
        when (s) {
            is TransferState.Sending -> {
                val f = s.fraction
                b.setContentTitle("Sending to ${r.name}")
                b.setContentText(buildString {
                    append(label)
                    if (s.fileCount > 1) append(" · ${minOf(s.fileIndex + 1, s.fileCount)} of ${s.fileCount}")
                    if (s.bytesPerSecond > 0) append(" · ${Format.size(s.bytesPerSecond.toLong())}/s")
                })
                if (f != null) b.setProgress(1000, (f * 1000).toInt(), false) else b.setProgress(0, 0, true)
            }
            is TransferState.WaitingForAccept -> b.setContentTitle("Waiting for ${r.name}").setContentText("Accept on your computer to start.").setProgress(0, 0, true)
            else -> b.setContentTitle("Connecting to ${r.name}").setContentText(label).setProgress(0, 0, true)
        }
        return b.build()
    }

    private fun notify(id: Int, n: Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return // the person declined notifications; the share sheet still shows progress
        NotificationManagerCompat.from(this).notify(id, n)
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(id: String): PendingIntent = PendingIntent.getService(
        this, notifId(id), Intent(this, TransferService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL = "transfers"
        private const val ACTION_CANCEL = "app.flick.CANCEL"
        private const val EXTRA_ID = "id"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_OS = "os"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_PORT = "port"
        private const val EXTRA_FP = "fp"
        private const val EXTRA_URIS = "uris"
        private const val EXTRA_NAMES = "names"
        private const val EXTRA_SIZES = "sizes"
        private const val EXTRA_MIMES = "mimes"
        private const val EXTRA_TEXT = "text"

        private fun notifId(id: String) = 1000 + (id.hashCode() and 0x7ffff)
        private fun resultId(id: String) = 900_000 + (id.hashCode() and 0x7ffff)

        fun failText(r: FailReason) = when (r) {
            FailReason.UNREACHABLE -> "Computer not reachable. Is Flick open and on the same Wi-Fi?"
            FailReason.NO_ANSWER -> "Nobody accepted in time."
            FailReason.BUSY -> "The computer is busy. Try again in a moment."
            FailReason.NO_SPACE -> "Not enough free space on the computer."
            FailReason.SECURITY -> "Security check failed. Open Flick on the computer and try again."
            FailReason.INTERRUPTED -> "Stopped — cancelled on the computer or the Wi-Fi dropped."
            FailReason.FILE_ACCESS -> "Couldn't read a file. Share it again from the app it's in."
            FailReason.UNKNOWN -> "Something went wrong. Try again."
        }

        fun createChannel(ctx: Context) {
            val ch = NotificationChannel(CHANNEL, ctx.getString(R.string.channel_transfers), NotificationManager.IMPORTANCE_LOW)
            ch.description = ctx.getString(R.string.channel_transfers_desc)
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /** Hand files (and permission to read them) to the service and start sending. */
        fun send(ctx: Context, r: Receiver, items: List<Shared>, text: String?) {
            val i = Intent(ctx, TransferService::class.java)
                .putExtra(EXTRA_ID, r.id).putExtra(EXTRA_NAME, r.name).putExtra(EXTRA_OS, r.os)
                .putExtra(EXTRA_HOST, r.host).putExtra(EXTRA_PORT, r.port).putExtra(EXTRA_FP, r.fingerprint)
                .putParcelableArrayListExtra(EXTRA_URIS, ArrayList(items.map { it.uri }))
                .putExtra(EXTRA_NAMES, items.map { it.name }.toTypedArray())
                .putExtra(EXTRA_SIZES, items.map { it.size }.toLongArray())
                .putExtra(EXTRA_MIMES, items.map { it.mime }.toTypedArray())
            if (items.isEmpty()) i.putExtra(EXTRA_TEXT, text)
            if (items.isNotEmpty()) {
                // ClipData + grant flag passes our temporary read access on to the service.
                val clip = ClipData.newRawUri("files", items[0].uri)
                items.drop(1).forEach { clip.addItem(ClipData.Item(it.uri)) }
                i.clipData = clip
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startForegroundService(ctx, i)
        }
    }
}

object Format {
    fun size(bytes: Long): String {
        if (bytes < 0) return ""
        val u = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = bytes.toDouble(); var i = 0
        while (v >= 1000 && i < u.size - 1) { v /= 1000; i++ }
        return if (v >= 100 || i == 0) "${v.toInt()} ${u[i]}" else String.format(java.util.Locale.US, "%.1f %s", v, u[i])
    }
}
