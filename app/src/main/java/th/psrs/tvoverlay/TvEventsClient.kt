package th.psrs.tvoverlay

/**
 * Follows the PC's `GET http://host:port/tv/events` stream through [SseClient] (reconnects every 3 s).
 * Callbacks run on the SSE thread; the owner posts them to the main looper.
 */
class TvEventsClient(
    host: String,
    port: Int,
    onConnecting: () -> Unit,
    onOpen: () -> Unit,
    onEvent: (name: String, data: String) -> Unit,
    onFailure: (reason: String) -> Unit,
) {
    private val sse = SseClient("http://$host:$port/tv/events", onConnecting, onOpen, onEvent, onFailure)

    fun start() = sse.start()

    fun stop() = sse.stop()
}
