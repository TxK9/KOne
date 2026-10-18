package cc.txwd.kone.city

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin

/*
 * The boats, wave marks and birds of a city scene, as Codex's marine layer (marine-v13.js)
 * works them out: which routes a boat can keep to without touching a shore or a bridge, how far
 * along it is at a moment, where the wave marks sit and how the birds fly. Coordinates are
 * fractions of the whole wallpaper.
 */

/** How a kind of water moves: how many marks, how big, how often they crest, how fast. */
class WaterStyle(val count: Int, val scale: Double, val crest: Double, val tempo: Double, val drift: Double, val ripple: Double, val stretch: Double)

class WaveMark(
    val x: Double, val y: Double, val phase: Double, val speed: Double, val length: Double, val crest: Boolean,
    val weight: Double, val drift: Double, val bend: Double, val packet: Double, val layer: Int, val glint: Double,
)

class Segment(val a: Point, val b: Point, val length: Double, val start: Double, val end: Double, val ux: Double, val uy: Double)

/** A boat's way across the water: what it is, how big (a fraction of the width), how long a crossing takes, and whether it runs at night. */
class BoatRoute(val id: String, val kind: String, val width: Double, val duration: Double, val phase: Double, val night: Boolean, val segments: List<Segment>, val length: Double) {
    val motor get() = kind in MOTOR

    /** Where along its way the boat is at [progress] (0 to 1), and which way it heads, in wallpaper fractions (heading in square units). */
    fun at(progress: Double): Placed {
        val target = unit(progress) * length
        var index = 0
        while (index < segments.size - 1 && target > segments[index].end) index++
        val s = segments[index]
        val t = if (s.length > 0) unit((target - s.start) / s.length) else 0.0
        return Placed(s.a.x + (s.b.x - s.a.x) * t, s.a.y + (s.b.y - s.a.y) * t, s.ux, s.uy)
    }

    /** How far it has gone by [time]: whole crossings and the part of this one. Its speed varies a little but never stops. */
    fun travel(time: Double): Double {
        val amplitude = if (motor) .055 else if (kind == "sailboat") .105 else .13
        val phase = this.phase * TAU
        val frequency = if (motor) .043 else .068
        val secondary = .017 + this.phase * .006
        val varied = time + amplitude / frequency * (sin(time * frequency + phase) - sin(phase)) +
            .035 / secondary * (sin(time * secondary + phase * 1.7) - sin(phase * 1.7))
        return this.phase + varied * BOAT_PACE * (PACE[kind] ?: 1.0) / duration
    }

    fun speed(time: Double): Double {
        val amplitude = if (motor) .055 else if (kind == "sailboat") .105 else .13
        val frequency = if (motor) .043 else .068
        val phase = this.phase * TAU
        return (PACE[kind] ?: 1.0) * (1 + amplitude * cos(time * frequency + phase) + .035 * cos(time * (.017 + this.phase * .006) + phase * 1.7))
    }

    class Placed(val x: Double, val y: Double, val ux: Double, val uy: Double)

    companion object {
        val MOTOR = setOf("yacht", "ferry", "canalboat")
        val PACE = mapOf("rowboat" to .60, "gondola" to .55, "sailboat" to .82, "canalboat" to .78, "ferry" to .95, "yacht" to 1.08)
        const val BOAT_PACE = 3.4
    }
}

class Bird(
    val phase: Double, val lane: Double, val size: Double, val period: Double, val flap: Double, val index: Int, val direction: Int,
    val beatPeriod: Double, val beatDuty: Double, val beatRate: Double, val bank: Double,
)

class SceneMarine(scene: CityScene, motion: SceneMotion) {
    val water = motion.water
    val exclusions: List<Polygon>
    val type: String?
    val style: WaterStyle?
    /** Codex's own seed for the marine layer: not a 32-bit integer, it is used as a number too. */
    val seed: Double
    val waves: List<List<WaveMark>>
    val routes: List<BoatRoute>
    val rejected = mutableListOf<Pair<String, String>>()
    val birds: List<Bird>
    val flight: Box
    val skyExclusions: List<Polygon> = motion.cloudExclusions
    /** Which way the wind blows across the picture, and how much it lifts the marks. */
    val windX: Double
    val windY: Double

    init {
        val config = scene.motion
        val marine = config["marine"] as? JsonObject
        exclusions = SceneMotion.polygons(marine?.get("exclusionPolygons"))
        type = (marine?.get("type") as? JsonPrimitive)?.contentOrNull?.takeIf { it in STYLES }
        style = type?.let { STYLES[it] }
        var n = 437.0
        for (c in scene.id) n = (int32(n) * 31).toDouble() + c.code
        seed = n
        val random = Seeds.Random(int32(seed))
        waves = water.mapIndexed { _, polygon ->
            val s = style ?: return@mapIndexed emptyList()
            val result = mutableListOf<WaveMark>()
            var attempts = 0
            while (result.size < s.count && attempts < s.count * 35) {
                attempts++
                val x = polygon.left + random.next() * (polygon.right - polygon.left)
                val y = polygon.top + random.next() * (polygon.bottom - polygon.top)
                val p = Point(x, y)
                if (p !in polygon || exclusions.any { p in it }) continue
                val phase = random.next() * TAU
                val speed = .48 + random.next() * .72
                val length = .01 + random.next() * (.014 + .045 * s.scale)
                val crest = random.next() < s.crest
                val weight = .45 + random.next() * .55
                val drift = random.next()
                val bend = .6 + random.next() * 1.4
                val packet = random.next() * TAU
                result += WaveMark(x, y, phase, speed, length, crest, weight, drift, bend, packet, result.size % 3, random.next())
            }
            result
        }
        val birdConfig = (marine?.get("birds") as? JsonObject) ?: (config["birds"] as? JsonObject)
        val off = (marine?.get("birds") as? JsonPrimitive)?.booleanOrNull == false || (config["birds"] as? JsonPrimitive)?.booleanOrNull == false
        val band = (birdConfig?.get("skyBand") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 }
        val birdTop = band?.let { minOf(it[0], it[1]).coerceIn(.145, .255) } ?: .16
        val birdBottom = (band?.let { maxOf(it[0], it[1]).coerceIn(.16, .275) } ?: .255).coerceAtLeast(birdTop)
        val count = if (off) 0 else ((birdConfig?.get("count") as? JsonPrimitive)?.doubleOrNull?.let { jsRound(it) }
            ?: if (type in listOf("harbour", "bay", "strait", "lagoon", "marina")) 5 else 3).coerceIn(0, 10)
        class Flock(val phase: Double, val lane: Double, val period: Double, val direction: Int)
        val flocks = List(ceil(count / 3.0).toInt()) {
            val phase = random.next()
            val lane = .15 + random.next() * .5
            val period = 49 + random.next() * 19
            Flock(phase, lane, period, if (random.next() < .27) -1 else 1)
        }
        birds = List(count) { i ->
            val flock = flocks[i / 3]
            val member = i % 3
            val phase = (flock.phase + member * .036 + random.next() * .009) % 1
            val lane = unit(flock.lane + member * .065 + random.next() * .02)
            val size = .006 + random.next() * .0065
            val flap = random.next() * TAU
            val beatPeriod = 4.1 + random.next() * 3.8
            val beatDuty = .16 + random.next() * .15
            val beatRate = 5.2 + random.next() * 2.4
            Bird(phase, lane, size, flock.period, flap, i, flock.direction, beatPeriod, beatDuty, beatRate, .08 + random.next() * .12)
        }
        val sky = scene.skySafe
        val clouds = scene.cloudBand ?: (birdTop to birdBottom)
        flight = Box(
            left = unit(sky.left), right = unit(sky.right),
            top = maxOf(birdTop, minOf(clouds.first, clouds.second), unit(sky.top)),
            bottom = minOf(birdBottom, maxOf(clouds.first, clouds.second), unit(sky.bottom)),
        )
        val wind = (marine?.get("windScreen") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
        windX = wind?.getOrNull(0)?.let { if (it == 0.0) 1.0 else sign(it) } ?: 1.0
        windY = wind?.getOrNull(1)?.coerceIn(-.35, .35) ?: -.08
        routes = routes(marine, scene.crop.aspect)
    }

    private fun routes(marine: JsonObject?, aspect: Double): List<BoatRoute> {
        if (marine == null || aspect <= 0) return emptyList()
        val accepted = mutableListOf<BoatRoute>()
        (marine["routes"] as? JsonArray).orEmpty().forEachIndexed { index, element ->
            val raw = element as? JsonObject
            val id = (raw?.get("id") as? JsonPrimitive)?.contentOrNull ?: "route-${index + 1}"
            fun reject(reason: String) { rejected += id to reason }
            val points = (raw?.get("points") as? JsonArray)?.map { point ->
                (point as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 && it.all { v -> v in 0.0..1.0 } }?.let { Point(it[0], it[1]) }
            }
            if (raw == null || points == null || points.size < 2 || points.any { it == null }) return@forEachIndexed reject("invalid-points")
            val kind = (raw["kind"] as? JsonPrimitive)?.contentOrNull
            if (kind !in BoatRoute.PACE) return@forEachIndexed reject("unsupported-vessel")
            if (type in listOf("river", "canal", "marina") && kind == "sailboat") return@forEachIndexed reject("sailboat-disallowed-on-city-river")
            fun number(key: String) = (raw[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it != 0.0 }
            val width = (number("width") ?: .028).coerceIn(.009, .065)
            val duration = (number("duration") ?: 100.0).coerceIn(35.0, 600.0)
            val phase = (((number("phase") ?: 0.0) % 1) + 1) % 1
            val nightFlag = (raw["night"] as? JsonPrimitive)?.booleanOrNull
            val night = nightFlag == true || (kind in BoatRoute.MOTOR && nightFlag != false)
            val given = points.filterNotNull()
            val path = mutableListOf<Point>()
            if (kind == "yacht" && given.size > 2) {
                // Yachts round their corners.
                path += given[0]
                for (i in 1 until given.size - 1) {
                    val a = given[i - 1]
                    val b = given[i]
                    val c = given[i + 1]
                    val entry = Point(b.x + (a.x - b.x) * .2, b.y + (a.y - b.y) * .2)
                    val exit = Point(b.x + (c.x - b.x) * .2, b.y + (c.y - b.y) * .2)
                    path += entry
                    for (j in 1..16) {
                        val t = j / 16.0
                        val u = 1 - t
                        path += Point(u * u * entry.x + 2 * u * t * b.x + t * t * exit.x, u * u * entry.y + 2 * u * t * b.y + t * t * exit.y)
                    }
                }
                path += given.last()
            } else path += given
            val segments = mutableListOf<Segment>()
            var length = 0.0
            for (i in 1 until path.size) {
                val a = path[i - 1]
                val b = path[i]
                val dx = b.x - a.x
                val dy = (b.y - a.y) / aspect
                val l = hypot(dx, dy)
                if (l < .00001) continue
                segments += Segment(a, b, l, length, length + l, dx / l, dy / l)
                length += l
            }
            if (length == 0.0) return@forEachIndexed reject("zero-length")
            val route = BoatRoute(id, kind!!, width, duration, phase, night, segments, length)
            // The whole boat, mast and sails and bobbing, stays in the water all the way.
            val (ex, ey) = when (kind) {
                "sailboat" -> .72 to 1.16
                "gondola", "rowboat" -> .87 to .85
                else -> .66 to .66
            }
            val mx = width * ex + .0008
            val my = (width * ey + .0008) * aspect
            val samples = ceil(length / minOf(.0015, width / 5)).toInt()
            for (s in 0..samples) {
                val p = route.at(s.toDouble() / samples)
                if (!safe(p.x, p.y, mx, my)) return@forEachIndexed reject("vessel-bounds-outside-reviewed-water")
            }
            accepted += route
        }
        return accepted
    }

    private fun safe(x: Double, y: Double, mx: Double, my: Double): Boolean {
        val left = x - mx
        val top = y - my
        val right = x + mx
        val bottom = y + my
        val corners = listOf(Point(left, top), Point(right, top), Point(right, bottom), Point(left, bottom))
        val contained = water.any { p -> corners.all { it in p } && !touches(p, left, top, right, bottom) }
        return contained && !exclusions.any { p -> corners.any { it in p } || touches(p, left, top, right, bottom) }
    }

    companion object {
        val STYLES = mapOf(
            "lake" to WaterStyle(64, .16, .0, .58, .0038, .38, 1.1),
            "bay" to WaterStyle(132, 1.0, .32, 1.0, .008, .9, 1.2),
            "coast" to WaterStyle(112, .72, .15, .88, .007, .76, 1.15),
            "strait" to WaterStyle(96, .85, .2, 1.08, .01, .8, 1.4),
            "harbour" to WaterStyle(84, .65, .06, .7, .0048, .62, 1.0),
            "lagoon" to WaterStyle(60, .46, .0, .52, .003, .38, 1.15),
            "marina" to WaterStyle(48, .25, .0, .5, .0028, .3, .9),
            "river" to WaterStyle(64, .24, .0, .84, .0075, .46, 1.45),
            "canal" to WaterStyle(40, .12, .0, .42, .0022, .22, .9),
        )

        /** JavaScript's ToInt32. */
        fun int32(value: Double): Int = value.toLong().toInt()

        /** Whether an edge of [polygon] reaches into the rectangle. */
        fun touches(polygon: Polygon, left: Double, top: Double, right: Double, bottom: Double): Boolean {
            fun inRect(x: Double, y: Double) = x in left..right && y in top..bottom
            val points = polygon.points
            var j = points.size - 1
            for (i in points.indices) {
                val a = points[j]
                val b = points[i]
                j = i
                if (inRect(a.x, a.y) || inRect(b.x, b.y)) return true
                val dx = b.x - a.x
                val dy = b.y - a.y
                for (x in listOf(left, right)) {
                    val t = if (dx != 0.0) (x - a.x) / dx else -1.0
                    if (t in 0.0..1.0 && a.y + t * dy in top..bottom) return true
                }
                for (y in listOf(top, bottom)) {
                    val t = if (dy != 0.0) (y - a.y) / dy else -1.0
                    if (t in 0.0..1.0 && a.x + t * dx in left..right) return true
                }
            }
            return false
        }

        private val cache = HashMap<String, SceneMarine>()

        fun of(scene: CityScene): SceneMarine = synchronized(cache) { cache.getOrPut(scene.id) { SceneMarine(scene, SceneMotion.of(scene)) } }
    }
}
