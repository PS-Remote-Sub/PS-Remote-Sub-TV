package th.psrs.tvoverlay

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The last uncaught exception, kept in one small file and exposed as `psrs-diag-crash v1`. */
object CrashRecord {
    private const val HEADER = "psrs-diag-crash v1\n"

    /** Overwrites [file] with the crash. Never throws. */
    fun write(file: File, thread: String, t: Throwable, nowMs: Long) {
        try {
            val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            fmt.timeZone = TimeZone.getTimeZone("UTC")
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val name = thread.replace('\r', ' ').replace('\n', ' ')
            file.writeText(
                HEADER + "time=${fmt.format(Date(nowMs))} thread=$name\n" + sw.toString().trimEnd() + "\n",
                Charsets.UTF_8,
            )
        } catch (_: Throwable) {
        }
    }

    /** Header plus the record, or `none`. Never throws. */
    fun read(file: File): String = try {
        if (file.isFile) {
            val body = file.readText(Charsets.UTF_8)
            if (body.startsWith(HEADER)) body else HEADER + body
        } else HEADER + "none\n"
    } catch (_: Throwable) {
        HEADER + "none\n"
    }
}
