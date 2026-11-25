package th.psrs.tvoverlay

import android.app.Application
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagLog.init(filesDir)
        installCrashHandler()
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val crashFile = File(filesDir, "crash.txt")
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            // Synchronous on purpose: the process is about to die.
            CrashRecord.write(crashFile, thread.name, t, System.currentTimeMillis())
            try {
                DiagLog.e("uncaught exception on ${thread.name}", t)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, t)
        }
    }
}
