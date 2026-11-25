package th.psrs.tvoverlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Accessibility service that owns one full-screen, untouchable overlay window drawn over whatever the TV shows
 * (including the HDMI input), finds the PC by its beacon, follows its `/tv/events` stream and draws the subtitles.
 * The overlay is always attached and visible; the PC alone decides what is drawn (nothing while not connected).
 */
class OverlayA11yService : AccessibilityService() {

    /** Full-screen container for everything drawn over the TV; null while the service is not connected. */
    internal var overlayRoot: FrameLayout? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val state = TvState()
    private var renderer: OverlayRenderer? = null
    private var picker: PcPicker? = null
    private var beacons: BeaconListener? = null
    private var client: TvEventsClient? = null
    /** The PC [client] follows (id, address, name), or null. */
    private var connectedPc: PcBeacon? = null
    /** The status line drawn at the top, or null. */
    private var banner: String? = null
    private var renderPending = false

    // ---- Diagnostics state (main thread only; read by the snapshot, which is built on the main thread). ----
    private var serviceConnected = false
    private var overlayError: String? = null
    private var appVersion = "unknown"
    private var connectedAtMs = 0L
    private var sseState = SSE_IDLE
    private var sseLastFailure: String? = null
    private var beaconCount = 0L
    private var eventCount = 0L
    private var renderCount = 0L
    private var guardedFailureCount = 0L
    private val seenPcs = HashSet<String>()
    /** Last successfully built snapshot, returned when the main thread does not answer in time. */
    @Volatile private var cachedSnapshot: DiagSnapshot? = null

    /** Re-checks every second which PC to follow, so one that stops beaconing is dropped. */
    private val tick = object : Runnable {
        override fun run() {
            guarded("tick") { updateConnection() }
            main.postDelayed(this, TICK_MS)
        }
    }
    private val hideBanner = Runnable {
        banner = null
        requestRender()
    }
    private val renderNow = Runnable {
        renderPending = false
        guarded("render") { render() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        stopFeed() // the system may reconnect without destroying us first
        removeOverlay()
        serviceConnected = true
        connectedAtMs = SystemClock.elapsedRealtime()
        appVersion = readAppVersion()
        beaconCount = 0; eventCount = 0; renderCount = 0; guardedFailureCount = 0
        seenPcs.clear()
        sseLastFailure = null
        overlayError = null
        cachedSnapshot = null
        DiagState.supplier = ::snapshotFromAnyThread
        DiagLog.i("service connected, app $appVersion")
        val root = FrameLayout(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        try {
            windowManager().addView(root, params)
        } catch (e: Throwable) {
            overlayError = "${e.javaClass.simpleName}: ${e.message}"
            DiagLog.e("overlay addView failed", e)
            return // keep running without a root: renderer and feed do nothing
        }
        overlayRoot = root
        DiagLog.i("overlay added")
        guarded("feed start") { startFeed(root) }
    }

    @Suppress("DEPRECATION")
    private fun readAppVersion(): String = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        "${info.versionName} ($code)"
    } catch (e: Exception) {
        "unknown"
    }

    // ---- Diagnostics snapshot ----

    /** Callable from any thread: builds on the main thread, falling back to the last cached snapshot. */
    private fun snapshotFromAnyThread(): DiagSnapshot {
        if (Looper.myLooper() == Looper.getMainLooper()) return buildSnapshot()
        var built: DiagSnapshot? = null
        val latch = CountDownLatch(1)
        main.post {
            try {
                built = buildSnapshot()
            } catch (_: Throwable) {
            } finally {
                latch.countDown()
            }
        }
        val ok = try { latch.await(SNAPSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { false }
        return (if (ok) built else null) ?: cachedSnapshot ?: throw IllegalStateException("no snapshot available")
    }

    private fun buildSnapshot(): DiagSnapshot {
        val now = SystemClock.elapsedRealtime()
        val pc = connectedPc
        val config = state.config
        val drawing = when {
            sseState != SSE_OPEN -> "no:not-connected"
            config != null && !config.enabled && !config.calibrating -> "no:pc-off"
            state.zones.isEmpty() && banner == null -> "no:nothing"
            else -> "yes"
        }
        val snap = DiagSnapshot(
            appVersion = appVersion,
            serviceConnected = serviceConnected,
            overlayAttached = overlayRoot != null,
            overlayError = overlayError,
            drawing = drawing,
            connectedPc = pc?.let { HeardPc(pcIdHash(it.id), it.host, it.port, 0) },
            sseState = sseState,
            sseLastFailure = sseLastFailure,
            heard = picker?.heardList(now).orEmpty().map { (b, age) -> HeardPc(pcIdHash(b.id), b.host, b.port, age) },
            beacons = beaconCount,
            events = eventCount,
            renders = renderCount,
            guardedFailures = guardedFailureCount,
            uptimeMs = now - connectedAtMs,
        )
        cachedSnapshot = snap
        return snap
    }

    // ---- PC feed: beacon → picker → /tv/events → render. Everything below runs on the main thread. ----

    /** Finds the PC by its beacon, follows its stream and draws it into [root]. */
    private fun startFeed(root: FrameLayout) {
        renderer = OverlayRenderer(root)
        // The first layout (or a size change) gives the real screen size to draw against.
        root.addOnLayoutChangeListener { _, l, t, r, b, oldL, oldT, oldR, oldB ->
            if (r - l != oldR - oldL || b - t != oldB - oldT) requestRender()
        }
        picker = PcPicker(prefs().getString(PREF_LAST_PC, null))
        lateinit var listener: BeaconListener
        listener = BeaconListener(this) { beacon -> main.post { if (listener === beacons) onBeacon(beacon) } }
        beacons = listener
        main.post(tick)
        listener.start()
    }

    private fun stopFeed() {
        main.removeCallbacks(tick)
        main.removeCallbacks(hideBanner)
        main.removeCallbacks(renderNow)
        renderPending = false
        guarded("beacon stop") { beacons?.stop() }
        beacons = null
        guarded("client stop") { client?.stop() }
        client = null
        connectedPc = null
        picker = null
        banner = null
        sseState = SSE_IDLE
        state.reset()
        renderer?.clear()
        renderer = null
        TvStatus.set(null, connected = false)
    }

    private fun onBeacon(beacon: PcBeacon) = guarded("beacon") {
        beaconCount++
        if (seenPcs.add(beacon.id)) DiagLog.i("first beacon from PC ${pcIdHash(beacon.id)} at ${beacon.host}:${beacon.port}")
        picker?.heard(beacon, SystemClock.elapsedRealtime())
        updateConnection()
    }

    /**
     * Follows the picker's choice: when it is another PC (or the same PC at another address), drops the old stream
     * and connects to the new one; with no PC heard for a while, disconnects.
     */
    private fun updateConnection() {
        val pick = picker?.choice(SystemClock.elapsedRealtime())
        val current = connectedPc
        if (pick?.id == current?.id && pick?.host == current?.host && pick?.port == current?.port) {
            if (pick != null && pick.name != current?.name) {
                connectedPc = pick
                TvStatus.pcName = pick.name
            }
            return
        }
        if (current != null) DiagLog.i("PC dropped: ${pcIdHash(current.id)}")
        disconnect()
        if (pick != null) connect(pick)
    }

    private fun connect(pc: PcBeacon) {
        picker?.use(pc.id)
        prefs().edit().putString(PREF_LAST_PC, pc.id).apply()
        connectedPc = pc
        sseState = SSE_CONNECTING
        DiagLog.i("connecting to PC ${pcIdHash(pc.id)} at ${pc.host}:${pc.port}")
        TvStatus.set(pc, connected = false)
        // Callbacks from a client that was replaced or stopped are dropped (posted before stop, run after).
        lateinit var c: TvEventsClient
        c = TvEventsClient(
            pc.host, pc.port,
            onConnecting = { main.post { if (c === client && sseState != SSE_FAILED) sseState = SSE_CONNECTING } },
            onOpen = { main.post { if (c === client) guarded("open") { onOpen(pc) } } },
            onEvent = { name, data -> main.post { if (c === client) guarded("event $name") { onEvent(name, data) } } },
            onFailure = { reason -> main.post { if (c === client) guarded("failure") { onFailure(reason) } } },
        )
        client = c
        c.start()
    }

    private fun disconnect() {
        val old = client
        client = null
        connectedPc = null
        sseState = SSE_IDLE
        guarded("client stop") { old?.stop() }
        state.reset()
        hideBannerNow()
        TvStatus.set(null, connected = false)
        requestRender()
    }

    private fun onOpen(pc: PcBeacon) {
        sseState = SSE_OPEN
        DiagLog.i("stream open to PC ${pcIdHash(pc.id)}")
        state.reset() // the PC re-sends its config and every zone on connect
        TvStatus.set(pc, connected = true)
        banner = getString(R.string.banner_connected, pc.name)
        main.removeCallbacks(hideBanner)
        main.postDelayed(hideBanner, BANNER_MS)
        requestRender()
    }

    private fun onEvent(name: String, data: String) {
        eventCount++
        val before = state.config
        val changed = state.apply(name, data)
        val after = state.config
        if (after != null && (before?.enabled != after.enabled || before.mode != after.mode)) {
            DiagLog.i("config: enabled=${after.enabled} mode=${after.mode}")
        }
        if (changed) requestRender()
    }

    private fun onFailure(reason: String) {
        sseState = SSE_FAILED
        sseLastFailure = reason
        DiagLog.i("PC stream dropped: $reason")
        // Nothing is drawn while not connected; the client reconnects by itself.
        state.reset()
        TvStatus.connected = false
        hideBannerNow()
        requestRender()
    }

    private fun hideBannerNow() {
        main.removeCallbacks(hideBanner)
        banner = null
    }

    /** Renders once on the next main-loop turn, however many events arrive before it. */
    private fun requestRender() {
        if (renderPending) return
        renderPending = true
        main.post(renderNow)
    }

    private fun render() {
        val r = renderer ?: return
        val root = overlayRoot ?: return
        if (root.width == 0 || root.height == 0) return // not laid out yet; the layout listener renders then
        r.render(state, root.width, root.height, banner)
        renderCount++
    }

    /** Runs [block], logging instead of throwing: an exception must never kill the service. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            guardedFailureCount++
            DiagLog.w("$what failed", e)
        }
    }

    /** The PC alone decides what is drawn; accessibility events are not needed. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        DiagLog.i("service unbind")
        markDisconnected()
        stopFeed()
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        DiagLog.i("service destroy")
        markDisconnected()
        stopFeed()
        removeOverlay()
        super.onDestroy()
    }

    private fun markDisconnected() {
        serviceConnected = false
        DiagState.supplier = null
    }

    private fun windowManager() = getSystemService(WINDOW_SERVICE) as WindowManager

    /** Safe to call repeatedly. */
    private fun removeOverlay() {
        val root = overlayRoot ?: return
        overlayRoot = null
        runCatching { windowManager().removeView(root) }
    }

    private companion object {
        const val SSE_IDLE = "idle"
        const val SSE_CONNECTING = "connecting"
        const val SSE_OPEN = "open"
        const val SSE_FAILED = "failed"
        const val SNAPSHOT_TIMEOUT_MS = 1_000L
        const val PREFS = "psrs"
        const val PREF_LAST_PC = "lastPcId"
        const val TICK_MS = 1_000L
        const val BANNER_MS = 5_000L
    }
}
