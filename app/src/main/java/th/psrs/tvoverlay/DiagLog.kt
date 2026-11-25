package th.psrs.tvoverlay

import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Log header; the PC treats a response without it as "channel unavailable". */
private const val LOG_HEADER = "psrs-diag-log v1\n"

/**
 * In-memory ring plus rolling file log. Never throws. The ring is guarded by a lock;
 * all file I/O runs on [io] (which must be single-threaded or serial).
 */
class DiagLogCore(
    private val dir: File?,
    private val maxEntries: Int = 500,
    private val maxFileBytes: Long = 256 * 1024,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: Executor,
    private val sink: (level: Char, msg: String, t: Throwable?) -> Unit,
) {
    private val lock = Any()
    private val ring = ArrayDeque<String>()

    fun log(level: Char, msg: String, t: Throwable? = null) {
        try {
            val entry = format(level, msg, t)
            synchronized(lock) {
                ring.addLast(entry)
                while (ring.size > maxEntries) ring.removeFirst()
            }
            if (dir != null) {
                try {
                    io.execute { appendToFile(entry) }
                } catch (_: Throwable) {
                }
            }
            try {
                sink(level, msg, t)
            } catch (_: Throwable) {
            }
        } catch (_: Throwable) {
        }
    }

    fun snapshot(): List<String> = synchronized(lock) { ring.toList() }

    /**
     * Header + diag.1.log + diag.log. The read is queued on [io] behind pending writes so
     * unflushed entries are included; if that takes over 2 s, falls back to the ring snapshot.
     */
    fun readAll(): String {
        try {
            if (dir == null) return ringFallback()
            val task = FutureTask { LOG_HEADER + readFile("diag.1.log") + readFile("diag.log") }
            io.execute(task)
            return task.get(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
            return try { ringFallback() } catch (_: Throwable) { LOG_HEADER }
        }
    }

    private fun ringFallback(): String {
        val sb = StringBuilder(LOG_HEADER)
        for (e in snapshot()) sb.append(e).append('\n')
        return sb.toString()
    }

    private fun readFile(name: String): String {
        val f = File(dir, name)
        return try {
            if (f.exists()) f.readText(Charsets.UTF_8) else ""
        } catch (e: Throwable) {
            "(could not read $name: ${e.javaClass.simpleName})\n"
        }
    }

    private fun appendToFile(entry: String) {
        try {
            val d = dir ?: return
            if (!d.isDirectory && !d.mkdirs()) return
            val cur = File(d, "diag.log")
            if (cur.length() >= maxFileBytes) {
                val old = File(d, "diag.1.log")
                old.delete()
                cur.renameTo(old)
            }
            cur.appendText(entry + "\n", Charsets.UTF_8)
        } catch (_: Throwable) {
        }
    }

    private fun format(level: Char, msg: String, t: Throwable?): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        val sb = StringBuilder(fmt.format(Date(clock()))).append(' ').append(level).append(' ').append(msg)
        if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            sb.append('\n').append(sw.toString().trimEnd())
        }
        return sb.toString()
    }
}

/** Process-wide log. Before [init] it keeps the ring only. */
object DiagLog {
    private const val TAG = "PsrsTv"

    private val io: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "psrs-diaglog").apply { isDaemon = true }
    }

    @Volatile
    private var core: DiagLogCore = newCore(null)

    private fun newCore(dir: File?) = DiagLogCore(dir, io = io) { level, msg, t ->
        when (level) {
            'E' -> Log.e(TAG, msg, t)
            'W' -> Log.w(TAG, msg, t)
            else -> Log.i(TAG, msg, t)
        }
    }

    @Synchronized
    fun init(dir: File) {
        core = newCore(dir)
    }

    fun i(msg: String) = core.log('I', msg)
    fun w(msg: String, t: Throwable? = null) = core.log('W', msg, t)
    fun e(msg: String, t: Throwable? = null) = core.log('E', msg, t)
    fun readAll(): String = core.readAll()
}

/** First 8 lowercase hex chars of SHA-256(UTF-8). Same function on the PC side. */
fun pcIdHash(id: String): String =
    MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
        .take(4).joinToString("") { "%02x".format(it) }
