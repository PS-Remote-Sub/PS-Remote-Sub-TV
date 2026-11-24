package th.psrs.tvoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayPlannerTest {
    private val gap = 6

    private fun config(
        enabled: Boolean = true,
        mode: Mode = Mode.COVER,
        textScale: Int = 100,
        stripBottom: Int = 8,
        calibrating: Boolean = false,
        cal: TvCalibration = TvCalibration(1.0, 0.0, 0.0),
    ) = TvConfig(enabled, "PC", mode, textScale, 0.75, stripBottom, cal, calibrating)

    private fun row(text: String, cover: Rect? = null) = Row(null, text, cover)

    private fun view(
        placement: Placement,
        rows: List<Row>,
        captionBox: Rect = Rect(0.3, 0.85, 0.4, 0.08),
        ocrBox: Rect? = Rect(0.1, 0.7, 0.8, 0.25),
        bounds: Rect? = Rect(0.3, 0.8, 0.4, 0.1),
        fontSize: Double = 30.0,
        provisional: Boolean = false,
    ) = ZoneView(placement, captionBox, ocrBox, bounds, fontSize, provisional, rows)

    /** Every row is 10 px per character wide (capped at the width) and 40 px tall; calls are recorded. */
    private class FakeMeasure(val lineH: Int = 40) {
        val calls = ArrayList<Triple<List<String>, Float, Int>>()
        val measure: Measure = { lines, font, maxW ->
            calls.add(Triple(lines.map { it.row.text }, font, maxW))
            Pair(minOf(lines.maxOf { it.row.text.length } * 10, maxW), lines.size * lineH)
        }
    }

    private fun plan(config: TvConfig?, zones: List<ZoneView>, m: FakeMeasure = FakeMeasure(), w: Int = 1920, h: Int = 1080) =
        OverlayPlanner.plan(config, zones, w, h, gap, "sample", m.measure)

    // ---- nothing to draw ----

    @Test fun noConfig_drawsNothing() {
        val p = plan(null, listOf(view(Placement.CUSTOM, listOf(row("a")))))
        assertTrue(p.boxes.isEmpty())
        assertNull(p.calibrationFrame)
    }

    @Test fun disabled_drawsNothing() {
        val p = plan(config(enabled = false), listOf(view(Placement.CUSTOM, listOf(row("a")))))
        assertTrue(p.boxes.isEmpty())
        assertNull(p.calibrationFrame)
    }

    @Test fun zoneWithoutRows_drawsNothing() =
        assertTrue(plan(config(), listOf(view(Placement.CUSTOM, emptyList()))).boxes.isEmpty())

    // ---- calibration ----

    @Test fun calibrating_whileDisabled_drawsFrameAndSampleOnly() {
        val cal = TvCalibration(0.9, 0.02, -0.01)
        val p = plan(config(enabled = false, calibrating = true, cal = cal), listOf(view(Placement.CUSTOM, listOf(row("a")))))
        val frame = TvLayout.frameOnScreen(1920, 1080, cal)
        assertEquals(frame, p.calibrationFrame)
        assertEquals(1, p.boxes.size)
        assertEquals(TvLayout.toScreen(OverlayPlanner.CALIBRATION_SAMPLE, frame), p.boxes[0].px)
        assertEquals("sample", p.boxes[0].lines.single().row.text)
    }

    @Test fun calibrating_whileEnabled_drawsSubtitlesToo() {
        val p = plan(config(calibrating = true), listOf(view(Placement.CUSTOM, listOf(row("a")))))
        assertEquals(2, p.boxes.size)
        assertEquals(Px(0, 0, 1920, 1080), p.calibrationFrame)
    }

    // ---- cover: fit with every row's game line known ----

    @Test fun fitAllCovers_oneBoxPerRowOverItsGameLine() {
        val covers = listOf(Rect(0.3, 0.80, 0.4, 0.04), Rect(0.3, 0.85, 0.4, 0.04))
        val m = FakeMeasure()
        val p = plan(config(), listOf(view(Placement.FIT, listOf(row("first", covers[0]), row("second", covers[1])))), m)

        val frame = Px(0, 0, 1920, 1080)
        val ocr = TvLayout.toScreen(Rect(0.1, 0.7, 0.8, 0.25), frame)
        // Each row measured on its own at the OCR box width, at the profile size scaled to the frame.
        assertEquals(listOf("first"), m.calls[0].first)
        assertEquals(30f, m.calls[0].second, 0.001f)
        assertEquals(ocr.w, m.calls[0].third)
        val expected = TvLayout.fitRows(
            frame, covers.map { TvLayout.toScreen(it, frame) },
            listOf(Pair(50 + OverlayPlanner.FIT_SLACK_PX, 40), Pair(60 + OverlayPlanner.FIT_SLACK_PX, 40)), gap,
        )
        assertEquals(expected, p.boxes.map { it.px })
        assertEquals(listOf("first", "second"), p.boxes.map { it.lines.single().row.text })
    }

    // ---- cover: fit with the text bounds but not every line ----

    @Test fun fitMissingCover_rowsStackedInsideTheFitBox() {
        val m = FakeMeasure()
        val zone = view(Placement.FIT, listOf(row("first", Rect(0.3, 0.8, 0.4, 0.04)), row("second!!")))
        val p = plan(config(), listOf(zone), m)

        val frame = Px(0, 0, 1920, 1080)
        val ocr = TvLayout.toScreen(zone.ocrBox!!, frame)
        val text = TvLayout.toScreen(zone.bounds!!, frame)
        val fit = TvLayout.fit(frame, ocr, text, 80 + OverlayPlanner.FIT_SLACK_PX, 40 + gap + 40)
        assertTrue(m.calls.all { it.third == ocr.w })
        // Stack (86 px tall) centred in the fit box; each row centred horizontally at its own width.
        val top = fit.y + (fit.h - 86) / 2
        assertEquals(
            listOf(
                Px(fit.x + (fit.w - 50) / 2, top, 50, 40),
                Px(fit.x + (fit.w - 80) / 2, top + 40 + gap, 80, 40),
            ),
            p.boxes.map { it.px },
        )
    }

    // ---- cover: custom (and fit lacking bounds) ----

    @Test fun custom_contentFits_keepsFontAndCaptionBox() {
        val m = FakeMeasure()
        val zone = view(Placement.CUSTOM, listOf(row("hello")))
        val p = plan(config(), listOf(zone), m)
        val box = TvLayout.toScreen(zone.captionBox, Px(0, 0, 1920, 1080)) // (576,918,768,86)
        assertEquals(30f, p.boxes.single().fontPx, 0.001f)
        assertTrue(m.calls.all { it.third == box.w })
        val rect = TvLayout.caption(Px(0, 0, 1920, 1080), box, 40)
        assertEquals(Px(rect.x + (rect.w - 50) / 2, rect.y + (rect.h - 40) / 2, 50, 40), p.boxes.single().px)
    }

    @Test fun custom_tooTall_shrinksFontDownTo16ThenGrowsUp() {
        // Height = 4 px per font pixel: at 30 px the row is 120 px, the box is 86 px; nothing fits above 21.
        val measure: Measure = { lines, font, maxW -> Pair(maxW / 2, lines.size * (font * 4).toInt()) }
        val zone = view(Placement.CUSTOM, listOf(row("hello")))
        val p = OverlayPlanner.plan(config(), listOf(zone), 1920, 1080, gap, "s", measure)
        assertEquals(21f, p.boxes.single().fontPx, 0.001f)

        // Two rows: 2*64 + 6 at 16 px never fits 86 → 16 px and the box grows upwards (bottom fixed at 1004).
        val two = OverlayPlanner.plan(config(), listOf(view(Placement.CUSTOM, listOf(row("a"), row("b")))), 1920, 1080, gap, "s", measure)
        assertEquals(16f, two.boxes[0].fontPx, 0.001f)
        assertEquals(1004 - 134, two.boxes[0].px.y)
        assertEquals(1004, two.boxes[1].px.bottom)
    }

    @Test fun fitWithoutBounds_usesTheCaptionBox() {
        val zone = view(Placement.FIT, listOf(row("hello", Rect(0.3, 0.8, 0.4, 0.04))), bounds = null)
        val p = plan(config(), listOf(zone))
        val box = TvLayout.toScreen(zone.captionBox, Px(0, 0, 1920, 1080))
        assertEquals(box.y + (box.h - 40) / 2, p.boxes.single().px.y)
    }

    @Test fun textScaleAndFrameHeight_scaleTheFont() {
        val m = FakeMeasure()
        plan(config(textScale = 150), listOf(view(Placement.FIT, listOf(row("a", Rect(0.3, 0.8, 0.4, 0.04))))), m, 1280, 720)
        assertEquals(30f, m.calls[0].second, 0.001f) // 30 * 1.5 * 720 / 1080
    }

    @Test fun provisional_dimsTheLines() {
        val p = plan(config(), listOf(view(Placement.CUSTOM, listOf(row("a")), provisional = true)))
        assertTrue(p.boxes.single().lines.single().dimmed)
    }

    @Test fun calibration_movesTheBoxes() {
        val cal = TvCalibration(1.0, 0.05, 0.0) // shifted 96 px right
        val zone = view(Placement.CUSTOM, listOf(row("hello")))
        val plain = plan(config(), listOf(zone)).boxes.single().px
        val moved = plan(config(cal = cal), listOf(zone)).boxes.single().px
        assertEquals(plain.copy(x = plain.x + 96), moved)
    }

    // ---- strip ----

    @Test fun strip_allZonesRowsInOneCentredBox() {
        val m = FakeMeasure()
        val zones = listOf(
            view(Placement.FIT, listOf(row("one", Rect(0.3, 0.8, 0.4, 0.04)), row("two"))),
            view(Placement.CUSTOM, listOf(row("three!")), provisional = true),
        )
        val p = plan(config(mode = Mode.STRIP, stripBottom = 10), zones, m)

        val box = p.boxes.single()
        assertEquals(listOf("one", "two", "three!"), box.lines.map { it.row.text })
        assertEquals(listOf(false, false, true), box.lines.map { it.dimmed })
        assertEquals(1920 * 85 / 100, m.calls.single().third)
        // 60 x 120, centred, bottom 10 % (108 px) above the frame's bottom.
        assertEquals(Px((1920 - 60) / 2, 1080 - 108 - 120, 60, 120), box.px)
        assertNull(p.calibrationFrame)
    }

    @Test fun strip_tallContent_clampedAtFrameTop() {
        val p = plan(config(mode = Mode.STRIP, stripBottom = 60), listOf(view(Placement.CUSTOM, List(20) { row("x") })))
        assertEquals(0, p.boxes.single().px.y)
    }

    @Test fun strip_noRows_noBox() =
        assertTrue(plan(config(mode = Mode.STRIP), listOf(view(Placement.CUSTOM, emptyList()))).boxes.isEmpty())
}
