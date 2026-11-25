package th.psrs.tvoverlay

/** A PC heard on the LAN, identified only by its [idHash] (never the raw id or machine name). */
data class HeardPc(val idHash: String, val host: String, val port: Int, val ageMs: Long)

/** Point-in-time view of the service, rendered as the `psrs-diag-state v1` text. */
data class DiagSnapshot(
    val appVersion: String,
    val serviceConnected: Boolean,
    val overlayAttached: Boolean,
    val overlayError: String?,
    val drawing: String,
    val connectedPc: HeardPc?,
    val sseState: String,
    val sseLastFailure: String?,
    val heard: List<HeardPc>,
    val beacons: Long,
    val events: Long,
    val renders: Long,
    val guardedFailures: Long,
    val uptimeMs: Long,
)

private const val STATE_HEADER = "psrs-diag-state v1\n"

/** Values are single-line: newlines would break the key=value contract. */
private fun oneLine(s: String?): String = (s ?: "").replace('\r', ' ').replace('\n', ' ')

fun DiagSnapshot.format(): String {
    val sb = StringBuilder(STATE_HEADER)
    sb.append("service.running=true\n")
    sb.append("app.version=").append(oneLine(appVersion)).append('\n')
    sb.append("service.connected=").append(serviceConnected).append('\n')
    sb.append("overlay.attached=").append(overlayAttached).append('\n')
    sb.append("overlay.error=").append(oneLine(overlayError)).append('\n')
    sb.append("drawing=").append(drawing).append('\n')
    sb.append("pc.connected=")
        .append(connectedPc?.let { "${it.idHash} ${it.host}:${it.port}" } ?: "none").append('\n')
    sb.append("sse.state=").append(sseState).append('\n')
    sb.append("sse.lastFailure=").append(oneLine(sseLastFailure)).append('\n')
    for (h in heard) sb.append("pc.heard=").append(h.idHash).append(' ').append(h.host).append(':')
        .append(h.port).append(' ').append(h.ageMs).append('\n')
    sb.append("count.beacons=").append(beacons).append('\n')
    sb.append("count.events=").append(events).append('\n')
    sb.append("count.renders=").append(renders).append('\n')
    sb.append("count.guardedFailures=").append(guardedFailures).append('\n')
    sb.append("uptime.ms=").append(uptimeMs).append('\n')
    return sb.toString()
}

fun formatServiceNotRunning(): String = STATE_HEADER + "service.running=false\n"

/** Process-wide hook the service sets while connected. */
object DiagState {
    /** Must be callable from any thread. */
    @Volatile
    var supplier: (() -> DiagSnapshot)? = null

    fun text(): String = try {
        supplier?.invoke()?.format() ?: formatServiceNotRunning()
    } catch (_: Throwable) {
        formatServiceNotRunning()
    }
}
