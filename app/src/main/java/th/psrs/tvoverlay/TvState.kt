package th.psrs.tvoverlay

import org.json.JSONObject

/** A rectangle in fractions of the game frame (letterbox removed). */
data class Rect(val x: Double, val y: Double, val width: Double, val height: Double)

/** How the PC's subtitles are drawn: over the game's own text (cover) or in a strip at the bottom. */
enum class Mode { COVER, STRIP }

/** Where a zone's caption sits: the user's custom box, or fitted to the detected text. */
enum class Placement { CUSTOM, FIT }

/** Calibration of the game frame inside the TV picture. */
data class TvCalibration(val scale: Double, val offsetX: Double, val offsetY: Double)

/** The PC's `config` event. */
data class TvConfig(
    val enabled: Boolean,
    val pcName: String,
    val mode: Mode,
    val textScale: Int,
    val boxOpacity: Double,
    val stripBottom: Int,
    val calibration: TvCalibration,
    val calibrating: Boolean,
)

/** A speaker tag; [color] is `#RRGGBB` or null. */
data class Speaker(val name: String, val color: String?)

/** One caption row; [cover] is the rectangle hiding the game's own text for this row, if any. */
data class Row(val speaker: Speaker?, val text: String, val cover: Rect?)

/** What to draw for one zone. */
data class ZoneView(
    val placement: Placement,
    val captionBox: Rect,
    val ocrBox: Rect?,
    val bounds: Rect?,
    val fontSize: Double,
    val provisional: Boolean,
    val rows: List<Row>,
)

/**
 * The latest `config` and per-zone view streamed by the PC on `/tv/events`.
 *
 * Not thread-safe: the accessibility service owns it and touches it on the main thread only
 * (SseClient callbacks are posted to the main looper).
 */
class TvState {
    var config: TvConfig? = null
        private set

    private val zoneViews = LinkedHashMap<String, ZoneView>()
    val zones: Map<String, ZoneView> get() = zoneViews

    /** Forget everything; the service calls this on every (re)connect and on failure. */
    fun reset() {
        config = null
        zoneViews.clear()
    }

    /**
     * Applies one event. Returns true only when what is drawn changed (config replaced, zone set/changed/removed).
     * Malformed JSON, unknown events and unknown mode/placement values are ignored and return false.
     */
    fun apply(event: String, data: String): Boolean = try {
        when (event) {
            "config" -> applyConfig(JSONObject(data))
            "zone" -> applyZone(JSONObject(data))
            else -> false
        }
    } catch (_: Exception) {
        false
    }

    private fun applyConfig(j: JSONObject): Boolean {
        val mode = when (j.getString("mode")) {
            "cover" -> Mode.COVER
            "strip" -> Mode.STRIP
            else -> return false
        }
        val cal = j.getJSONObject("calibration")
        val parsed = TvConfig(
            enabled = j.getBoolean("enabled"),
            pcName = j.getString("pcName"),
            mode = mode,
            textScale = j.getInt("textScale"),
            boxOpacity = j.getDouble("boxOpacity"),
            stripBottom = j.getInt("stripBottom"),
            calibration = TvCalibration(cal.getDouble("scale"), cal.getDouble("offsetX"), cal.getDouble("offsetY")),
            calibrating = j.getBoolean("calibrating"),
        )
        if (parsed == config) return false
        config = parsed
        return true
    }

    private fun applyZone(j: JSONObject): Boolean {
        val id = j.getString("zoneId")
        if (j.isNull("view")) return zoneViews.remove(id) != null
        val view = parseView(j.getJSONObject("view")) ?: return false
        if (zoneViews[id] == view) return false
        zoneViews[id] = view
        return true
    }

    private fun parseView(v: JSONObject): ZoneView? {
        val placement = when (v.getString("placement")) {
            "custom" -> Placement.CUSTOM
            "fit" -> Placement.FIT
            else -> return null
        }
        val rowsJson = v.getJSONArray("rows")
        val rows = (0 until rowsJson.length()).map { i ->
            val r = rowsJson.getJSONObject(i)
            Row(parseSpeaker(r.optJSONObject("speaker")), r.getString("text"), parseRect(r.optJSONObject("cover")))
        }
        return ZoneView(
            placement = placement,
            captionBox = parseRect(v.getJSONObject("captionBox"))!!,
            ocrBox = parseRect(v.optJSONObject("ocrBox")),
            bounds = parseRect(v.optJSONObject("bounds")),
            fontSize = v.getDouble("fontSize"),
            provisional = v.getBoolean("provisional"),
            rows = rows,
        )
    }

    private fun parseSpeaker(s: JSONObject?): Speaker? =
        s?.let { Speaker(it.getString("name"), if (it.isNull("color")) null else it.getString("color")) }

    private fun parseRect(r: JSONObject?): Rect? =
        r?.let { Rect(it.getDouble("x"), it.getDouble("y"), it.getDouble("width"), it.getDouble("height")) }
}
