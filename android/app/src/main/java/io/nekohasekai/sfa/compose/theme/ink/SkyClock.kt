package io.nekohasekai.sfa.compose.theme.ink

import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Where the sun and the moon stand over the ink paintings, from the clock alone: no location is
 * asked for. The time zone gives a city near enough (New York for America/New_York), and simple
 * almanac formulas (good to a few minutes) give the rest.
 *
 * [across] runs from 0 where the body rises on the left (east, as when looking south) to 1 where
 * it sets on the right (west); [height] is 0 at the horizon and 1 at the top of its arc.
 */
internal object SkyClock {
    class Body(val up: Boolean, val across: Float, val height: Float)

    /** [phase] 0 new, 0.25 first quarter, 0.5 full, 0.75 last quarter. */
    class Moon(val body: Body, val phase: Float)

    private class Place(val latitude: Double, val longitude: Double)

    // Zones the App is likely to meet. Any other zone uses its own offset for longitude.
    private val places = mapOf(
        "America/New_York" to Place(40.71, -74.01), "America/Toronto" to Place(43.65, -79.38),
        "America/Chicago" to Place(41.88, -87.63), "America/Denver" to Place(39.74, -104.99),
        "America/Phoenix" to Place(33.45, -112.07), "America/Los_Angeles" to Place(34.05, -118.24),
        "America/Vancouver" to Place(49.28, -123.12), "Pacific/Honolulu" to Place(21.31, -157.86),
        "Asia/Shanghai" to Place(31.23, 121.47), "Asia/Chongqing" to Place(29.56, 106.55),
        "Asia/Urumqi" to Place(43.83, 87.62), "Asia/Hong_Kong" to Place(22.32, 114.17),
        "Asia/Macau" to Place(22.20, 113.54), "Asia/Taipei" to Place(25.03, 121.57),
        "Asia/Tokyo" to Place(35.68, 139.69), "Asia/Seoul" to Place(37.57, 126.98),
        "Asia/Singapore" to Place(1.35, 103.82), "Europe/London" to Place(51.51, -0.13),
        "Europe/Paris" to Place(48.86, 2.35), "Europe/Berlin" to Place(52.52, 13.40),
        "Australia/Sydney" to Place(-33.87, 151.21),
    )

    private fun place(zone: TimeZone): Place = places[zone.id]
        ?: Place(35.0, zone.rawOffset / 3_600_000.0 * 15.0)

    private fun rad(degrees: Double) = degrees * PI / 180.0
    private fun deg(radians: Double) = radians * 180.0 / PI

    /** The sun at [millis], seen from the city of [zone]. */
    fun sun(millis: Long, zone: TimeZone = TimeZone.getDefault()): Body = arc(sunHourAngle(millis, zone), sunrise(millis, zone), noonHeight(millis, zone))

    /**
     * The moon at [millis]: its phase, and its place, which runs behind the sun's by the phase
     * (new rises with the sun, full as it sets). Its own tilt over the month is left out.
     */
    fun moon(millis: Long, zone: TimeZone = TimeZone.getDefault()): Moon {
        val phase = moonPhase(millis)
        val hour = normalize(sunHourAngle(millis, zone) - 360.0 * phase)
        return Moon(arc(hour, 90.0, 0.75), phase.toFloat())
    }

    fun moonPhase(millis: Long): Double {
        val days = julianDay(millis) - 2451550.1 // a new moon, 6 January 2000
        val cycles = days / 29.530588853
        return cycles - floor(cycles)
    }

    private fun arc(hourAngle: Double, rise: Double, top: Double): Body {
        val up = abs(hourAngle) < rise
        val across = ((hourAngle + rise) / (2 * rise)).coerceIn(0.0, 1.0)
        return Body(up, across.toFloat(), (sin(across * PI) * top).toFloat())
    }

    private fun julianDay(millis: Long) = millis / 86_400_000.0 + 2440587.5

    private class Sun(val declination: Double, val equationMinutes: Double)

    private fun sunAt(millis: Long): Sun {
        val n = julianDay(millis) - 2451545.0
        val meanLongitude = (280.460 + 0.9856474 * n).mod(360.0)
        val anomaly = rad((357.528 + 0.9856003 * n).mod(360.0))
        val longitude = rad(meanLongitude + 1.915 * sin(anomaly) + 0.020 * sin(2 * anomaly))
        val obliquity = rad(23.439 - 0.0000004 * n)
        val declination = asin(sin(obliquity) * sin(longitude))
        val ascension = deg(atan2(cos(obliquity) * sin(longitude), cos(longitude))).mod(360.0)
        return Sun(declination, 4.0 * normalize(meanLongitude - ascension))
    }

    /** Degrees west of the meridian: negative in the morning, positive in the afternoon. */
    private fun sunHourAngle(millis: Long, zone: TimeZone): Double {
        val sun = sunAt(millis)
        val utcHours = (millis / 3_600_000.0).mod(24.0)
        val solarHours = utcHours + place(zone).longitude / 15.0 + sun.equationMinutes / 60.0
        return normalize(15.0 * (solarHours - 12.0))
    }

    /** The hour angle at which the sun rises and sets (90° at the equinox). */
    private fun sunrise(millis: Long, zone: TimeZone): Double {
        val latitude = rad(place(zone).latitude)
        val declination = sunAt(millis).declination
        val cosine = (sin(rad(-0.833)) - sin(latitude) * sin(declination)) / (cos(latitude) * cos(declination))
        return deg(acos(cosine.coerceIn(-1.0, 1.0)))
    }

    /** How high the noon sun climbs, as a share of the arc drawn: lower in winter. */
    private fun noonHeight(millis: Long, zone: TimeZone): Double {
        val altitude = 90.0 - abs(place(zone).latitude - deg(sunAt(millis).declination))
        return (altitude / 75.0).coerceIn(0.45, 1.0)
    }

    private fun normalize(degrees: Double) = (degrees + 180.0).mod(360.0) - 180.0
}
