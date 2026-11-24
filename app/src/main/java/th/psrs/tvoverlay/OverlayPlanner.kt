package th.psrs.tvoverlay

import kotlin.math.max
import kotlin.math.min

/** One caption row to draw; [dimmed] while its line is provisional (the AI may still replace it). */
data class Line(val row: Row, val dimmed: Boolean)

/** A rounded black box at [px] (screen pixels) with [lines] stacked inside it, drawn at [fontPx]. */
data class OverlayBox(val px: Px, val lines: List<Line>, val fontPx: Float)

/** Everything to draw. [calibrationFrame] is the game frame to outline while the PC calibrates, else null. */
data class OverlayPlan(val boxes: List<OverlayBox>, val calibrationFrame: Px?)

/**
 * The size (width to height, padding included) of one box holding [lines] at [fontPx], its text wrapped to fit
 * [maxWidthPx]; the width is the text's natural width, at most [maxWidthPx].
 */
typealias Measure = (lines: List<Line>, fontPx: Float, maxWidthPx: Int) -> Pair<Int, Int>

/**
 * Decides which boxes to draw where, the way the PC overlay places them (`OverlayWindow.ShowText`); pure, so the
 * Android drawing ([OverlayRenderer]) only measures text and adds views.
 */
object OverlayPlanner {
    /** Extra width for fitted boxes so the text never re-wraps on rounding (the PC's `FitSlackPx`). */
    const val FIT_SLACK_PX = 2

    /** The strip is at most this share of the frame width. */
    const val STRIP_WIDTH_PERCENT = 85

    /** Where the calibration sample box sits, in fractions of the game frame. */
    val CALIBRATION_SAMPLE = Rect(0.1, 0.8, 0.8, 0.12)

    /** Profile font size the calibration sample is drawn at (before text scale). */
    const val CALIBRATION_FONT_SIZE = 30.0

    /**
     * Plans the overlay for [config] and the zones' views (in zone order) on a [screenW] x [screenH] screen.
     * Nothing is drawn without a config or while it is disabled, except the calibration frame and sample box
     * ([sampleText]) while calibrating. [gapPx] separates stacked boxes.
     */
    fun plan(
        config: TvConfig?,
        zones: Collection<ZoneView>,
        screenW: Int,
        screenH: Int,
        gapPx: Int,
        sampleText: String,
        measure: Measure,
    ): OverlayPlan {
        if (config == null) return OverlayPlan(emptyList(), null)
        val frame = TvLayout.frameOnScreen(screenW, screenH, config.calibration)
        val boxes = ArrayList<OverlayBox>()
        if (config.enabled) {
            val visible = zones.filter { it.rows.isNotEmpty() }
            when (config.mode) {
                Mode.COVER -> visible.forEach { boxes.addAll(cover(it, frame, config.textScale, gapPx, measure)) }
                Mode.STRIP -> strip(visible, frame, config, measure)?.let(boxes::add)
            }
        }
        if (!config.calibrating) return OverlayPlan(boxes, null)
        val sample = Line(Row(null, sampleText, null), dimmed = false)
        val font = TvLayout.fontPx(CALIBRATION_FONT_SIZE, config.textScale, frame.h)
        boxes.add(OverlayBox(TvLayout.toScreen(CALIBRATION_SAMPLE, frame), listOf(sample), font))
        return OverlayPlan(boxes, frame)
    }

    /** One zone in cover mode: over the game's lines (fit) or in the user's caption box (custom). */
    private fun cover(view: ZoneView, frame: Px, textScale: Int, gapPx: Int, measure: Measure): List<OverlayBox> {
        val lines = view.rows.map { Line(it, view.provisional) }
        val fontPx = TvLayout.fontPx(view.fontSize, textScale, frame.h)
        val ocrBox = view.ocrBox
        val bounds = view.bounds
        if (view.placement == Placement.FIT && ocrBox != null && bounds != null) {
            // At the profile's font size, measured at the OCR box's width.
            val ocr = TvLayout.toScreen(ocrBox, frame)
            val sizes = lines.map { measure(listOf(it), fontPx, ocr.w) }
            if (view.rows.all { it.cover != null }) {
                // Each row its own box over its game line.
                val english = view.rows.map { TvLayout.toScreen(it.cover!!, frame) }
                val content = sizes.map { (w, h) -> Pair(w + FIT_SLACK_PX, h) }
                val placed = TvLayout.fitRows(frame, english, content, gapPx)
                return lines.indices.map { OverlayBox(placed[it], listOf(lines[it]), fontPx) }
            }
            // One block over the game's subtitle.
            val contentW = sizes.maxOf { it.first } + FIT_SLACK_PX
            val rect = TvLayout.fit(frame, ocr, TvLayout.toScreen(bounds, frame), contentW, stackHeight(sizes, gapPx))
            return stack(rect, lines, sizes, fontPx, gapPx)
        }
        // The caption box: the font shrinks (down to 16) before the box grows upwards.
        val box = TvLayout.toScreen(view.captionBox, frame)
        val chosen = TvLayout.fontFit(fontPx, box.h) { size ->
            stackHeight(lines.map { measure(listOf(it), size, box.w) }, gapPx)
        }
        val sizes = lines.map { measure(listOf(it), chosen, box.w) }
        val rect = TvLayout.caption(frame, box, stackHeight(sizes, gapPx))
        return stack(rect, lines, sizes, chosen, gapPx)
    }

    /** Every zone's rows in one box, centred, [TvConfig.stripBottom] percent of the frame above its bottom. */
    private fun strip(zones: List<ZoneView>, frame: Px, config: TvConfig, measure: Measure): OverlayBox? {
        val lines = zones.flatMap { z -> z.rows.map { Line(it, z.provisional) } }
        if (lines.isEmpty()) return null
        val fontPx = TvLayout.fontPx(zones.first().fontSize, config.textScale, frame.h)
        val maxW = frame.w * STRIP_WIDTH_PERCENT / 100
        val (contentW, contentH) = measure(lines, fontPx, maxW)
        val w = min(contentW, maxW)
        val h = min(contentH, frame.h)
        val bottom = frame.bottom - TvLayout.roundAway(frame.h * config.stripBottom / 100.0)
        val y = max(bottom - h, frame.y)
        return OverlayBox(Px(frame.x + (frame.w - w) / 2, y, w, h), lines, fontPx)
    }

    private fun stackHeight(sizes: List<Pair<Int, Int>>, gapPx: Int): Int =
        sizes.sumOf { it.second } + gapPx * (sizes.size - 1)

    /** Rows stacked in the middle of [rect] (like the PC's centred panel), each centred at its own width. */
    private fun stack(rect: Px, lines: List<Line>, sizes: List<Pair<Int, Int>>, fontPx: Float, gapPx: Int): List<OverlayBox> {
        var y = max(rect.y + (rect.h - stackHeight(sizes, gapPx)) / 2, rect.y)
        return lines.indices.map { i ->
            val w = min(sizes[i].first, rect.w)
            val h = sizes[i].second
            OverlayBox(Px(rect.x + (rect.w - w) / 2, y, w, h), listOf(lines[i]), fontPx).also { y += h + gapPx }
        }
    }
}
