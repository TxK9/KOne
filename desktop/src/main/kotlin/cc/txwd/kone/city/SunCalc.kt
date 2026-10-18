package cc.txwd.kone.city

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Where the sun and moon stand over a place at an instant, and how much of the moon is lit:
 * SunCalc by Volodymyr Agafonkin (BSD 2-Clause, see resources/licenses/suncalc.txt), as Codex's
 * country preview uses it. The sun follows SunCalc 2.1.1; the moon follows SunCalc's compact
 * series (about half a degree), plenty for a painted sky. Angles are degrees, azimuth clockwise
 * from north.
 */
object SunCalc {
    data class Position(val azimuth: Double, val altitude: Double)

    /** [fraction] lit, from 0 (new) to 1 (full). */
    data class Illumination(val fraction: Double, val phase: Double, val angle: Double)

    private const val RAD = PI / 180
    private const val DAY_MS = 86_400_000.0
    private const val J1970 = 2440588.0
    private const val J2000 = 2451545.0
    private const val EARTH_RADIUS = 6378.14
    private const val OBLIQUITY = RAD * 23.4397

    fun sun(epochMillis: Long, lat: Double, lon: Double): Position {
        val lw = RAD * -lon
        val phi = RAD * lat
        val d = days(epochMillis)
        val c = sunCoords(terrestrial(d))
        val h = sidereal(d, lw) - c.ra
        val altitude = altitude(h, phi, c.dec)
        return Position(azimuth(h, phi, c.dec), (altitude + refraction(altitude)) / RAD)
    }

    fun moon(epochMillis: Long, lat: Double, lon: Double): Position {
        val lw = RAD * -lon
        val phi = RAD * lat
        val d = days(epochMillis)
        val c = moonCoords(d)
        val h = sidereal(d, lw) - c.ra
        val geocentric = altitude(h, phi, c.dec)
        val topocentric = geocentric - asin(EARTH_RADIUS / c.dist * cos(geocentric))
        return Position(azimuth(h, phi, c.dec), (topocentric + refraction(topocentric)) / RAD)
    }

    fun moonIllumination(epochMillis: Long): Illumination {
        val d = days(epochMillis)
        val s = sunCoords(terrestrial(d))
        val m = moonCoords(d)
        val sunDistance = 149_598_000.0
        val phi = acos(sin(s.dec) * sin(m.dec) + cos(s.dec) * cos(m.dec) * cos(s.ra - m.ra))
        val inc = atan2(sunDistance * sin(phi), m.dist - sunDistance * cos(phi))
        val angle = atan2(cos(s.dec) * sin(s.ra - m.ra), sin(s.dec) * cos(m.dec) - cos(s.dec) * sin(m.dec) * cos(s.ra - m.ra))
        return Illumination((1 + cos(inc)) / 2, 0.5 + 0.5 * inc * (if (angle < 0) -1 else 1) / PI, angle / RAD)
    }

    private class Coords(val ra: Double, val dec: Double, val dist: Double = 0.0)

    private fun days(epochMillis: Long) = epochMillis / DAY_MS - 0.5 + J1970 - J2000

    /** Terrestrial time: ΔT, by the polynomial SunCalc 2.1.1 uses for 2005–2050 (and after). */
    private fun terrestrial(d: Double): Double {
        val year = 2000 + d / 365.2425
        val deltaT = if (year < 2050) {
            val t = year - 2000
            62.92 + t * (0.32217 + t * 0.005589)
        } else {
            val t = (year - 1820) / 100
            -20 + 32 * t * t - 0.5628 * (2150 - year)
        }
        return d + deltaT / 86_400
    }

    private fun azimuth(h: Double, phi: Double, dec: Double) = (atan2(sin(h), cos(h) * sin(phi) - tan(dec) * cos(phi)) / RAD + 540) % 360

    private fun altitude(h: Double, phi: Double, dec: Double) = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))

    private fun sidereal(d: Double, lw: Double) = RAD * (280.46061837 + 360.98564736629 * d) - lw

    private fun refraction(h: Double): Double {
        val above = if (h < 0) 0.0 else h
        return 0.0002967 / tan(above + 0.00312536 / (above + 0.08901179))
    }

    private fun sunCoords(d: Double): Coords {
        val t = d / 36_525
        val l0 = RAD * (280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = RAD * (357.52911 + t * (35999.05029 - t * 0.0001537))
        val sinM = sin(m)
        val cosM = cos(m)
        val c = RAD * ((1.914602 - t * (0.004817 + t * 0.000014)) * sinM + (0.019993 - 0.000101 * t) * 2 * sinM * cosM + 0.000289 * sinM * (3 - 4 * sinM * sinM))
        val om = RAD * (125.04 - 1934.136 * t)
        val l = l0 + c - RAD * (0.00569 + 0.00478 * sin(om))
        val e = RAD * (23.439291 - t * (0.0130042 + t * (0.00000016 - t * 0.000000504))) + RAD * 0.00256 * cos(om)
        return Coords(atan2(cos(e) * sin(l), cos(l)), asin(sin(e) * sin(l)))
    }

    private fun moonCoords(d: Double): Coords {
        val l = RAD * (218.316 + 13.176396 * d)
        val m = RAD * (134.963 + 13.064993 * d)
        val f = RAD * (93.272 + 13.229350 * d)
        val longitude = l + RAD * 6.289 * sin(m)
        val latitude = RAD * 5.128 * sin(f)
        return Coords(
            ra = atan2(sin(longitude) * cos(OBLIQUITY) - tan(latitude) * sin(OBLIQUITY), cos(longitude)),
            dec = asin(sin(latitude) * cos(OBLIQUITY) + cos(latitude) * sin(OBLIQUITY) * sin(longitude)),
            dist = 385_001.0 - 20_905 * cos(m),
        )
    }
}
