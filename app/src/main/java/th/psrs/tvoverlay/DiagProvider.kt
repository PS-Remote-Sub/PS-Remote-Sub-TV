package th.psrs.tvoverlay

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException

/**
 * Read-only text endpoint for `adb shell content read`. Protected by the DUMP permission in the
 * manifest, which only the adb shell holds, so other apps cannot read it.
 */
class DiagProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw SecurityException("read-only")
        val ctx = context ?: throw FileNotFoundException("no context")
        val produce: () -> String = when (uri.pathSegments.singleOrNull()) {
            "log" -> { { DiagLog.readAll() } }
            "state" -> { { DiagState.text() } }
            "crash" -> { { CrashRecord.read(File(ctx.filesDir, "crash.txt")) } }
            else -> throw FileNotFoundException(uri.toString())
        }
        val pipe = ParcelFileDescriptor.createPipe()
        val reader = pipe[0]
        val writer = pipe[1]
        Thread({
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writer).use { out ->
                    out.write(produce().toByteArray(Charsets.UTF_8))
                }
            } catch (_: Throwable) {
                try { writer.close() } catch (_: Throwable) {}
            }
        }, "diag-provider").start()
        return reader
    }

    override fun getType(uri: Uri): String = "text/plain"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
