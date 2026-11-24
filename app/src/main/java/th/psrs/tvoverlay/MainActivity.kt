package th.psrs.tvoverlay

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView

/**
 * Status page: whether our accessibility service is enabled, which PC it follows ([TvStatus]), the app version,
 * and a shortcut to the accessibility settings. Refreshed every second while shown.
 */
class MainActivity : Activity() {

    private lateinit var serviceStatus: TextView
    private lateinit var pcStatus: TextView
    private val main = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            showStatus()
            main.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        serviceStatus = findViewById(R.id.serviceStatus)
        pcStatus = findViewById(R.id.pcStatus)
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        findViewById<TextView>(R.id.version).text = getString(R.string.version, version)
        val button = findViewById<Button>(R.id.openSettings)
        button.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Log.w(TAG, "no accessibility settings screen", e)
            }
        }
        button.requestFocus()
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(refresh)
        refresh.run()
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    private fun showStatus() {
        val enabled = isServiceEnabled()
        serviceStatus.setText(if (enabled) R.string.service_on else R.string.service_off)
        serviceStatus.setTextColor(if (enabled) GOOD else BAD)

        val name = TvStatus.pcName
        val host = TvStatus.pcHost
        val connected = TvStatus.connected
        pcStatus.text = when {
            name == null || host == null -> getString(R.string.pc_none)
            connected -> getString(R.string.pc_connected, name, host)
            else -> getString(R.string.pc_connecting, name, host)
        }
        pcStatus.setTextColor(if (connected) GOOD else NEUTRAL)
    }

    private fun isServiceEnabled(): Boolean {
        val list = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val ours = ComponentName(this, OverlayA11yService::class.java)
        return list.split(':').any { ComponentName.unflattenFromString(it) == ours }
    }

    private companion object {
        const val TAG = "PsrsTv"
        const val REFRESH_MS = 1_000L
        const val GOOD = 0xFF81C784.toInt()
        const val BAD = 0xFFFF8A65.toInt()
        const val NEUTRAL = 0xFFECEFF1.toInt()
    }
}
