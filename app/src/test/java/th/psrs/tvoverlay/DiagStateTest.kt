package th.psrs.tvoverlay

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagStateTest {
    private fun snapshot(connected: HeardPc?, heard: List<HeardPc>) = DiagSnapshot(
        appVersion = "1.1.0 (3)", serviceConnected = true, overlayAttached = true, overlayError = null,
        drawing = "yes", connectedPc = connected, sseState = "open", sseLastFailure = null,
        heard = heard, beacons = 12, events = 34, renders = 5, guardedFailures = 1, uptimeMs = 60_000,
    )

    @After
    fun reset() { DiagState.supplier = null }

    @Test
    fun format_matchesContract() {
        val pc = HeardPc("a1b2c3d4", "192.168.1.5", 5000, 0)
        val s = snapshot(pc, listOf(HeardPc("a1b2c3d4", "192.168.1.5", 5000, 300))).format()
        assertEquals(
            "psrs-diag-state v1\n" +
                "service.running=true\n" +
                "app.version=1.1.0 (3)\n" +
                "service.connected=true\n" +
                "overlay.attached=true\n" +
                "overlay.error=\n" +
                "drawing=yes\n" +
                "pc.connected=a1b2c3d4 192.168.1.5:5000\n" +
                "sse.state=open\n" +
                "sse.lastFailure=\n" +
                "pc.heard=a1b2c3d4 192.168.1.5:5000 300\n" +
                "count.beacons=12\n" +
                "count.events=34\n" +
                "count.renders=5\n" +
                "count.guardedFailures=1\n" +
                "uptime.ms=60000\n",
            s,
        )
    }

    @Test
    fun format_noPc() {
        val s = snapshot(null, emptyList()).format()
        assertTrue(s.contains("pc.connected=none\n"))
        assertFalse(s.contains("pc.heard="))
        assertTrue(s.contains("overlay.error=\n"))
    }

    @Test
    fun notRunning_format() {
        assertEquals("psrs-diag-state v1\nservice.running=false\n", formatServiceNotRunning())
    }

    @Test
    fun text_withoutSupplier_isNotRunning() {
        DiagState.supplier = null
        assertEquals(formatServiceNotRunning(), DiagState.text())
    }

    @Test
    fun text_supplierThrows_isNotRunning() {
        DiagState.supplier = { throw IllegalStateException("boom") }
        assertEquals(formatServiceNotRunning(), DiagState.text())
    }

    @Test
    fun text_usesSupplier() {
        DiagState.supplier = { snapshot(null, emptyList()) }
        assertTrue(DiagState.text().startsWith("psrs-diag-state v1\nservice.running=true\n"))
    }
}
