package cc.txwd.kone.city

import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/*
 * Sunlight and weather over a city painting, as Codex's lighting-v13.js and weather-v13.js work
 * them out: how strong and warm the sun is on the facades, which parts of the day painting take
 * its wash, and where the rain, snow and ripples fall.
 */

/** Smoothstep from [from] to [to]. */
fun smooth(from: Double, to: Double, value: Double) = smooth((value - from) / (to - from))

/** The sun on a scene: [strength] 0 to 1 by its height and the daylight, [warm] as it nears the horizon, [inFrame] when it shows in the painting. */
class Sunlight(val strength: Double, val warm: Double, val inFrame: Boolean) {
    companion object {
        fun of(sky: SkyState): Sunlight {
            val altitude = sky.sunAltitude
            val above = altitude > 0
            val strength = if (!above) 0.0 else sky.daylight * smooth(0.0, 4.0, altitude) * (.44 + .56 * sin(altitude.coerceIn(0.0, 90.0) * PI / 180))
            return Sunlight(strength, if (above) 1 - smooth(8.0, 48.0, altitude) else 0.0, above && sky.body?.moon == false)
        }
    }
}

/** How strongly the moon is reflected on the water: only a moon in sight, the sun well down, by how much of it is lit. */
fun moonReflection(sky: SkyState, motion: SceneMotion): Double {
    val moon = sky.body ?: return 0.0
    if (!moon.moon || motion.water.isEmpty() || sky.sunAltitude >= -6) return 0.0
    return (1 - sky.daylight) * unit(sky.moonLit) * smooth(0.0, 14.0, sky.moonAltitude) * .075
}

/**
 * Where the sun's wash may fall on the day painting, as unpremultiplied white RGBA with the
 * strength in alpha: midtones with texture (facades) in the band between the skyline and the
 * water, never the paper-white sky or the dark creases. [rgba] is the day painting laid on the
 * whole wallpaper at [width] by [height] (Codex samples at 480 wide).
 */
fun facadeMask(rgba: ByteArray, width: Int, height: Int): ByteArray {
    fun luminance(x: Int, y: Int): Double {
        val i = (y * width + x.coerceIn(0, width - 1)) * 4
        return ((rgba[i].toInt() and 0xFF) * .2126 + (rgba[i + 1].toInt() and 0xFF) * .7152 + (rgba[i + 2].toInt() and 0xFF) * .0722) / 255
    }
    val out = ByteArray(width * height * 4)
    for (y in 0 until height) {
        val ny = (y + .5) / height
        val band = smooth(.282, .324, ny) * (1 - smooth(.61, .67, ny))
        if (band == 0.0) continue
        for (x in 0 until width) {
            val lightness = luminance(x, y)
            val midtone = smooth(.28, .54, lightness) * (1 - smooth(.90, .985, lightness))
            val texture = smooth(.006, .055, kotlin.math.abs(luminance(x + 2, y) - luminance(x - 2, y)))
            val i = (y * width + x) * 4
            out[i] = 255.toByte()
            out[i + 1] = 255.toByte()
            out[i + 2] = 255.toByte()
            out[i + 3] = jsRound(255 * band * midtone * texture).toByte()
        }
    }
    return out
}

class Drop(val x: Double, val y: Double, val phase: Double, val depth: Double, val speed: Double, val drift: Double, val alpha: Double)

class Flake(val x: Double, val y: Double, val phase: Double, val depth: Double, val speed: Double, val drift: Double, val period: Double)

class Ring(val x: Double, val y: Double, val phase: Double, val period: Double, val scale: Double)

/** A scene's rain, snow and the places rain rings the water, the same each time for the scene. */
class Precipitation(scene: CityScene, motion: SceneMotion) {
    val drops: List<Drop>
    val flakes: List<Flake>
    val rings: List<Ring>

    init {
        val random = Seeds.Random(Seeds.of(scene.id + ":weather-v11"))
        drops = List(210) { i ->
            val x = random.next()
            val y = random.next()
            val phase = random.next() * TAU
            val speed = .38 + random.next() * .38
            val drift = .015 + random.next() * .025
            Drop(x, y, phase, .15 + .85 * (i % 3) / 2, speed, drift, .4 + random.next() * .6)
        }
        flakes = List(125) {
            val x = random.next()
            val y = random.next()
            val phase = random.next() * TAU
            val depth = .2 + random.next() * .8
            val speed = .012 + random.next() * .025
            val drift = .007 + random.next() * .015
            Flake(x, y, phase, depth, speed, drift, 3 + random.next() * 5)
        }
        val water = motion.water
        val holes = motion.waterExclusions
        val rings = mutableListOf<Ring>()
        var attempts = 0
        while (water.isNotEmpty() && rings.size < 64 && attempts < 6400) {
            attempts++
            val region = floor(random.next() * water.size).toInt()
            val box = water[region]
            val x = box.left + random.next() * (box.right - box.left)
            val y = box.top + random.next() * (box.bottom - box.top)
            val point = Point(x, y)
            if (point in water[region] && holes.none { point in it }) {
                val phase = random.next()
                val period = .7 + random.next() * .85
                rings += Ring(x, y, phase, period, .6 + random.next() * .8)
            }
        }
        this.rings = rings
    }

    companion object {
        private val cache = HashMap<String, Precipitation>()

        fun of(scene: CityScene): Precipitation = synchronized(cache) { cache.getOrPut(scene.id) { Precipitation(scene, SceneMotion.of(scene)) } }
    }
}

/** How much of the sun or moon shows through the weather (the preview's sky layer opacity). */
fun bodyShows(weather: WeatherProfile?, night: Boolean, fogVisibility: Double): Double {
    val p = weather ?: return 1.0
    val transmission = maxOf(.02, 1 - p.cloudCover * .88 - p.rain * .40 - p.fog * .25 - p.snow * .2)
    return maxOf(if (night) .62 else .48, Math.pow(transmission, if (night) .35 else .5)) * fogVisibility
}
