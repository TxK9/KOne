package cc.txwd.kone.app

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashesTest {
    private val folder: File = Files.createTempDirectory("kone-crashes").toFile()

    @Test
    fun aStopIsKeptWithWhatAndWhereButItsMessageStaysInTheLog() {
        assertNull(Crashes.last(folder))
        val failure = IllegalStateException("https://example.com/sub?token=secret", NullPointerException("inner"))
        Crashes.record(failure, folder, now = 1_000)
        val stop = Crashes.last(folder)!!
        assertEquals(1_000, stop.at)
        assertTrue(stop.what.startsWith("NullPointerException in CrashesTest."), stop.what)
        assertTrue("token" !in stop.what)
        assertTrue("token=secret" in File(folder, "crashes.log").readText(), "the full trace is in the log, for whoever helps")
    }

    @Test
    fun theLogKeepsTheLatestWholeEntries() {
        repeat(200) { Crashes.record(RuntimeException("x".repeat(1_000)), folder, now = it.toLong()) }
        val text = File(folder, "crashes.log").readText()
        assertTrue(text.length <= 64 * 1024 + 4_000, "${text.length}")
        assertTrue(text.startsWith("=== "))
        assertEquals(199, Crashes.last(folder)!!.at)
    }
}
