package app.flick

import android.Manifest
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.flick.core.LinkEvent
import app.flick.core.LinkRejected
import app.flick.core.Offer
import app.flick.core.PhoneAuth
import app.flick.core.Probe
import app.flick.core.Receiver
import app.flick.core.UdpScanner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

enum class LinkState { CONNECTING, CONNECTED, OFFLINE }

/** Live connection state per paired computer, for the UI. */
object LinkHub {
    private val _states = MutableStateFlow<Map<String, LinkState>>(emptyMap())
    val states: StateFlow<Map<String, LinkState>> = _states.asStateFlow()
    internal fun set(id: String, s: LinkState) = _states.update { it + (id to s) }
    internal fun clear(id: String) = _states.update { it - id }
    internal fun clearAll() { _states.value = emptyMap() }
}

/**
 * Keeps this phone connected to the computers it's paired with, so a computer can
 * send it files and its clipboard at any time — like AirDrop and Universal Clipboard.
 *
 * Runs as a "connected device" foreground service with a silent, minimised notification.
 */
class LinkService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loops = HashMap<String, Job>() // main thread only
    private val wake = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val pending = ConcurrentHashMap<String, Pair<String, Offer>>() // offerId -> (computerId, offer)
    private val downloads = ConcurrentHashMap<String, Job>()
    private lateinit var app: FlickApp
    private var inForeground = false

    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { wake.tryEmit(Unit) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        app = FlickApp.get(this)
        if (!goForeground()) {
            stopSelf()
            return
        }
        runCatching { connectivity.registerDefaultNetworkCallback(netCallback) }
        scope.launch {
            combine(LinkHub.states, app.store.known, app.store.clipboardSync) { _, _, _ -> }.debounce(300).collect {
                if (inForeground) notifyStatus()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!inForeground) return START_NOT_STICKY
        when (intent?.action) {
            ACTION_ACCEPT -> intent.getStringExtra(EXTRA_OFFER)?.let(::acceptPending)
            ACTION_DECLINE -> intent.getStringExtra(EXTRA_OFFER)?.let(::declinePending)
            ACTION_CANCEL -> intent.getStringExtra(EXTRA_OFFER)?.let { downloads[it]?.cancel() }
            ACTION_TURN_OFF -> {
                app.store.setStayConnected(false)
                sync()
            }
            else -> sync()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { connectivity.unregisterNetworkCallback(netCallback) }
        scope.cancel()
        LinkHub.clearAll()
        super.onDestroy()
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, NOTIF_ID, statusNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0,
        )
        inForeground = true
        true
    } catch (e: Exception) { // Android may refuse to start it from the background
        false
    }

    /** Start or stop per-computer loops to match the paired list and settings. */
    private fun sync() {
        val store = app.store
        val wanted = if (store.stayConnected.value) store.paired().map { it.id }.toSet() else emptySet()
        for (id in loops.keys.toList() - wanted) {
            loops.remove(id)?.cancel()
            LinkHub.clear(id)
        }
        for (id in wanted - loops.keys) loops[id] = loop(id)
        if (wanted.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            inForeground = false
            stopSelf()
        } else {
            notifyStatus()
        }
    }

    // ---------------------------------------------------------------- the connection loop

    private fun loop(id: String): Job = scope.launch {
        val me = coroutineContext[Job]
        var backoff = 2_000L
        while (isActive) {
            val k = app.store.get(id) ?: break
            val auth = app.store.auth(id) ?: break
            var connected = false
            LinkHub.set(id, LinkState.CONNECTING)
            try {
                app.client.link(k.toReceiver(), auth) { ev ->
                    if (ev.type == "hello") {
                        connected = true
                        backoff = 2_000L
                        LinkHub.set(id, LinkState.CONNECTED)
                    }
                    handle(id, auth, ev)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkRejected) {
                app.store.clearTrust(id) // the computer forgot this phone: needs "Connect" again
                break
            } catch (_: Exception) {
                // computer asleep, Flick closed, Wi-Fi changed… retry below
            }
            LinkHub.set(id, LinkState.OFFLINE)
            if (!connected) rediscover(id)?.let {
                app.store.remember(it) // it moved to a new address: try again right away
                backoff = 1_000L
            }
            withTimeoutOrNull(backoff) { wake.first() }
            backoff = (backoff * 2).coerceAtMost(5 * 60_000L)
        }
        LinkHub.clear(id)
        withContext(Dispatchers.Main) { if (loops[id] == me) loops.remove(id) }
    }

    /** Looks for the computer on the current Wi-Fi in case its address changed. */
    private suspend fun rediscover(id: String): Receiver? = coroutineScope {
        val found = java.util.concurrent.atomic.AtomicReference<Receiver?>(null)
        val known = app.store.get(id)
        val scanner = UdpScanner(
            probe = { Probe(id = app.store.phoneId, name = app.store.phoneName.value) },
            targets = { UdpScanner.broadcastTargets() + listOfNotNull(known?.host?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }) },
            onFound = { if (it.id == id) found.set(it) },
            intervalMs = 700,
        )
        scanner.start(this)
        withTimeoutOrNull(3_000) { while (found.get() == null) delay(100) }
        scanner.stop()
        found.get()
    }

    private suspend fun handle(id: String, auth: PhoneAuth, ev: LinkEvent) {
        when (ev.type) {
            "clip" -> {
                val text = ev.text ?: return
                if (!app.store.clipboardSync.value) return
                val from = app.store.get(id)?.name ?: "your computer"
                ClipShare.noteFromComputer(text)
                withContext(Dispatchers.Main) {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Copied on $from", text))
                }
            }
            "offer" -> {
                val offer = ev.offer ?: return
                if (app.store.autoAcceptFiles.value) startDownload(id, auth, offer)
                else askAboutOffer(id, offer)
            }
            "cancel" -> {
                val offerId = ev.offerId ?: return
                downloads[offerId]?.cancel()
                if (pending.remove(offerId) != null) nm().cancel(offerNotifId(offerId))
            }
        }
    }

    // ---------------------------------------------------------------- files from the computer

    private fun askAboutOffer(id: String, offer: Offer) {
        pending[offer.id] = id to offer
        val from = offer.from.ifBlank { "Your computer" }
        val n = NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF2F6BFF.toInt())
            .setContentTitle("$from wants to send you ${offer.description.ifBlank { "files" }}")
            .setContentText(namesLine(offer))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setTimeoutAfter(115_000)
            .setAutoCancel(true)
            .setContentIntent(serviceIntent(ACTION_ACCEPT, offer.id))
            .addAction(0, "Decline", serviceIntent(ACTION_DECLINE, offer.id))
            .addAction(0, "Accept", serviceIntent(ACTION_ACCEPT, offer.id))
            .build()
        post(offerNotifId(offer.id), n)
    }

    private fun acceptPending(offerId: String) {
        val (id, offer) = pending.remove(offerId) ?: return
        val auth = app.store.auth(id) ?: return
        nm().cancel(offerNotifId(offerId))
        startDownload(id, auth, offer)
    }

    private fun declinePending(offerId: String) {
        val (id, offer) = pending.remove(offerId) ?: return
        nm().cancel(offerNotifId(offerId))
        val r = app.store.get(id)?.toReceiver() ?: return
        val auth = app.store.auth(id) ?: return
        scope.launch { app.client.offerStatus(r, auth, offer.id, "declined") }
    }

    private fun startDownload(id: String, auth: PhoneAuth, offer: Offer) {
        if (downloads.containsKey(offer.id)) return
        val r = app.store.get(id)?.toReceiver() ?: return
        val from = offer.from.ifBlank { app.store.get(id)?.name ?: "your computer" }
        val label = offer.description.ifBlank { "files" }
        val nid = offerNotifId(offer.id)
        val job = scope.launch {
            val saver = Saver(this@LinkService)
            val saved = mutableListOf<Saver.Saved>()
            var current: Saver.Target? = null
            try {
                app.client.offerStatus(r, auth, offer.id, "accepted")
                var before = 0L
                var lastPost = 0L
                post(nid, progressNotification(offer, from, label, 0, offer.total))
                offer.items.forEachIndexed { i, item ->
                    val t = saver.create(item.name, item.mime)
                    current = t
                    t.out.use { out ->
                        app.client.downloadOffered(r, auth, offer.id, i, out) { n ->
                            val now = System.currentTimeMillis()
                            if (now - lastPost > 700) {
                                lastPost = now
                                post(nid, progressNotification(offer, from, label, before + n, offer.total))
                            }
                        }
                    }
                    saved += saver.finish(t)
                    current = null
                    before += item.size.coerceAtLeast(0)
                }
                app.client.offerStatus(r, auth, offer.id, "done")
                post(nid, doneNotification(from, label, saved))
            } catch (e: CancellationException) {
                current?.let(saver::abort)
                withContext(NonCancellable) { app.client.offerStatus(r, auth, offer.id, "cancelled") }
                nm().cancel(nid)
                throw e
            } catch (e: Exception) {
                current?.let(saver::abort)
                app.client.offerStatus(r, auth, offer.id, "failed", e.message ?: "")
                post(nid, NotificationCompat.Builder(this@LinkService, CHANNEL_INCOMING)
                    .setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6BFF.toInt())
                    .setContentTitle("Couldn't receive $label")
                    .setContentText(if (saved.isNotEmpty()) "${saved.size} of ${offer.items.size} saved. The connection to $from was lost." else "The connection to $from was lost.")
                    .setAutoCancel(true).setSilent(true).build())
            }
        }
        downloads[offer.id] = job
        job.invokeOnCompletion { downloads.remove(offer.id) }
    }

    private fun namesLine(o: Offer): String {
        val names = o.items.take(2).joinToString(", ") { it.name } + if (o.items.size > 2) " +${o.items.size - 2}" else ""
        return if (o.total > 0) "${Format.size(o.total)} · $names" else names
    }

    private fun progressNotification(o: Offer, from: String, label: String, got: Long, total: Long) =
        NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6BFF.toInt())
            .setContentTitle("Receiving $label from $from")
            .setContentText(if (total > 0) "${Format.size(got)} of ${Format.size(total)}" else Format.size(got))
            .setProgress(1000, if (total > 0) (got * 1000 / total).toInt() else 0, total <= 0)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(0, "Cancel", serviceIntent(ACTION_CANCEL, o.id))
            .build()

    private fun doneNotification(from: String, label: String, saved: List<Saver.Saved>): android.app.Notification {
        val open: Intent = run {
            val single = saved.singleOrNull()
            val visual = saved.all { it.mime.startsWith("image/") || it.mime.startsWith("video/") }
            when {
                single?.uri != null -> Intent(Intent.ACTION_VIEW).setDataAndType(single.uri, single.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                visual && saved.firstOrNull()?.uri != null -> Intent(Intent.ACTION_VIEW)
                    .setDataAndType(saved.first().uri, saved.first().mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                else -> Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
            }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val where = when {
            saved.all { it.mime.startsWith("image/") } -> "your gallery (Pictures/Flick)"
            saved.all { it.mime.startsWith("video/") } -> "your gallery (Movies/Flick)"
            else -> "Downloads/Flick"
        }
        return NotificationCompat.Builder(this, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_notification).setColor(0xFF2F6BFF.toInt())
            .setContentTitle("Received $label")
            .setContentText("From $from · saved to $where")
            .setContentIntent(PendingIntent.getActivity(this, saved.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .setAutoCancel(true)
            .build()
    }

    // ---------------------------------------------------------------- notifications

    private fun statusNotification(): android.app.Notification {
        val states = LinkHub.states.value
        val names = app.store.paired().filter { states[it.id] == LinkState.CONNECTED }.map { it.name }
        val title = when (names.size) {
            0 -> "Waiting for your computer"
            1 -> "Connected to ${names[0]}"
            else -> "Connected to ${names.size} computers"
        }
        val text = when {
            names.isEmpty() -> "Reconnects automatically when it's on the same Wi-Fi."
            app.store.clipboardSync.value -> "Copy on one, paste on the other. Files arrive automatically."
            else -> "Ready to receive files from your computer."
        }
        return NotificationCompat.Builder(this, CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFF2F6BFF.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(PendingIntent.getActivity(this, 0,
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, "Turn off", serviceIntent(ACTION_TURN_OFF, null))
            .build()
    }

    private fun notifyStatus() = post(NOTIF_ID, statusNotification())

    private fun nm() = NotificationManagerCompat.from(this)

    private fun post(id: Int, n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        nm().notify(id, n)
    }

    private fun serviceIntent(action: String, offerId: String?): PendingIntent = PendingIntent.getService(
        this, (action + offerId).hashCode(),
        Intent(this, LinkService::class.java).setAction(action).putExtra(EXTRA_OFFER, offerId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val CHANNEL_CONNECTION = "connection"
        const val CHANNEL_INCOMING = "incoming"
        private const val NOTIF_ID = 77
        private const val ACTION_ACCEPT = "app.flick.ACCEPT_OFFER"
        private const val ACTION_DECLINE = "app.flick.DECLINE_OFFER"
        private const val ACTION_CANCEL = "app.flick.CANCEL_OFFER"
        private const val ACTION_TURN_OFF = "app.flick.TURN_OFF"
        private const val EXTRA_OFFER = "offer"

        private fun offerNotifId(id: String) = 50_000 + (id.hashCode() and 0xffff)

        fun createChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL_CONNECTION, "Connection to your computer", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while Flick stays connected to your computers"
                setShowBadge(false)
            })
            nm.createNotificationChannel(NotificationChannel(CHANNEL_INCOMING, "Files from your computer", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Files your computer sends to this phone"
            })
        }

        /** Starts (or re-syncs) the connection service, or stops it when nothing is paired. */
        fun start(ctx: Context) {
            val store = FlickApp.get(ctx).store
            val i = Intent(ctx, LinkService::class.java)
            if (!store.stayConnected.value || store.paired().isEmpty()) {
                ctx.stopService(i)
                return
            }
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (_: Exception) {
                // Not allowed from the background right now; it starts next time Flick opens.
            }
        }

        fun ignoringBatteryOptimizations(ctx: Context): Boolean =
            ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
    }
}

/** Reconnect after a restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            LinkService.start(context)
        }
    }
}
