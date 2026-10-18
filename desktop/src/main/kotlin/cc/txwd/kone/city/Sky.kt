package cc.txwd.kone.city

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** The sun or the moon where a scene's camera sees it, in wallpaper fractions; [radius] a fraction of the width. */
data class Body(val moon: Boolean, val x: Double, val y: Double, val radius: Double, val illumination: Double = 1.0, val tilt: Double = 0.0)

/**
 * A scene's sky at an instant: how far into day (0 night, 1 day; twilight between), the body to
 * show if any, where the sun and moon stand in degrees and how much of the moon is lit.
 */
data class SkyState(
    val daylight: Double,
    val body: Body?,
    val sunAltitude: Double,
    val moonLit: Double = 0.0,
    val sunAzimuth: Double = 180.0,
    val moonAltitude: Double = -90.0,
)

/**
 * The sky as Codex's sky layer works it out (country-preview/sky-v5.js): the sun and moon over
 * the city projected through the scene's camera, shown only where the painting leaves open sky.
 * Day blends into night between the sun 12° up and 6° down.
 */
object Sky {
    private const val SUN_RADIUS = 0.030
    private const val MOON_RADIUS = 0.032

    fun at(scene: CityScene, epochMillis: Long): SkyState {
        val sun = SunCalc.sun(epochMillis, scene.lat, scene.lon)
        val moon = SunCalc.moon(epochMillis, scene.lat, scene.lon)
        val daylight = smooth(-6.0, 12.0, sun.altitude)
        val lit = SunCalc.moonIllumination(epochMillis).fraction
        val sunSpot = project(sun, scene)
        val body = when {
            sun.altitude > 0 && sunSpot != null && safe(sunSpot, scene, SUN_RADIUS) -> Body(false, sunSpot.first, sunSpot.second, SUN_RADIUS)
            sun.altitude < -6 && moon.altitude > 0 -> project(moon, scene)?.takeIf { safe(it, scene, MOON_RADIUS) }?.let { spot ->
                Body(true, spot.first, spot.second, MOON_RADIUS, lit, lightAngle(sun, moon, scene))
            }
            else -> null
        }
        return SkyState(daylight, body, sun.altitude, lit, sun.azimuth, moon.altitude)
    }

    /** Where [position] falls on the wallpaper through the scene's camera, or null behind it. */
    fun project(position: SunCalc.Position, scene: CityScene): Pair<Double, Double>? {
        val delta = rad(wrap(position.azimuth - scene.bearing))
        val altitude = rad(position.altitude)
        if (cos(altitude) * cos(delta) <= 0) return null
        val x = 0.5 + tan(delta) / (2 * tan(rad(scene.fov / 2)))
        val y = scene.horizonY - scene.verticalScale * tan(altitude) / cos(delta)
        return if (x in 0.0..1.0 && y in 0.0..1.0) x to y else null
    }

    /** Whether a disc at [spot] lies wholly in the open sky, clear of every part kept out of it. */
    private fun safe(spot: Pair<Double, Double>, scene: CityScene, radius: Double): Boolean {
        val (x, y) = spot
        val radiusY = radius * scene.crop.aspect
        val sky = scene.skySafe
        if (x - radius < sky.left || x + radius > sky.right || y - radiusY < sky.top || y + radiusY > sky.bottom) return false
        return scene.skyExclusions.none { x + radius > it.left && x - radius < it.right && y + radiusY > it.top && y - radiusY < it.bottom }
    }

    /** Which way the moon's lit side faces on the wallpaper: toward the sun along the sky. */
    private fun lightAngle(sun: SunCalc.Position, moon: SunCalc.Position, scene: CityScene): Double {
        val m = vector(moon)
        val s = vector(sun)
        val bearing = rad(scene.bearing)
        val along = dot(s, m)
        val tangent = DoubleArray(3) { s[it] - along * m[it] }
        val right = doubleArrayOf(cos(bearing), -sin(bearing), 0.0)
        val forward = doubleArrayOf(sin(bearing), cos(bearing), 0.0)
        val mf = dot(m, forward)
        val mr = dot(m, right)
        val tf = dot(tangent, forward)
        val dx = (dot(tangent, right) * mf - mr * tf) / (2 * tan(rad(scene.fov / 2)) * mf * mf)
        val dy = -scene.verticalScale * (tangent[2] * mf - m[2] * tf) / (mf * mf)
        return atan2(dy / scene.crop.aspect, dx)
    }

    private fun vector(p: SunCalc.Position): DoubleArray {
        val a = rad(p.azimuth)
        val h = rad(p.altitude)
        return doubleArrayOf(cos(h) * sin(a), cos(h) * cos(a), sin(h))
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a.indices.sumOf { a[it] * b[it] }

    fun smooth(from: Double, to: Double, value: Double): Double {
        val t = ((value - from) / (to - from)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    private fun rad(degrees: Double) = degrees * PI / 180
    private fun wrap(degrees: Double) = ((degrees + 180) % 360 + 360) % 360 - 180
}
