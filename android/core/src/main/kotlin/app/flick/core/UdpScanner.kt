package app.flick.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface

/**
 * Finds Flick receivers by broadcasting a small UDP probe every [intervalMs].
 * Receivers answer by unicast, so no multicast lock is needed to hear them.
 */
class UdpScanner(
    private val probe: () -> Probe,
    private val targets: () -> Collection<InetAddress>,
    private val onFound: (Receiver) -> Unit,
    private val intervalMs: Long = 1500,
    private val port: Int = Flick.DISCOVERY_PORT,
) {
    @Volatile private var socket: DatagramSocket? = null
    private var job: Job? = null

    @Synchronized
    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        val sock = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            bind(InetSocketAddress(0))
        }
        socket = sock
        job = scope.launch(Dispatchers.IO) {
            launch(Dispatchers.IO) { receiveLoop(sock) }
            while (isActive && !sock.isClosed) {
                sendProbes(sock)
                delay(intervalMs)
            }
        }
    }

    @Synchronized
    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
    }

    /** Send one round of probes right now (e.g. when the network changes). */
    fun pokeNow() {
        socket?.let { s -> Thread { sendProbes(s) }.start() }
    }

    private fun sendProbes(sock: DatagramSocket) {
        val data = Flick.json.encodeToString(Probe.serializer(), probe()).toByteArray()
        for (addr in targets()) {
            try {
                sock.send(DatagramPacket(data, data.size, addr, port))
            } catch (_: Exception) {
                // unreachable network, no route for broadcast, etc. — try the next target
            }
        }
    }

    private fun receiveLoop(sock: DatagramSocket) {
        val buf = ByteArray(8192)
        while (!sock.isClosed) {
            val pkt = DatagramPacket(buf, buf.size)
            try {
                sock.receive(pkt)
            } catch (_: Exception) {
                if (sock.isClosed) return else continue
            }
            val here = try {
                Flick.json.decodeFromString(Here.serializer(), String(pkt.data, 0, pkt.length, Charsets.UTF_8))
            } catch (_: Exception) {
                continue
            }
            if (here.flick != Flick.PROTOCOL_VERSION || here.type != "here" || here.port !in 1..65535) continue
            val host = pkt.address?.hostAddress ?: continue
            onFound(
                Receiver(
                    id = here.id, name = here.name.ifBlank { host }, os = here.os, host = host,
                    port = here.port, fingerprint = here.fp.lowercase(), via = "udp",
                ),
            )
        }
    }

    companion object {
        /** 255.255.255.255 plus the directed broadcast address of every active IPv4 interface. */
        fun broadcastTargets(): List<InetAddress> {
            val out = LinkedHashSet<InetAddress>()
            try {
                for (ni in NetworkInterface.getNetworkInterfaces()) {
                    if (!ni.isUp || ni.isLoopback) continue
                    for (ia in ni.interfaceAddresses) {
                        if (ia.address is Inet4Address) ia.broadcast?.let { out.add(it) }
                    }
                }
            } catch (_: Exception) { /* no interfaces */ }
            out.add(InetAddress.getByName("255.255.255.255"))
            return out.toList()
        }
    }
}
