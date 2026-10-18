package io.nekohasekai.sfa.compose.theme

import io.nekohasekai.sfa.compose.theme.ink.SkyClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId
import java.util.TimeZone

class SkyClockTest {
    private val newYork = TimeZone.getTimeZone("America/New_York")
    private fun at(text: String, zone: String = "America/New_York") =
        ZonedDateTime.of(java.time.LocalDateTime.parse(text), ZoneId.of(zone)).toInstant().toEpochMilli()

    @Test
    fun earlyAfternoonSunLeansWest() {
        val sun = SkyClock.sun(at("2026-10-14T13:28"), newYork)
        assertTrue(sun.up)
        assertTrue("past the top, on the west side", sun.across in 0.53f..0.62f)
    }

    @Test
    fun sunRisesAndSetsWithTheDay() {
        // New York, 14 October 2026: sunrise 7:11, sunset 18:20.
        assertFalse(SkyClock.sun(at("2026-10-14T06:45"), newYork).up)
        assertTrue(SkyClock.sun(at("2026-10-14T07:30"), newYork).up)
        assertTrue(SkyClock.sun(at("2026-10-14T18:05"), newYork).up)
        assertFalse(SkyClock.sun(at("2026-10-14T18:40"), newYork).up)
        assertTrue(SkyClock.sun(at("2026-10-14T07:30"), newYork).across < 0.1f)
    }

    @Test
    fun noonInShanghaiIsNearTheTop() {
        val sun = SkyClock.sun(at("2026-10-14T12:00", "Asia/Shanghai"), TimeZone.getTimeZone("Asia/Shanghai"))
        assertTrue(sun.across in 0.45f..0.6f)
        assertTrue(sun.height > 0.6f)
    }

    @Test
    fun moonPhaseFollowsTheMonth() {
        // A young crescent on 14 October 2026, full on 26 October 2026 (04:12 UTC).
        assertEquals(0.12, SkyClock.moonPhase(at("2026-10-14T13:28")), 0.03)
        assertEquals(0.5, SkyClock.moonPhase(at("2026-10-26T04:12", "UTC")), 0.02)
        val moon = SkyClock.moon(at("2026-10-14T13:28"), newYork)
        assertTrue("the crescent has risen in the east", moon.body.up && moon.body.across < 0.5f)
    }
}
