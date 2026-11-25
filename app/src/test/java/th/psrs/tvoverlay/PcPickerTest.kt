package th.psrs.tvoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PcPickerTest {
    private fun pc(id: String) = PcBeacon(id, "name-$id", "10.0.0.$id", 5000)

    @Test
    fun picker_prefersLastId() {
        val p = PcPicker("2")
        p.heard(pc("1"), 0); p.heard(pc("2"), 100)
        assertEquals("2", p.choice(200)?.id)
    }

    @Test
    fun picker_firstHeardWithoutLast() {
        val p = PcPicker(null)
        p.heard(pc("1"), 0); p.heard(pc("2"), 100); p.heard(pc("1"), 200)
        assertEquals("1", p.choice(300)?.id)
    }

    @Test
    fun picker_dropsStale() {
        val p = PcPicker(null)
        p.heard(pc("1"), 0)
        assertNull(p.choice(10_001))
    }

    @Test
    fun picker_switchesWhenChosenGoesStale() {
        val p = PcPicker("2")
        p.heard(pc("1"), 0); p.heard(pc("2"), 0)
        p.heard(pc("1"), 9_000)
        assertEquals("1", p.choice(10_500)?.id)
    }

    @Test
    fun picker_useChangesPreference() {
        val p = PcPicker(null)
        p.heard(pc("1"), 0); p.heard(pc("2"), 0)
        p.use("2")
        assertEquals("2", p.choice(100)?.id)
    }

    @Test
    fun picker_updatesHostAndPort() {
        val p = PcPicker("1")
        p.heard(pc("1"), 0)
        p.heard(PcBeacon("1", "n", "10.9.9.9", 6000), 50)
        assertEquals(PcBeacon("1", "n", "10.9.9.9", 6000), p.choice(100))
    }

    @Test
    fun heardList_reportsAges() {
        val p = PcPicker(null)
        p.heard(pc("1"), 0); p.heard(pc("2"), 100)
        val l = p.heardList(300)
        assertEquals(listOf("1" to 300L, "2" to 200L), l.map { it.first.id to it.second })
    }
}
