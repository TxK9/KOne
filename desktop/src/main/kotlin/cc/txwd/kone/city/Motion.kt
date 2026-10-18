package cc.txwd.kone.city

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/*
 * What moves over a city painting, as Codex's motion layer works it out (country-preview/
 * motion-v13.js and environment-v10.js): the wind, the water's ripple, the clouds, the mist and
 * the lit windows. Coordinates are fractions of the whole wallpaper, as the scenes give them.
 * The seeds and hashes are Codex's, bit for bit, so each scene moves as it does in the preview.
 */

const val TAU = 2 * PI

class Point(val x: Double, val y: Double)

/** A closed outline on the wallpaper. */
class Polygon(val points: List<Point>) {
    val left = points.minOf { it.x }
    val right = points.maxOf { it.x }
    val top = points.minOf { it.y }
    val bottom = points.maxOf { it.y }

    operator fun contains(p: Point): Boolean {
        var hit = false
        var j = points.size - 1
        for (i in points.indices) {
            val a = points[i]
            val b = points[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) hit = !hit
            j = i
        }
        return hit
    }

    companion object {
        fun box(left: Double, top: Double, right: Double, bottom: Double) =
            Polygon(listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom)))
    }
}

/** Codex's seeds and hashes, with JavaScript's 32-bit integer arithmetic. */
object Seeds {
    /** FNV-1a over the UTF-16 units. */
    fun of(text: String): Int {
        var seed = 2166136261L.toInt()
        for (c in text) seed = (seed xor c.code) * 16777619
        return seed
    }

    fun unitHash(seed: Int, cell: Int): Double {
        var n = (seed xor (cell * 374761393)) * 668265263
        n = (n xor (n ushr 13)) * 1274126177
        return (n xor (n ushr 16)).toUInt().toDouble() / 4294967295.0
    }

    /** A linear congruential sequence in [0, 1). */
    class Random(seed: Int) {
        private var n = seed

        fun next(): Double {
            n = n * 1664525 + 1013904223
            return n.toUInt().toDouble() / 4294967296.0
        }
    }
}

/** The air the moving layers share at an instant: Codex's own wind, and the real weather when there is some (see [CityAir]). */
data class Environment(
    val wind: Double,
    /** Wind integrated over time: how far the air has carried things, which never jumps or runs back. */
    val advection: Double,
    val gust: Double,
    val cloudCover: Double,
    val waveGroup: Double,
    val shimmer: Double,
    val rain: Double = 0.0,
    val snow: Double = 0.0,
    val fog: Double = 0.0,
    val fogVisibility: Double = 1.0,
    val directVisibility: Double = 1.0,
    /** How much sunlight comes through the cloud, rain and fog; null without real weather. */
    val transmission: Double? = null,
    val wetness: Double = 0.0,
    val temperature: Double? = null,
    /** How much is out and about in the city: less in rain, snow and fog. */
    val activity: Double = 1.0,
    /** The real weather this comes from, if any. */
    val weather: WeatherProfile? = null,
)

/** Codex's art-directed wind for a scene (environment-v10.js): steady, gusting a little, not the real weather. */
object Wind {
    private class Rates(val a: Double, val b: Double, val c: Double, val w1: Double, val w2: Double, val coverRate: Double, val groupRate: Double)

    private val rates = HashMap<String, Rates>()

    private fun rates(id: String): Rates = synchronized(rates) {
        rates.getOrPut(id) {
            val random = Seeds.Random(Seeds.of(id))
            Rates(
                a = random.next() * TAU, b = random.next() * TAU, c = random.next() * TAU,
                w1 = TAU / (31 + random.next() * 24), w2 = TAU / (9 + random.next() * 8),
                coverRate = TAU / (47 + random.next() * 32), groupRate = TAU / (12 + random.next() * 9),
            )
        }
    }

    fun at(id: String, seconds: Double): Environment {
        val t = seconds.coerceAtLeast(0.0)
        val p = rates(id)
        val wind = 1 + .22 * sin(t * p.w1 + p.a) + .10 * sin(t * p.w2 + p.b)
        val advection = t - .22 / p.w1 * (cos(t * p.w1 + p.a) - cos(p.a)) - .10 / p.w2 * (cos(t * p.w2 + p.b) - cos(p.b))
        return Environment(
            wind = wind.coerceIn(.45, 1.65),
            advection = advection,
            gust = unit((wind - .68) / .64),
            cloudCover = unit(.48 + .20 * sin(t * p.coverRate + p.c) + .10 * sin(t * p.w1 * .47 + p.a)),
            waveGroup = unit(.5 + .32 * sin(t * p.groupRate + p.b) + .15 * sin(t * p.w1 + p.c)),
            shimmer = unit(.5 + .32 * sin(t * 1.17 + p.a) + .15 * sin(t * .43 + p.b)),
        )
    }
}

fun unit(value: Double) = value.coerceIn(0.0, 1.0)

fun smooth(value: Double): Double {
    val t = unit(value)
    return t * t * (3 - 2 * t)
}

fun wrap(value: Double, span: Double) = ((value % span) + span) % span

/** A bank of cloud: how far off, how big, where it starts and how fast it goes. */
class CloudBank(val depth: Double, val width: Double, val height: Double, val offset: Double, val elevation: Double, val phase: Double, val speed: Double)

/** A glint on the water, in fractions of its water's bounds. */
class Glint(val x: Double, val y: Double, val length: Double, val phase: Double, val speed: Double, val period: Double)

/** A lit window found in the night painting: where, its colour, and how it changes over minutes. */
class WindowLight(
    val x: Double, val y: Double, val r: Int, val g: Int, val b: Int, val region: Int,
    val seed: Int, val period: Double, val transition: Double, val offset: Double, val variable: Boolean,
)

/** One row of water to be redrawn shifted: its place on the wallpaper and the stretches of it that are only water. */
class WaterRow(val top: Double, val bottom: Double, val spans: List<Pair<Double, Double>>) {
    val middle get() = (top + bottom) / 2
}

/** Everything about a scene that moves, worked out once. */
class SceneMotion(scene: CityScene) {
    val seed = Seeds.of(scene.id)
    val water: List<Polygon>
    val waterExclusions: List<Polygon>
    /** How lively the water is (sea 1, a pool barely) and how quickly it goes. */
    val waterEnergy: Double
    val waterPace: Double
    val cloudBand: Pair<Double, Double>
    val cloudExclusions: List<Polygon>
    val lightRegions: List<Polygon>
    val mist: List<Polygon>
    val cloudBanks: List<CloudBank>
    /** Which of the six cloud pigments each bank wears. */
    val pigments: List<Int>
    val glints: List<List<Glint>>

    init {
        val motion = scene.motion
        val marine = motion["marine"] as? JsonObject
        water = polygons(motion["waterPolygons"])
        waterExclusions = polygons(motion["waterExclusions"]) + polygons(marine?.get("exclusionPolygons"))
        val type = ((marine?.get("type") as? JsonPrimitive)?.contentOrNull ?: scene.waterType ?: "pool").lowercase()
        val nature = NATURES[type] ?: NATURES.getValue("pool")
        waterEnergy = nature.first
        waterPace = nature.second
        val bands = listOfNotNull(scene.cloudBand, band(motion["cloudSkyBand"])).filter { it.second > it.first }
        cloudBand = if (bands.isEmpty()) .13 to .267 else maxOf(.13, bands.maxOf { it.first }) to minOf(.267, bands.minOf { it.second })
        cloudExclusions = scene.skyExclusions.map { Polygon.box(it.left, it.top, it.right, it.bottom) } + polygons(motion["skyExclusions"])
        lightRegions = polygons(motion["lightRegions"]).ifEmpty {
            List(8) { i ->
                val x = .04 + (i % 4) * .23
                val y = .30 + (i / 4) * .135
                Polygon.box(x, y, x + .22, y + .125)
            }
        }
        mist = polygons(motion["mistPolygons"])
        val clouds = Seeds.Random(seed xor 0x71ac49)
        cloudBanks = List(4) { index ->
            CloudBank(
                depth = .22 + index * .21, width = .39 + clouds.next() * .35, height = .47 + clouds.next() * .26,
                offset = clouds.next() * 1.9 - .7, elevation = .02 + clouds.next() * .25, phase = clouds.next() * TAU,
                speed = .63 + clouds.next() * .24 + index * .17,
            )
        }
        pigments = List(4) { index -> PIGMENT_SEEDS[((seed.toUInt() % PIGMENT_SEEDS.size.toUInt()).toInt() + index) % PIGMENT_SEEDS.size] }
        val sparkle = Seeds.Random(seed xor 937)
        glints = water.map {
            List(48) {
                Glint(
                    x = sparkle.next(), y = sparkle.next(), length = .012 + sparkle.next() * .052,
                    phase = sparkle.next() * TAU, speed = .7 + sparkle.next() * .8, period = 3.8 + sparkle.next() * 5.2,
                )
            }
        }
    }

    /**
     * The water cut into rows [rowHeight] high (a fraction of the wallpaper's height), each with
     * the stretches that are water all the way from its top to its bottom and a source pixel in
     * from every bank, so a row shifted sideways never carries the shore out onto the water.
     * [pixelX] and [pixelY] are one source pixel, as fractions.
     */
    fun waterRows(index: Int, rowHeight: Double, pixelX: Double, pixelY: Double, from: Double = 0.0, to: Double = 1.0): List<WaterRow> {
        val polygon = water[index]
        val critical = (listOf(polygon) + waterExclusions).flatMap { shape -> shape.points.map { it.y } }.distinct().sorted()
        val rows = mutableListOf<WaterRow>()
        var top = maxOf(polygon.top, from)
        val end = minOf(polygon.bottom, to)
        while (top < end) {
            val bottom = minOf(top + rowHeight, end)
            val low = top - pixelY
            val high = bottom + pixelY
            val levels = mutableListOf(low, (top + bottom) / 2, high)
            val epsilon = minOf(1e-7, (high - low) * 1e-4)
            for (y in critical) if (y in low..high) levels += listOf(maxOf(low, y - epsilon), y, minOf(high, y + epsilon))
            var spans: List<Pair<Double, Double>>? = null
            for (level in levels) {
                var allowed = horizontalSpans(polygon, level)
                for (exclusion in waterExclusions) allowed = subtract(allowed, horizontalSpans(exclusion, level))
                spans = if (spans == null) allowed else intersect(spans, allowed)
                if (spans.isEmpty()) break
            }
            var kept = subtract(spans.orEmpty(), sweptSpans(polygon, low, high))
            for (exclusion in waterExclusions) kept = subtract(kept, sweptSpans(exclusion, low, high))
            kept = kept.map { (l, r) -> l + pixelX to r - pixelX }.filter { (l, r) -> r - l > 1e-10 }
            if (kept.isNotEmpty()) rows += WaterRow(top, bottom, kept)
            top = bottom
        }
        return rows
    }

    companion object {
        private val NATURES = mapOf(
            "sea" to (1.0 to 1.0), "ocean" to (1.0 to 1.0), "coast" to (1.0 to 1.0), "bay" to (1.0 to 1.0), "strait" to (1.0 to 1.0),
            "harbour" to (.54 to .72), "harbor" to (.54 to .72), "estuary" to (.55 to .74), "river" to (.4 to .65),
            "canal" to (.18 to .4), "lake" to (.25 to .46), "lagoon" to (.3 to .5), "marina" to (.2 to .42),
            "pool" to (.075 to .22), "pond" to (.075 to .22), "land" to (0.0 to 0.0),
        )

        val PIGMENT_SEEDS = listOf(11, 41, 83, 131, 173, 229)

        private val cache = HashMap<String, SceneMotion>()

        fun of(scene: CityScene): SceneMotion = synchronized(cache) { cache.getOrPut(scene.id) { SceneMotion(scene) } }

        /** Outlines of at least three points, every coordinate within the wallpaper; anything else is left out. */
        fun polygons(json: JsonElement?): List<Polygon> = (json as? JsonArray).orEmpty().mapNotNull { shape ->
            val points = (shape as? JsonArray)?.map { point ->
                val pair = (point as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull } ?: return@mapNotNull null
                if (pair.size != 2 || pair.any { it !in 0.0..1.0 }) return@mapNotNull null
                Point(pair[0], pair[1])
            } ?: return@mapNotNull null
            if (points.size >= 3) Polygon(points) else null
        }

        private fun band(json: JsonElement?): Pair<Double, Double>? =
            (json as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

        fun horizontalSpans(polygon: Polygon, y: Double): List<Pair<Double, Double>> {
            val crossings = mutableListOf<Double>()
            val points = polygon.points
            var j = points.size - 1
            for (i in points.indices) {
                val a = points[j]
                val b = points[i]
                if ((a.y <= y && y < b.y) || (b.y <= y && y < a.y)) crossings += a.x + (y - a.y) * (b.x - a.x) / (b.y - a.y)
                j = i
            }
            crossings.sort()
            return (0 until crossings.size - 1 step 2).mapNotNull { i -> (crossings[i] to crossings[i + 1]).takeIf { it.second - it.first > 1e-10 } }
        }

        fun intersect(left: List<Pair<Double, Double>>, right: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
            val result = mutableListOf<Pair<Double, Double>>()
            var a = 0
            var b = 0
            while (a < left.size && b < right.size) {
                val start = maxOf(left[a].first, right[b].first)
                val end = minOf(left[a].second, right[b].second)
                if (end - start > 1e-10) result += start to end
                if (left[a].second < right[b].second) a++ else b++
            }
            return result
        }

        fun subtract(spans: List<Pair<Double, Double>>, cuts: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
            val result = mutableListOf<Pair<Double, Double>>()
            for ((start, end) in spans) {
                var cursor = start
                for ((cutStart, cutEnd) in cuts) {
                    if (cutEnd <= cursor || cutStart >= end) continue
                    if (cutStart > cursor) result += cursor to minOf(cutStart, end)
                    cursor = maxOf(cursor, cutEnd)
                    if (cursor >= end) break
                }
                if (cursor < end) result += cursor to end
            }
            return result
        }

        /** Every x an edge of [polygon] passes through between [low] and [high]. */
        fun sweptSpans(polygon: Polygon, low: Double, high: Double): List<Pair<Double, Double>> {
            val spans = mutableListOf<Pair<Double, Double>>()
            val points = polygon.points
            var j = points.size - 1
            for (i in points.indices) {
                val a = points[j]
                val b = points[i]
                j = i
                if (a.y == b.y) {
                    if (a.y in low..high) spans += minOf(a.x, b.x) to maxOf(a.x, b.x)
                    continue
                }
                val fromY = maxOf(low, minOf(a.y, b.y))
                val toY = minOf(high, maxOf(a.y, b.y))
                if (toY < fromY) continue
                val fromX = a.x + (fromY - a.y) * (b.x - a.x) / (b.y - a.y)
                val toX = a.x + (toY - a.y) * (b.x - a.x) / (b.y - a.y)
                spans += minOf(fromX, toX) to maxOf(fromX, toX)
            }
            return spans.sortedWith(compareBy({ it.first }, { it.second }))
        }
    }
}

/** How strongly light plays on the water: the moon's share at night, the sun's height by day. */
fun lightStrength(sky: SkyState): Double {
    val night = (.35 + sky.moonLit * .65).coerceIn(.35, 1.0)
    val day = (sin(sky.sunAltitude * PI / 180) * .8 + .25).coerceIn(.12, 1.0)
    return night * (1 - sky.daylight) + day * sky.daylight
}

/** Where the sun or moon stands across the wallpaper, for the glints under it; null when neither is in sight. */
fun reflectionX(sky: SkyState): Double? = sky.body?.takeIf { it.moon == sky.daylight <= .5 }?.x?.coerceIn(-.2, 1.2)

/**
 * One of Codex's six cloud pigments, 192 by 96, as unpremultiplied RGBA: lobes of porous
 * pigment with a soft rim and an opening or two, never a band or a blue shadow.
 */
fun cloudPigment(seed: Int, night: Boolean): ByteArray {
    val width = 192
    val height = 96
    val random = Seeds.Random(seed)
    val color = if (night) intArrayOf(121, 128, 132) else intArrayOf(183, 181, 174)
    fun lattice(x: Int, y: Int): Double {
        var n = x * 374761393 + y * 668265263 + seed * 1447
        n = (n xor (n ushr 13)) * 1274126177
        return (n xor (n ushr 16)).toUInt().toDouble() / 4294967295.0
    }
    fun noise(x: Double, y: Double): Double {
        val ix = floor(x).toInt()
        val iy = floor(y).toInt()
        val u = smooth(x - ix)
        val v = smooth(y - iy)
        val a = lattice(ix, iy)
        val b = lattice(ix + 1, iy)
        val c = lattice(ix, iy + 1)
        val d = lattice(ix + 1, iy + 1)
        return (a + (b - a) * u) * (1 - v) + (c + (d - c) * u) * v
    }
    val lobeCount = 4 + seed % 4
    class Lobe(val x: Double, val y: Double, val rx: Double, val ry: Double, val strength: Double)
    val lobes = List(lobeCount) { i ->
        val x = .17 + i * (.69 / (lobeCount - 1)) + (random.next() - .5) * .09
        val y = .39 + (random.next() - .5) * .2
        val rx = .095 + random.next() * .065
        val ry = .13 + random.next() * .13
        Lobe(x, y, rx, ry, .65 + random.next() * .5)
    }
    class Hole(val x: Double, val y: Double, val rx: Double, val ry: Double)
    val holes = List(1 + seed % 3) {
        val x = .3 + random.next() * .4
        val y = .4 + random.next() * .2
        val rx = .045 + random.next() * .03
        Hole(x, y, rx, .06 + random.next() * .05)
    }
    val pixels = ByteArray(width * height * 4)
    for (py in 0 until height) {
        for (px in 0 until width) {
            val x = px.toDouble() / width
            val y = py.toDouble() / height
            val warp = (noise(x * 4 + 2, y * 4 + 9) - .5) * .11
            var mass = 0.0
            for (lobe in lobes) {
                val dx = (x - lobe.x) / lobe.rx
                val dy = (y + warp - lobe.y) / lobe.ry
                mass = maxOf(mass, exp(-(dx * dx + dy * dy) * 1.35) * lobe.strength)
            }
            var openings = 0.0
            for (hole in holes) {
                val dx = (x - hole.x) / hole.rx
                val dy = (y - hole.y) / hole.ry
                openings += exp(-(dx * dx + dy * dy) * 1.8) * .65
            }
            val coarse = noise(x * 11 + 7, y * 7 + 3)
            val fine = noise(x * 35 + 5, y * 23 + 17)
            val density = unit((mass + (coarse - .5) * .47 + (fine - .5) * .12 - openings - .19) / .68)
            val edge = smooth(unit(x / .1)) * smooth(unit((1 - x) / .1)) * smooth(unit(y / .12)) * smooth(unit((1 - y) / .18))
            val alpha = smooth(density) * (.25 + coarse * .75) * edge * (if (night) .27 else .38)
            val offset = (py * width + px) * 4
            pixels[offset] = color[0].toByte()
            pixels[offset + 1] = color[1].toByte()
            pixels[offset + 2] = color[2].toByte()
            pixels[offset + 3] = jsRound(alpha * 255).toByte()
        }
    }
    return pixels
}

/**
 * The windows already lit in the night painting, up to ten a region: warm pixels brighter than
 * what is around them, never on the water. [rgba] is the night painting laid on the whole
 * wallpaper at [width] by [height] (Codex samples at 768 wide), transparent where it was cut away.
 */
fun windowLights(sceneId: String, motion: SceneMotion, rgba: ByteArray, width: Int, height: Int): List<WindowLight> {
    fun channel(offset: Int) = rgba[offset].toInt() and 0xFF
    fun luminance(x: Int, y: Int): Double {
        val offset = (y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)) * 4
        return channel(offset) * .3 + channel(offset + 1) * .59 + channel(offset + 2) * .11
    }
    val lights = mutableListOf<WindowLight>()
    motion.lightRegions.forEachIndexed { region, polygon ->
        class Candidate(val x: Double, val y: Double, val r: Int, val g: Int, val b: Int, val score: Double)
        val candidates = mutableListOf<Candidate>()
        for (sy in ceil(polygon.top * height).toInt() until floor(polygon.bottom * height).toInt()) {
            for (sx in ceil(polygon.left * width).toInt() until floor(polygon.right * width).toInt()) {
                if (sx !in 0 until width || sy !in 0 until height) continue
                val point = Point((sx + .5) / width, (sy + .5) / height)
                if (point !in polygon || motion.water.any { point in it }) continue
                val offset = (sy * width + sx) * 4
                val r = channel(offset)
                val g = channel(offset + 1)
                val b = channel(offset + 2)
                // Only windows the painting lit already: warm and bright.
                if (r < 112 || g < 84 || r < b * 1.18 || g < b * 1.04 || g > r * 1.1) continue
                val around = (luminance(sx - 3, sy) + luminance(sx + 3, sy) + luminance(sx, sy - 3) + luminance(sx, sy + 3)) * .25
                if (luminance(sx, sy) - around < 12) continue
                candidates += Candidate(point.x, point.y, r, g, b, r + g - b * .7)
            }
        }
        val chosen = mutableListOf<WindowLight>()
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (chosen.all { hypot((it.x - candidate.x) * width, (it.y - candidate.y) * height) >= 3.8 }) {
                val seed = Seeds.of("$sceneId:${jsRound(candidate.x * width)}:${jsRound(candidate.y * height)}")
                chosen += WindowLight(
                    candidate.x, candidate.y, candidate.r, candidate.g, candidate.b, region, seed,
                    period = 54 + Seeds.unitHash(seed, 1) * 104, transition = 9 + Seeds.unitHash(seed, 2) * 13,
                    offset = Seeds.unitHash(seed, 3) * 158, variable = Seeds.unitHash(seed, 4) < .28,
                )
            }
            if (chosen.size == 10) break
        }
        lights += chosen
    }
    return lights
}

/** How bright a window is at [seconds]: each holds its level for a minute or two and changes gently, none together. */
fun WindowLight.activity(seconds: Double): Double {
    if (!variable) return .68 + Seeds.unitHash(seed, 5) * .16
    val local = seconds + offset
    val cell = floor(local / period).toInt()
    val position = wrap(local, period)
    fun level(step: Int): Double = Seeds.unitHash(seed, step).let { if (it < .12) .12 else .42 + it * .58 }
    val from = level(cell)
    val to = level(cell + 1)
    return from + (to - from) * smooth((position - period + transition) / transition)
}

/** JavaScript's Math.round: halves go up. */
fun jsRound(value: Double): Int = floor(value + .5).toInt()
