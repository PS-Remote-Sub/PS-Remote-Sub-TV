package th.psrs.tvoverlay

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout

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
        windowManager().addView(root, params)
        overlayRoot = root
        guarded("feed start") { startFeed(root) }
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
        state.reset()
        renderer?.clear()
        renderer = null
        TvStatus.set(null, connected = false)
    }

    private fun onBeacon(beacon: PcBeacon) = guarded("beacon") {
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
        disconnect()
        if (pick != null) connect(pick)
    }

    private fun connect(pc: PcBeacon) {
        picker?.use(pc.id)
        prefs().edit().putString(PREF_LAST_PC, pc.id).apply()
        connectedPc = pc
        TvStatus.set(pc, connected = false)
        // Callbacks from a client that was replaced or stopped are dropped (posted before stop, run after).
        lateinit var c: TvEventsClient
        c = TvEventsClient(
            pc.host, pc.port,
            onConnecting = {},
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
        guarded("client stop") { old?.stop() }
        state.reset()
        hideBannerNow()
        TvStatus.set(null, connected = false)
        requestRender()
    }

    private fun onOpen(pc: PcBeacon) {
        state.reset() // the PC re-sends its config and every zone on connect
        TvStatus.set(pc, connected = true)
        banner = getString(R.string.banner_connected, pc.name)
        main.removeCallbacks(hideBanner)
        main.postDelayed(hideBanner, BANNER_MS)
        requestRender()
    }

    private fun onEvent(name: String, data: String) {
        if (state.apply(name, data)) requestRender()
    }

    private fun onFailure(reason: String) {
        Log.i(TAG, "PC stream dropped: $reason")
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
    }

    /** Runs [block], logging instead of throwing: an exception must never kill the service. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "$what failed", e)
        }
    }

    /** The PC alone decides what is drawn; accessibility events are not needed. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        stopFeed()
        removeOverlay()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopFeed()
        removeOverlay()
        super.onDestroy()
    }

    private fun windowManager() = getSystemService(WINDOW_SERVICE) as WindowManager

    /** Safe to call repeatedly. */
    private fun removeOverlay() {
        val root = overlayRoot ?: return
        overlayRoot = null
        runCatching { windowManager().removeView(root) }
    }

    private companion object {
        const val TAG = "PsrsTv"
        const val PREFS = "psrs"
        const val PREF_LAST_PC = "lastPcId"
        const val TICK_MS = 1_000L
        const val BANNER_MS = 5_000L
    }
}
