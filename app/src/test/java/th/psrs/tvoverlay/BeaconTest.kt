package th.psrs.tvoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BeaconTest {
    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)

    @Test
    fun parse_valid() {
        val b = parseBeacon(bytes("""{"app":"psrs","v":1,"id":"pc-1","name":"DESK","port":5000,"extra":true}"""), "192.168.1.5")
        assertEquals(PcBeacon("pc-1", "DESK", "192.168.1.5", 5000), b)
    }

    @Test
    fun parse_wrongApp_or_v2_or_garbage() {
        assertNull(parseBeacon(bytes("""{"app":"other","v":1,"id":"a","name":"n","port":1}"""), "h"))
        assertNull(parseBeacon(bytes("""{"app":"psrs","v":2,"id":"a","name":"n","port":1}"""), "h"))
        assertNull(parseBeacon(bytes("not json"), "h"))
        assertNull(parseBeacon(bytes("""{"app":"psrs","v":1,"name":"n","port":1}"""), "h"))
    }
}
