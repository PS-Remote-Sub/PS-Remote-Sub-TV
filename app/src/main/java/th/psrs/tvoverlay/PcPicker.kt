package th.psrs.tvoverlay

/**
 * Chooses which heard PC to connect to: the last-used one while it is still being heard, otherwise the
 * PC heard earliest that is still fresh. A PC not heard for [staleMs] is gone. Thread-safe: the listener thread calls [heard] while the service calls [choice].
 */
class PcPicker(private var lastId: String?, private val staleMs: Long = 10_000) {
    private class Entry(var beacon: PcBeacon, var lastHeardMs: Long)

    // Insertion order = first-heard order.
    private val heardPcs = LinkedHashMap<String, Entry>()

    /** Records or refreshes a PC, keeping its latest host/port. */
    @Synchronized fun heard(b: PcBeacon, nowMs: Long) {
        val e = heardPcs[b.id]
        if (e == null) heardPcs[b.id] = Entry(b, nowMs) else { e.beacon = b; e.lastHeardMs = nowMs }
    }

    /** Marks [id] as the PC in use, so it is preferred next time. */
    @Synchronized fun use(id: String) { lastId = id }

    /** The PC to connect to now, or null when none is fresh. */
    @Synchronized fun choice(nowMs: Long): PcBeacon? {
        val fresh = heardPcs.values.filter { nowMs - it.lastHeardMs <= staleMs }
        return (fresh.firstOrNull { it.beacon.id == lastId } ?: fresh.firstOrNull())?.beacon
    }
}
