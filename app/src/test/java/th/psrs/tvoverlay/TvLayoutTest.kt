package th.psrs.tvoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvLayoutTest {
    private val client = Px(0, 0, 1920, 1080)
    private val region = Rect(0.1, 0.8, 0.8, 0.15)

    // Caption box (576,918,768,86) in the 1920x1080 client.
    private val captionBox = Rect(0.3, 0.85, 0.4, 0.08)

    private fun caption(frame: Px, box: Rect, contentH: Int) =
        TvLayout.caption(frame, TvLayout.toScreen(box, frame), contentH)

    private fun fit(frame: Px, text: Rect, contentW: Int, contentH: Int) =
        TvLayout.fit(frame, TvLayout.toScreen(region, frame), TvLayout.toScreen(text, frame), contentW, contentH)

    // ---- OverlayLayoutTests: Caption ----

    @Test fun caption_contentFits_isTheBox() =
        assertEquals(Px(576, 918, 768, 86), caption(client, captionBox, 60))

    @Test fun caption_contentTaller_growsUpOnly() =
        assertEquals(Px(576, 864, 768, 140), caption(client, captionBox, 140))

    @Test fun caption_growthClampedAtClientTop() {
        val r = caption(Px(100, 50, 1920, 1080), Rect(0.3, 0.02, 0.4, 0.08), 400)
        assertEquals(50, r.y)
        assertEquals(400, r.h)
    }

    @Test fun caption_neverTallerThanClient() {
        val r = caption(client, captionBox, 5000)
        assertEquals(1080, r.h)
        assertEquals(0, r.y)
    }

    @Test fun caption_offsetClient_addsOrigin() {
        val r = caption(Px(2560, 0, 2880, 1620), captionBox, 50)
        assertEquals(2560 + 864, r.x)
        assertEquals(1377, r.y)
    }

    @Test fun caption_nonPositiveContent_keepsTheBox() =
        assertEquals(Px(576, 918, 768, 86), caption(client, captionBox, 0))

    // ---- OverlayLayoutTests: Fit (previous height 0). Text bounds (768,918,384,54); region (192,864,1536,162). ----
    private val text = Rect(0.4, 0.85, 0.2, 0.05)

    @Test fun fit_shortText_boxEqualsTextBounds() =
        assertEquals(Px(768, 918, 384, 54), fit(client, text, 200, 40))

    @Test fun fit_longText_growsBothWaysEqually() =
        assertEquals(Px(660, 918, 600, 54), fit(client, text, 600, 40))

    @Test fun fit_textWiderThanRegion_cappedAtRegionWidth() =
        assertEquals(Px(192, 905, 1536, 80), fit(client, text, 3000, 80))

    @Test fun fit_nearClientBottom_shiftedInside() {
        val r = fit(client, Rect(0.4, 0.97, 0.2, 0.05), 200, 80)
        assertEquals(client.bottom, r.bottom)
        assertEquals(80, r.h)
    }

    @Test fun fit_offsetClient_usesClientOrigin() =
        assertEquals(Px(2560 + 768, 100 + 918, 384, 54), fit(Px(2560, 100, 1920, 1080), text, 200, 40))

    // ---- FitRowsTests ----
    private fun fitRows(english: List<Px>, thai: List<Pair<Int, Int>>) = TvLayout.fitRows(client, english, thai, 8)

    @Test fun fitRows_smallerThai_coversEachEnglishLineExactly() {
        val english = listOf(Px(100, 100, 400, 40), Px(80, 150, 440, 40))
        assertEquals(english, fitRows(english, listOf(300 to 30, 350 to 30)))
    }

    @Test fun fitRows_widerThai_growsAboutTheEnglishCentre() =
        assertEquals(listOf(Px(50, 100, 500, 40)), fitRows(listOf(Px(100, 100, 400, 40)), listOf(500 to 30)))

    @Test fun fitRows_tallerThai_growsAboutTheEnglishMiddle() =
        assertEquals(listOf(Px(100, 90, 400, 60)), fitRows(listOf(Px(100, 100, 400, 40)), listOf(300 to 60)))

    @Test fun fitRows_touchingLines_keepTheGap_fromTheirMargins() =
        assertEquals(
            listOf(Px(100, 100, 400, 46), Px(100, 154, 400, 46)),
            fitRows(listOf(Px(100, 100, 400, 50), Px(100, 150, 400, 50)), listOf(300 to 30, 300 to 30)),
        )

    @Test fun fitRows_tallThaiAbove_pushesTheNextRowDown() =
        assertEquals(
            listOf(Px(100, 80, 400, 80), Px(100, 168, 400, 30)),
            fitRows(listOf(Px(100, 100, 400, 40), Px(100, 150, 400, 40)), listOf(300 to 80, 300 to 30)),
        )

    @Test fun fitRows_keptInsideTheClient() =
        assertEquals(listOf(Px(0, 100, 600, 40)), fitRows(listOf(Px(10, 100, 100, 40)), listOf(600 to 30)))

    // ---- FontFitTests: three lines of text, content height is three times the font size ----
    private val threeLines: (Float) -> Int = { (it * 3).toInt() }

    @Test fun fontFit_fitsAtPreferred_returnsPreferred() = assertEquals(28f, TvLayout.fontFit(28f, 100, threeLines), 0f)

    @Test fun fontFit_tooTall_stepsDownByOne() = assertEquals(25f, TvLayout.fontFit(28f, 75, threeLines), 0f)

    @Test fun fontFit_neverBelow16() = assertEquals(16f, TvLayout.fontFit(28f, 10, threeLines), 0f)

    @Test fun fontFit_preferredBelow16_isTheMinimum() = assertEquals(12f, TvLayout.fontFit(12f, 10, threeLines), 0f)

    @Test fun fontFit_fractionalPreferred_triesItFirst() = assertEquals(27.5f, TvLayout.fontFit(28.5f, 84, threeLines), 0f)

    @Test fun fontFit_minimumIs16() = assertEquals(16f, TvLayout.MIN_FIT_FONT_SIZE, 0f)

    // ---- Rounding and frame ----
    @Test fun roundAway_halvesGoAwayFromZero() {
        assertEquals(3, TvLayout.roundAway(2.5))
        assertEquals(-3, TvLayout.roundAway(-2.5))
        assertEquals(-2, TvLayout.roundAway(-2.4))
        assertEquals(0, TvLayout.roundAway(0.4))
    }

    @Test fun toScreen_clipsAndGuaranteesOnePixel() {
        val frame = Px(10, 20, 100, 50)
        assertEquals(Px(10 + 99, 20 + 49, 1, 1), TvLayout.toScreen(Rect(2.0, 2.0, 0.0, 0.0), frame))
    }

    @Test fun frameOnScreen_identity() =
        assertEquals(Px(0, 0, 1920, 1080), TvLayout.frameOnScreen(1920, 1080, TvCalibration(1.0, 0.0, 0.0)))

    @Test fun frameOnScreen_scale110_centred() =
        assertEquals(Px(-96, -54, 2112, 1188), TvLayout.frameOnScreen(1920, 1080, TvCalibration(1.1, 0.0, 0.0)))

    @Test fun frameOnScreen_offset() =
        assertEquals(Px(96, -54, 1920, 1080), TvLayout.frameOnScreen(1920, 1080, TvCalibration(1.0, 0.05, -0.05)))

    @Test fun fontPx_1080_is_profile_size() = assertEquals(30f, TvLayout.fontPx(30.0, 100, 1080), 0f)

    @Test fun fontPx_scalesWithTextScaleAndFrameHeight() {
        assertEquals(45f, TvLayout.fontPx(30.0, 150, 1080), 0.001f)
        assertEquals(40f, TvLayout.fontPx(30.0, 100, 1440), 0.001f)
        assertTrue(TvLayout.fontPx(27.5, 100, 1080) == 27.5f)
    }
}
