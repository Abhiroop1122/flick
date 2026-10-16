package app.flick

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import app.flick.core.Flick
import app.flick.core.FlickClient
import app.flick.core.Probe
import app.flick.core.Receiver
import app.flick.core.UdpScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Keeps a live list of Flick computers on the current network.
 * Runs only while some screen holds it (see [acquire]/[release]).
 */
class Discovery(private val context: Context, private val store: Store, private val client: FlickClient) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val found = ConcurrentHashMap<String, Receiver>()
    private val _receivers = MutableStateFlow<List<Receiver>>(emptyList())
    val receivers: StateFlow<List<Receiver>> = _receivers.asStateFlow()

    private val _onLan = MutableStateFlow(true)
    /** False when the phone has no Wi-Fi/Ethernet connection (a phone hotspot also counts as "maybe"). */
    val onLan: StateFlow<Boolean> = _onLan.asStateFlow()

    private val _scanningSince = MutableStateFlow(0L)
    val scanningSince: StateFlow<Long> = _scanningSince.asStateFlow()

    private var users = 0
    private var pruneJob: Job? = null
    private var stopJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val main = Handler(Looper.getMainLooper())

    private val udp = UdpScanner(
        probe = { Probe(id = store.phoneId, name = store.phoneName.value) },
        targets = ::probeTargets,
        onFound = { add(it) },
    )

    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private var nsdListener: NsdManager.DiscoveryListener? = null
    private val resolveLock = Mutex()

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val netCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = updateLan()
        override fun onLost(network: Network) {
            found.clear(); publish(); updateLan()
        }
        override fun onAvailable(network: Network) {
            found.clear(); publish(); updateLan(); udp.pokeNow()
        }
    }

    @Synchronized
    fun acquire() {
        stopJob?.cancel()
        if (users++ == 0 && pruneJob == null) start()
    }

    @Synchronized
    fun release() {
        if (--users <= 0) {
            users = 0
            // keep scanning briefly so quickly reopening the sheet is instant
            stopJob = scope.launch { delay(15_000); synchronized(this@Discovery) { if (users == 0) stop() } }
        }
    }

    private fun start() {
        _scanningSince.value = System.currentTimeMillis()
        runCatching {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            multicastLock = wifi?.createMulticastLock("flick")?.apply { setReferenceCounted(false); acquire() }
        }
        runCatching { connectivity?.registerDefaultNetworkCallback(netCallback, main) }
        updateLan()
        udp.start(scope)
        startNsd()
        pruneJob = scope.launch {
            while (isActive) {
                delay(1000)
                val now = System.currentTimeMillis()
                val stale = found.values.filter { it.via == "udp" && now - it.lastSeen > 7_000 }
                if (stale.isNotEmpty()) { stale.forEach { found.remove(it.id) }; publish() }
            }
        }
    }

    private fun stop() {
        pruneJob?.cancel(); pruneJob = null
        udp.stop()
        stopNsd()
        runCatching { connectivity?.unregisterNetworkCallback(netCallback) }
        runCatching { multicastLock?.release() }
        multicastLock = null
        found.clear()
        publish()
    }

    /** Broadcast addresses + every computer we've used recently (unicast works even where broadcast is filtered). */
    private fun probeTargets(): Collection<InetAddress> {
        val out = LinkedHashSet<InetAddress>(UdpScanner.broadcastTargets())
        val month = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        for (k in store.known.value) {
            if (k.manual || maxOf(k.lastUsed, k.lastSeen) > month) {
                runCatching { InetAddress.getByName(k.host) }.getOrNull()?.let { out.add(it) }
            }
        }
        return out
    }

    /** "192.168.1.20" or "192.168.1.20:47475" → verifies it's a Flick computer and remembers it. Returns an error or null. */
    suspend fun addByAddress(input: String): String? {
        val t = input.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
        val host = t.substringBefore(':')
        val port = t.substringAfter(':', "").toIntOrNull() ?: 47475
        if (host.isBlank()) return "Type the address shown in Flick on your computer."
        return try {
            val r = kotlinx.coroutines.withTimeout(6_000) { client.info(host, port, null) }
            store.remember(r, manual = true)
            add(r.copy(via = "udp"))
            null
        } catch (e: Exception) {
            "No Flick computer answered at $host. Check the address and that both are on the same Wi-Fi."
        }
    }

    private fun add(r: Receiver) {
        val prev = found[r.id]
        // A UDP reply tells us the address that actually routes to us; prefer it over mDNS.
        val keep = if (prev != null && prev.via == "udp" && r.via == "nsd") prev.copy(lastSeen = System.currentTimeMillis()) else r
        found[r.id] = keep.copy(lastSeen = System.currentTimeMillis())
        store.remember(keep)
        if (prev == null || prev.name != keep.name || prev.host != keep.host || prev.port != keep.port) publish()
    }

    private fun publish() {
        val used = store.known.value.associate { it.id to it.lastUsed }
        _receivers.value = found.values.sortedWith(
            compareByDescending<Receiver> { used[it.id] ?: 0 }.thenBy { it.name.lowercase() },
        )
    }

    private fun updateLan() {
        val caps = runCatching { connectivity?.let { it.getNetworkCapabilities(it.activeNetwork) } }.getOrNull()
        _onLan.value = caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    // ------------------------------------------------------------------ mDNS (secondary route)
    private fun startNsd() {
        val m = nsd ?: return
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                scope.launch { resolve(m, info) }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                val gone = found.values.filter { it.via == "nsd" && it.name == info.serviceName }
                if (gone.isNotEmpty()) { gone.forEach { found.remove(it.id) }; publish() }
            }
        }
        nsdListener = l
        runCatching { m.discoverServices(Flick.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) }
    }

    private fun stopNsd() {
        val l = nsdListener ?: return
        nsdListener = null
        runCatching { nsd?.stopServiceDiscovery(l) }
    }

    private suspend fun resolve(m: NsdManager, info: NsdServiceInfo) = resolveLock.withLock {
        // Older Android versions can only resolve one service at a time, so this is serialized.
        val resolved = withTimeoutOrNull(5_000) {
            suspendCancellableCoroutine<NsdServiceInfo?> { cont ->
                @Suppress("DEPRECATION")
                runCatching {
                    m.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(si: NsdServiceInfo, errorCode: Int) { if (cont.isActive) cont.resume(null) }
                        override fun onServiceResolved(si: NsdServiceInfo) { if (cont.isActive) cont.resume(si) }
                    })
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        } ?: return@withLock

        val attrs = resolved.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8).orEmpty() }
        val id = attrs["id"].orEmpty()
        val fp = attrs["fp"].orEmpty().lowercase()
        if (id.isBlank() || fp.length != 64 || found[id]?.via == "udp") return@withLock
        val host = hostOf(resolved) ?: return@withLock
        // mDNS may hand out an address of a virtual adapter on the computer; only show it if it answers.
        scope.launch {
            val ok = withTimeoutOrNull(2_500) { runCatching { client.info(host, resolved.port, fp) }.getOrNull() }
            if (ok != null) add(ok.copy(via = "nsd", name = ok.name))
        }
    }

    private fun hostOf(si: NsdServiceInfo): String? {
        val addrs: List<InetAddress> = if (Build.VERSION.SDK_INT >= 34) si.hostAddresses else {
            @Suppress("DEPRECATION")
            listOfNotNull(si.host)
        }
        return (addrs.firstOrNull { it is Inet4Address } ?: return null).hostAddress
    }
}
