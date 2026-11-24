package th.psrs.tvoverlay

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** A rectangle in whole pixels. */
data class Px(val x: Int, val y: Int, val w: Int, val h: Int) {
    val right: Int get() = x + w
    val bottom: Int get() = y + h
}

/**
 * Port of the PC's `OverlayLayout`, `FontFit` and `RegionFraction.ToPixels`; "client" is the calibrated game frame.
 * Keep the arithmetic identical to the C# so the TV draws boxes where the PC overlay would.
 */
object TvLayout {
    /** Smallest font size [fontFit] shrinks to; past it the box grows instead. */
    const val MIN_FIT_FONT_SIZE = 16f

    /** C# `Math.Round(v, MidpointRounding.AwayFromZero)` (Kotlin's `roundToInt` rounds halves toward +infinity). */
    fun roundAway(v: Double): Int = (if (v < 0) -floor(-v + 0.5) else floor(v + 0.5)).toInt()

    /** The game frame on the TV screen: the screen scaled about its centre, then shifted by a fraction of the screen. */
    fun frameOnScreen(screenW: Int, screenH: Int, cal: TvCalibration): Px {
        val w = roundAway(screenW * cal.scale)
        val h = roundAway(screenH * cal.scale)
        val x = roundAway((screenW - w) / 2.0 + cal.offsetX * screenW)
        val y = roundAway((screenH - h) / 2.0 + cal.offsetY * screenH)
        return Px(x, y, w, h)
    }

    /** `RegionFraction.ToPixels` relative to [frame] (rounded, clipped, at least 1x1), then offset by the frame origin. */
    fun toScreen(r: Rect, frame: Px): Px {
        val x = roundAway(r.x * frame.w).coerceIn(0, max(frame.w - 1, 0))
        val y = roundAway(r.y * frame.h).coerceIn(0, max(frame.h - 1, 0))
        val w = roundAway(r.width * frame.w).coerceIn(1, max(frame.w - x, 1))
        val h = roundAway(r.height * frame.h).coerceIn(1, max(frame.h - y, 1))
        return Px(x + frame.x, y + frame.y, w, h)
    }

    /**
     * Custom: the caption [box] (screen pixels). Content taller than the box grows it upwards only (bottom fixed),
     * clamped at the frame top; never taller than the frame. The width is always the box's.
     */
    fun caption(frame: Px, box: Px, contentH: Int): Px {
        val height = min(max(box.h, max(contentH, 1)), frame.h)
        val y = max(box.bottom - height, frame.y)
        return Px(box.x, y, box.w, height)
    }

    /**
     * Fit: a box centred on the game's subtitle ([text]) and never smaller than it. Wider content grows it both ways up
     * to the width of [ocr] (the OCR region); shifted (not shrunk) to stay inside the frame.
     */
    fun fit(frame: Px, ocr: Px, text: Px, contentW: Int, contentH: Int): Px {
        var width = min(max(text.w, contentW), max(ocr.w, text.w))
        val captionHeight = max(text.h, max(contentH, 1))
        var x = text.x + (text.w - width) / 2
        var y = text.y + (text.h - captionHeight) / 2

        width = min(width, frame.w)
        val height = min(captionHeight, frame.h)
        x = x.coerceIn(frame.x, max(frame.right - width, frame.x))
        y = y.coerceIn(frame.y, max(frame.bottom - height, frame.y))
        return Px(x, y, width, height)
    }

    /**
     * Fit with one box per caption row: each row's box covers the game's line under it ([english]) and grows about that
     * line's centre when its text ([content], width to height) needs more room. Rows closer than [gap] make the gap from
     * the room their text leaves, and only then is the lower row moved down.
     */
    fun fitRows(frame: Px, english: List<Px>, content: List<Pair<Int, Int>>, gap: Int): List<Px> {
        require(english.size == content.size) { "One content size per row." }
        val rows = ArrayList<Px>(english.size)
        for (i in english.indices) {
            val e = english[i]
            val w = min(max(e.w, content[i].first), frame.w)
            var h = max(e.h, content[i].second)
            val x = (e.x + (e.w - w) / 2).coerceIn(frame.x, max(frame.right - w, frame.x))
            var y = e.y + (e.h - h) / 2
            if (i > 0) {
                val prev = rows[i - 1]
                val need = prev.bottom + gap - y
                if (need > 0) {
                    val prevRoom = max(prev.h - content[i - 1].second, 0)
                    val room = max(h - content[i].second, 0)
                    val fromPrev0 = min(prevRoom, need / 2)
                    val fromThis = min(room, need - fromPrev0)
                    val fromPrev = fromPrev0 + min(prevRoom - fromPrev0, need - fromPrev0 - fromThis)
                    rows[i - 1] = prev.copy(h = prev.h - fromPrev)
                    y += fromThis
                    h -= fromThis
                    y += need - fromPrev - fromThis
                }
            }
            rows.add(Px(x, y, w, h))
        }
        return rows
    }

    /**
     * The largest size from [preferred] down, in steps of 1, whose content ([heightAt], pixels at a font size) fits
     * [boxH]; never below [MIN_FIT_FONT_SIZE] (or [preferred] when smaller), which is returned when nothing fits.
     */
    fun fontFit(preferred: Float, boxH: Int, heightAt: (Float) -> Int): Float {
        val minSize = min(MIN_FIT_FONT_SIZE, preferred)
        var size = preferred
        while (size >= minSize) {
            if (heightAt(size) <= boxH) return size
            size -= 1f
        }
        return minSize
    }

    /** Font size in pixels: the profile size scaled by the text scale (percent) and the frame height against 1080. */
    fun fontPx(profileFontSize: Double, textScale: Int, frameH: Int): Float =
        (profileFontSize * textScale / 100.0 * frameH / 1080.0).toFloat()
}
