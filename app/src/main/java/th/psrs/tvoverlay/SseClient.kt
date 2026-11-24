package th.psrs.tvoverlay

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the PC's Server-Sent Events stream (`GET /events` of PS Remote Sub) on its own thread and reconnects
 * after 3 s whenever the connection drops. Callbacks run on that thread.
 */
class SseClient(
    private val url: String,
    private val onConnecting: () -> Unit,
    private val onOpen: () -> Unit,
    private val onEvent: (name: String, data: String) -> Unit,
    private val onFailure: (reason: String) -> Unit,
) {
    @Volatile private var running = false
    @Volatile private var connection: HttpURLConnection? = null
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "sse").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        connection?.disconnect()
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running) {
            try {
                onConnecting()
                readOnce()
                if (running) onFailure("closed")
            } catch (e: Exception) {
                if (running) onFailure(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
            }
            if (!running) break
            try {
                Thread.sleep(3000)
            } catch (_: InterruptedException) {
                break
            }
        }
    }

    private fun readOnce() {
        val conn = URL(url).openConnection() as HttpURLConnection
        connection = conn
        conn.connectTimeout = 5000
        conn.readTimeout = 40_000 // the server sends a ping every 15 s
        conn.setRequestProperty("Accept", "text/event-stream")
        conn.useCaches = false
        try {
            if (conn.responseCode != 200) throw IllegalStateException("HTTP ${conn.responseCode}")
            onOpen()
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var event = "message"
                val data = StringBuilder()
                while (running) {
                    val line = reader.readLine() ?: return
                    when {
                        line.isEmpty() -> {
                            if (data.isNotEmpty()) onEvent(event, data.toString())
                            event = "message"
                            data.setLength(0)
                        }
                        line.startsWith(":") -> Unit // ping / comment
                        line.startsWith("event:") -> event = line.substring(6).trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substring(5).removePrefix(" "))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
            connection = null
        }
    }
}
