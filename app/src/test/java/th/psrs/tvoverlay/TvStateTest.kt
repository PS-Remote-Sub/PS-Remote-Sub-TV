package th.psrs.tvoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvStateTest {
    private val config = """{"v":1,"enabled":true,"pcName":"DESK","mode":"cover","textScale":120,"boxOpacity":0.75,
        "stripBottom":8,"calibration":{"scale":1.1,"offsetX":0.01,"offsetY":-0.02},"calibrating":true}"""

    private fun zone(id: String, text: String = "hi", extra: String = "") =
        """{"zoneId":"$id","view":{"placement":"custom","captionBox":{"x":0.1,"y":0.7,"width":0.8,"height":0.2},
        "ocrBox":null,"bounds":null,"fontSize":30,"provisional":false,$extra
        "rows":[{"speaker":null,"text":"$text","cover":null}]}}"""

    @Test
    fun config_parsed() {
        val s = TvState()
        assertTrue(s.apply("config", config))
        val c = s.config!!
        assertTrue(c.enabled)
        assertEquals("DESK", c.pcName)
        assertEquals(Mode.COVER, c.mode)
        assertEquals(120, c.textScale)
        assertEquals(0.75, c.boxOpacity, 1e-9)
        assertEquals(8, c.stripBottom)
        assertEquals(TvCalibration(1.1, 0.01, -0.02), c.calibration)
        assertTrue(c.calibrating)
    }

    @Test
    fun config_unchanged_returnsFalse() {
        val s = TvState()
        s.apply("config", config)
        assertFalse(s.apply("config", config))
    }

    @Test
    fun zone_setAndHide() {
        val s = TvState()
        val full = """{"zoneId":"z1","view":{"placement":"fit","captionBox":{"x":0.1,"y":0.7,"width":0.8,"height":0.2},
            "ocrBox":{"x":0.2,"y":0.3,"width":0.4,"height":0.1},"bounds":{"x":0,"y":0,"width":1,"height":1},
            "fontSize":30,"provisional":true,"rows":[
            {"speaker":{"name":"Lucia","color":"#FFD54F"},"text":"Hello","cover":{"x":0.2,"y":0.3,"width":0.4,"height":0.05}},
            {"speaker":{"name":"Bo","color":null},"text":"Yo","cover":null}]}}"""
        assertTrue(s.apply("zone", full))
        val v = s.zones.getValue("z1")
        assertEquals(Placement.FIT, v.placement)
        assertEquals(Rect(0.1, 0.7, 0.8, 0.2), v.captionBox)
        assertEquals(Rect(0.2, 0.3, 0.4, 0.1), v.ocrBox)
        assertEquals(Rect(0.0, 0.0, 1.0, 1.0), v.bounds)
        assertEquals(30.0, v.fontSize, 0.0)
        assertTrue(v.provisional)
        assertEquals(Row(Speaker("Lucia", "#FFD54F"), "Hello", Rect(0.2, 0.3, 0.4, 0.05)), v.rows[0])
        assertEquals(Row(Speaker("Bo", null), "Yo", null), v.rows[1])

        assertFalse(s.apply("zone", full)) // same view: nothing to redraw
        assertTrue(s.apply("zone", """{"zoneId":"z1","view":null}"""))
        assertTrue(s.zones.isEmpty())
        assertFalse(s.apply("zone", """{"zoneId":"z1","view":null}"""))
    }

    @Test
    fun zone_missingOptionals_areNull() {
        val s = TvState()
        assertTrue(s.apply("zone", """{"zoneId":"a","view":{"placement":"custom",
            "captionBox":{"x":0,"y":0,"width":1,"height":1},"fontSize":20,"provisional":false,"rows":[{"text":"t"}]}}"""))
        val v = s.zones.getValue("a")
        assertNull(v.ocrBox)
        assertNull(v.bounds)
        assertNull(v.rows[0].speaker)
        assertNull(v.rows[0].cover)
    }

    @Test
    fun zone_unknownFields_ignored() {
        val s = TvState()
        assertTrue(s.apply("zone", zone("a", extra = """"future":{"x":1},""")))
        assertEquals("hi", s.zones.getValue("a").rows[0].text)
        assertTrue(s.apply("config", config.replace("\"v\":1,", "\"v\":1,\"newThing\":[1,2],")))
        assertFalse(s.apply("ping", "{}"))
    }

    @Test
    fun unknownEnum_ignored() {
        val s = TvState()
        assertFalse(s.apply("config", config.replace("\"cover\"", "\"hologram\"")))
        assertNull(s.config)
        assertFalse(s.apply("zone", zone("a").replace("\"custom\"", "\"weird\"")))
        assertTrue(s.zones.isEmpty())
    }

    @Test
    fun badJson_returnsFalse_noThrow() {
        val s = TvState()
        assertFalse(s.apply("config", "not json"))
        assertFalse(s.apply("zone", "{"))
        assertFalse(s.apply("zone", """{"zoneId":"a","view":{"placement":"fit"}}"""))
        assertFalse(s.apply("config", "{}"))
        assertNull(s.config)
        assertTrue(s.zones.isEmpty())
    }

    @Test
    fun reset_clearsZonesAndConfig() {
        val s = TvState()
        s.apply("config", config)
        s.apply("zone", zone("a"))
        s.reset()
        assertNull(s.config)
        assertTrue(s.zones.isEmpty())
    }
}
