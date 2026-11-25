package th.psrs.tvoverlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.Executor

class DiagLogTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val direct = Executor { it.run() }

    private fun core(
        dir: File?,
        maxEntries: Int = 500,
        maxFileBytes: Long = 256 * 1024,
        sink: (Char, String, Throwable?) -> Unit = { _, _, _ -> },
    ) = DiagLogCore(dir, maxEntries, maxFileBytes, { 0L }, direct, sink)

    @Test
    fun ring_keepsLastEntries() {
        val log = core(null, maxEntries = 3)
        for (i in 1..5) log.log('I', "m$i")
        val s = log.snapshot()
        assertEquals(3, s.size)
        assertTrue(s[0].endsWith("m3"))
        assertTrue(s[2].endsWith("m5"))
    }

    @Test
    fun line_format() {
        val log = core(null)
        log.log('W', "x")
        assertEquals("1970-01-01T00:00:00.000Z W x", log.snapshot()[0])
    }

    @Test
    fun throwable_addsStackTrace() {
        val log = core(null)
        log.log('E', "boom", IllegalStateException("bad"))
        val e = log.snapshot()[0]
        assertTrue(e.contains("IllegalStateException: bad"))
        assertTrue(e.contains("\tat "))
    }

    @Test
    fun file_rollsAtCap() {
        val dir = tmp.newFolder()
        val log = core(dir, maxFileBytes = 100)
        for (i in 1..12) log.log('I', "message number $i")
        assertTrue(File(dir, "diag.1.log").exists())
        assertTrue(File(dir, "diag.log").length() <= 100 + 60)
        assertFalse(File(dir, "diag.2.log").exists())
    }

    @Test
    fun readAll_startsWithHeader_oldestFirst() {
        val dir = tmp.newFolder()
        val log = core(dir, maxFileBytes = 100)
        for (i in 1..12) log.log('I', "message number $i")
        val all = log.readAll()
        assertTrue(all.startsWith("psrs-diag-log v1\n"))
        val old = File(dir, "diag.1.log").readText()
        val cur = File(dir, "diag.log").readText()
        assertEquals("psrs-diag-log v1\n" + old + cur, all)
    }

    @Test
    fun unwritableDir_neverThrows() {
        val notDir = tmp.newFile()
        val log = core(notDir)
        for (i in 1..10) log.log('I', "m$i")
        assertEquals(10, log.snapshot().size)
    }

    @Test
    fun sinkThrowing_neverThrows() {
        val log = core(null, sink = { _, _, _ -> throw RuntimeException("sink") })
        log.log('I', "x")
        assertEquals(1, log.snapshot().size)
    }

    @Test
    fun pcIdHash_knownVector() {
        assertEquals("ba7816bf", pcIdHash("abc"))
    }
}
