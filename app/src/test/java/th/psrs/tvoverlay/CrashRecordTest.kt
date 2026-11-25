package th.psrs.tvoverlay

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CrashRecordTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun write_thenRead_hasHeaderTimeThreadAndTrace() {
        val f = File(tmp.root, "crash.txt")
        CrashRecord.write(f, "main", IllegalStateException("boom"), 0L)
        val lines = CrashRecord.read(f).lines()
        assertEquals("psrs-diag-crash v1", lines[0])
        assertEquals("time=1970-01-01T00:00:00.000Z thread=main", lines[1])
        assertTrue(lines[2].startsWith("java.lang.IllegalStateException: boom"))
        assertTrue(lines.any { it.contains("CrashRecordTest") })
    }

    @Test
    fun read_missing_isNone() {
        assertEquals("psrs-diag-crash v1\nnone\n", CrashRecord.read(File(tmp.root, "nope.txt")))
    }

    @Test
    fun write_unwritable_neverThrows() {
        // Parent is a regular file, so the write cannot succeed.
        val blocker = tmp.newFile("blocker")
        CrashRecord.write(File(blocker, "crash.txt"), "t", RuntimeException("x"), 1L)
        assertEquals("psrs-diag-crash v1\nnone\n", CrashRecord.read(File(blocker, "crash.txt")))
    }
}
