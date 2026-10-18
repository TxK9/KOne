package cc.txwd.kone.app

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageTest {
    private val file = File(Files.createTempDirectory("usage").toFile(), "usage.properties")
    private var day = LocalDate.parse("2026-10-30")
    private fun usage() = Usage(file) { day }

    @Test
    fun countsTodayAndThisMonthAndKeepsThem() {
        val usage = usage()
        usage.add(1_000, 100, now = 0)
        usage.add(2_000, 200, now = 1_000)
        day = LocalDate.parse("2026-10-31")
        usage.add(500, 50, now = 2_000)
        assertEquals(Used(500, 50), usage.today())
        assertEquals(Used(3_500, 350), usage.month())

        usage.save()
        val again = usage()
        assertEquals(Used(500, 50), again.today())
        assertEquals(Used(3_500, 350), again.month())

        // A new month starts from nothing.
        day = LocalDate.parse("2026-11-01")
        assertEquals(Used(0, 0), again.today())
        assertEquals(Used(0, 0), again.month())
    }

    @Test
    fun writesOnceAMinuteAtMostAndLetsOldMonthsGo() {
        val usage = usage()
        usage.add(10, 1, now = 60_000)
        val first = file.readText()
        usage.add(10, 1, now = 90_000)
        assertEquals(first, file.readText(), "not written again within the minute")
        usage.add(10, 1, now = 120_000)
        assertTrue(file.readText().contains("2026-10-30=30,3"))

        day = LocalDate.parse("2026-12-02")
        usage.add(1, 1, now = 180_000)
        assertFalse(file.readText().contains("2026-10-30"), "October goes once it is two months back")
    }
}
