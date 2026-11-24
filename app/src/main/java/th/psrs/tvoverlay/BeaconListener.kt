package th.psrs.tvoverlay

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Listens for the PC's UDP beacon on [port] on a daemon thread and calls [onBeacon] (on that thread) for each
 * valid one. Holds a [WifiManager.MulticastLock] while started; rebinds after 3 s if the socket fails.
 * Each start() gets its own run, so a stopped run can never revive when start() is called again.
 */
class BeaconListener(
    context: Context,
    private val port: Int = 47610,
    private val onBeacon: (PcBeacon) -> Unit,
) {
    /** One start()..stop() span: its own liveness token, thread and socket. */
    private class Run {
        val active = AtomicBoolean(true)
        @Volatile var socket: DatagramSocket? = null
        lateinit var thread: Thread
    }

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var run: Run? = null

    @Synchronized
    fun start() {
        if (run != null) return
        val r = Run()
        try {
            lock = wifi.createMulticastLock("psrs-beacon").apply { setReferenceCounted(false); acquire() }
            r.thread = Thread({ loop(r) }, "psrs-beacon").apply { isDaemon = true }
            run = r
            r.thread.start()
        } catch (e: Exception) {
            r.active.set(false)
            run = null
            lock?.let { if (it.isHeld) it.release() }
            lock = null
            throw e
        }
    }

    @Synchronized
    fun stop() {
        val r = run ?: return
        run = null
        r.active.set(false)
        r.socket?.close()
        r.thread.interrupt()
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    private fun loop(r: Run) {
        val buf = ByteArray(2048)
        while (r.active.get()) {
            try {
                val s = DatagramSocket(null)
                r.socket = s
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                if (!r.active.get()) { s.close(); break }
                val packet = DatagramPacket(buf, buf.size)
                while (r.active.get()) {
                    packet.length = buf.size
                    s.receive(packet)
                    val host = packet.address?.hostAddress ?: continue
                    val beacon = parseBeacon(packet.data.copyOf(packet.length), host) ?: continue
                    try {
                        onBeacon(beacon)
                    } catch (e: Exception) {
                        Log.w("PsrsTv", "beacon callback failed: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                if (r.active.get()) Log.w("PsrsTv", "beacon listener error: ${e.message}")
            } finally {
                r.socket?.close()
            }
            if (r.active.get()) {
                try { Thread.sleep(3_000) } catch (_: InterruptedException) { break }
            }
        }
    }
}
