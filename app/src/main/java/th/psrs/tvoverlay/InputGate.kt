package th.psrs.tvoverlay

/**
 * Decides whether the subtitle boxes may be visible: only while the TV's input app (tvx) is in the foreground
 * and the input banner has not said the TV switched to something other than the PS5.
 * Pure logic (no Android types) so it can be unit-tested on the JVM.
 *
 * @param ps5Port the remembered "HDMI n" label of the PS5, or null if never seen.
 */
class InputGate(ps5Port: String?) {
    /** The remembered port label of the PS5 ("HDMI 1"), updated when a banner names PlayStation. */
    var ps5Port: String? = ps5Port
        private set

    private var tvxForeground = false
    private var bannerSaysOtherInput = false

    /** True while the subtitles should be shown. */
    val visible: Boolean get() = tvxForeground && !bannerSaysOtherInput

    /** Feeds the package of the foreground application window (null when unknown). */
    fun foreground(pkg: String?) {
        val isTvx = pkg == TVX_PACKAGE
        if (!isTvx) bannerSaysOtherInput = false // memory cleared: the next visit to tvx decides afresh
        tvxForeground = isTvx
    }

    /** Feeds the non-blank texts of the tvx input banner; ignored while tvx is not in the foreground. */
    fun banner(texts: List<String>) {
        if (!tvxForeground) return
        val port = texts.firstNotNullOfOrNull { t -> t.trim().takeIf { PORT.matches(it) } }
        val isPs5 = texts.any { it.contains("PlayStation", ignoreCase = true) }
        when {
            isPs5 -> {
                if (port != null) ps5Port = port
                bannerSaysOtherInput = false
            }
            port != null -> bannerSaysOtherInput = port != ps5Port
            // neither device name nor port (e.g. only the hint line): nothing changes
        }
    }

    companion object {
        const val TVX_PACKAGE = "com.sony.dtv.tvx"
        private val PORT = Regex("""^HDMI\s*\d+$""")
    }
}
