package th.psrs.tvoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InputGateTest {
    private val tvx = "com.sony.dtv.tvx"
    private val hint = "กดปุ่มเพื่อเปลี่ยนอินพุต"

    @Test
    fun home_hides() {
        val g = InputGate(null)
        g.foreground("com.google.android.apps.tv.launcherx")
        assertFalse(g.visible)
    }

    @Test
    fun tvx_noBanner_shows() {
        val g = InputGate(null)
        g.foreground(tvx)
        assertTrue(g.visible)
    }

    @Test
    fun tvx_playStationBanner_shows_andRemembersPort() {
        val g = InputGate(null)
        g.foreground(tvx)
        g.banner(listOf("PlayStation 5", "HDMI 1", hint))
        assertTrue(g.visible)
        assertEquals("HDMI 1", g.ps5Port)
    }

    @Test
    fun tvx_otherHdmi_hides() {
        val g = InputGate("HDMI 1")
        g.foreground(tvx)
        g.banner(listOf("Fire TV", "HDMI 2"))
        assertFalse(g.visible)
        assertEquals("HDMI 1", g.ps5Port)
    }

    @Test
    fun rememberedPort_withoutName_shows() {
        val g = InputGate("HDMI 1")
        g.foreground(tvx)
        g.banner(listOf("HDMI 2"))
        assertFalse(g.visible)
        g.banner(listOf(" HDMI 1 "))
        assertTrue(g.visible)
    }

    @Test
    fun youtube_thenTvx_needsFreshDecision() {
        val g = InputGate("HDMI 1")
        g.foreground(tvx)
        g.banner(listOf("HDMI 2"))
        g.foreground("com.google.android.youtube.tv")
        assertFalse(g.visible)
        g.foreground(tvx)
        assertTrue(g.visible)
    }

    @Test
    fun hintOnlyBanner_changesNothing() {
        val g = InputGate("HDMI 1")
        g.foreground(tvx)
        g.banner(listOf("HDMI 2"))
        g.banner(listOf(hint))
        assertFalse(g.visible)
    }

    @Test
    fun portWithoutRememberedPs5_hides_andNameIsCaseInsensitive() {
        val g = InputGate(null)
        g.foreground(tvx)
        g.banner(listOf("HDMI2"))
        assertFalse(g.visible)
        g.banner(listOf("playstation 5", "HDMI 3"))
        assertTrue(g.visible)
        assertEquals("HDMI 3", g.ps5Port)
    }

    @Test
    fun fullRealSequence() {
        val g = InputGate(null)
        g.foreground(tvx)
        g.banner(listOf("PlayStation 5", "HDMI 1", hint))
        assertTrue(g.visible)
        g.foreground("com.google.android.apps.tv.launcherx")
        assertFalse(g.visible)
        g.foreground("com.google.android.youtube.tv")
        assertFalse(g.visible)
        g.foreground(tvx)
        assertTrue(g.visible)
        g.banner(listOf("HDMI 2"))
        assertFalse(g.visible)
        g.banner(listOf("PlayStation 5", "HDMI 1"))
        assertTrue(g.visible)
    }

    @Test
    fun playStationBannerWithoutPort_keepsOldPs5Port() {
        val g = InputGate("HDMI 1")
        g.foreground(tvx)
        g.banner(listOf("PlayStation 5", hint))
        assertTrue(g.visible)
        assertEquals("HDMI 1", g.ps5Port)
    }
}
