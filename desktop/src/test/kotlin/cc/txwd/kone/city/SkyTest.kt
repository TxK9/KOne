package cc.txwd.kone.city

import java.time.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The port against SunCalc 2.1.1 itself (Codex's vendored copy, run in Node for these values). */
class SkyTest {
    private class Case(val time: String, val lat: Double, val lon: Double, val sunAz: Double, val sunAlt: Double, val moonAz: Double, val moonAlt: Double, val lit: Double)

    private val cases = listOf(
        Case("2026-06-21T16:57:00Z", 40.7128, -74.006, 179.298, 72.729, 92.858, 0.943, 0.4798),
        Case("2026-10-17T03:00:00Z", 35.6762, 139.6503, 191.496, 44.442, 124.764, 0.13, 0.3568),
        Case("2026-12-21T10:00:00Z", -33.8688, 151.2093, 232.503, -9.807, 18.493, 29.687, 0.8971),
        Case("2026-10-26T04:12:00Z", 22.3193, 114.1694, 181.998, 55.244, 4.249, -50.999, 0.9984),
        Case("2026-03-10T21:30:00Z", 51.5074, -0.1278, 310.781, -31.312, 84.233, -41.181, 0.5492),
    )

    private fun angle(a: Double, b: Double) = abs(((a - b + 540) % 360) - 180)

    @Test
    fun theSunStandsWhereSunCalcPutsIt() {
        for (c in cases) {
            val sun = SunCalc.sun(Instant.parse(c.time).toEpochMilli(), c.lat, c.lon)
            assertTrue(angle(sun.azimuth, c.sunAz) < 0.05, "${c.time} sun azimuth ${sun.azimuth} vs ${c.sunAz}")
            assertTrue(abs(sun.altitude - c.sunAlt) < 0.05, "${c.time} sun altitude ${sun.altitude} vs ${c.sunAlt}")
        }
    }

    @Test
    fun theMoonIsWithinAPaintersDegree() {
        for (c in cases) {
            val millis = Instant.parse(c.time).toEpochMilli()
            val moon = SunCalc.moon(millis, c.lat, c.lon)
            // Its bearing only matters while it is up to be drawn; far below, it swings with every hundredth of a degree.
            if (c.moonAlt > 0) assertTrue(angle(moon.azimuth, c.moonAz) < 1.5, "${c.time} moon azimuth ${moon.azimuth} vs ${c.moonAz}")
            assertTrue(abs(moon.altitude - c.moonAlt) < 1.5, "${c.time} moon altitude ${moon.altitude} vs ${c.moonAlt}")
            assertTrue(abs(SunCalc.moonIllumination(millis).fraction - c.lit) < 0.02, "${c.time} lit ${SunCalc.moonIllumination(millis).fraction} vs ${c.lit}")
        }
    }

    @Test
    fun dayFadesIntoNightThroughTwilight() {
        assertEquals(0.0, Sky.smooth(-6.0, 12.0, -10.0))
        assertEquals(1.0, Sky.smooth(-6.0, 12.0, 20.0))
        assertEquals(0.5, Sky.smooth(-6.0, 12.0, 3.0), 1e-9)
    }
}
