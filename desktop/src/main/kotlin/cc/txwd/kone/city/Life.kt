package cc.txwd.kone.city

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.hypot

/*
 * The small life of a city scene, as Codex's city-life layer (city-life-v13.js) reads its
 * reviewed notes on each painting: trees that sway, glass that catches the sun, foam on the
 * shore, lit accents at night, fountains, turning wheels, grass and heat, and cars and people
 * that keep to the roads and squares drawn for them. Coordinates are fractions of the wallpaper.
 */

class Tree(val center: Point, val rx: Double, val ry: Double, val kind: String?, val phase: Double?, val period: Double?, val gain: Double)

class Facade(val polygon: Polygon, val period: Double?, val phase: Double?, val gain: Double)

class Shore(val water: Polygon, val points: List<Point>, val flow: Pair<Double, Double>, val period: Double?, val phase: Double, val gain: Double, val reach: Double, val shallow: Polygon?)

class Eave(val a: Point, val b: Point, val fall: Double)

class Accent(val polygon: Polygon, val color: Triple<Int, Int, Int>, val period: Double?, val phase: Double?, val power: Double)

class Jet(val base: Point, val top: Point, val spread: Double, val clip: Polygon?, val period: Double, val power: Double)

class Wheel(val center: Point, val rx: Double, val ry: Double, val color: Triple<Int, Int, Int>?, val period: Double, val phase: Double)

class Ground(val polygon: Polygon, val kind: String, val period: Double?, val phase: Double?, val gain: Double, val flow: Pair<Double, Double>)

class Heat(val polygon: Polygon, val period: Double, val gain: Double, val minTemperature: Double)

/** A car or a walker going along [points] now and then: [period] seconds between, [travel] of them on the way. */
class Errand(val id: String?, val points: List<Point>, val size: Double, val height: Double, val period: Double, val travel: Double, val phase: Double, val color: String?)

class SceneLife(scene: CityScene) {
    val water: List<Polygon>
    val waterExclusions: List<Polygon>
    val trees: List<Tree>
    val facades: List<Facade>
    val shores: List<Shore>
    val eaves: List<Eave>
    val accents: List<Accent>
    val jets: List<Jet>
    val wheels: List<Wheel>
    val grounds: List<Ground>
    val heats: List<Heat>
    val roads: List<Polygon>
    val plazas: List<Polygon>
    val hidden: List<Polygon>
    val traffic: List<Errand>
    val walkers: List<Errand>
    val rejected = mutableListOf<Pair<String?, String>>()
    /** Whether Codex wrote notes for the scene at all. */
    val reviewed = scene.life.isNotEmpty()

    init {
        val c = scene.life
        fun list(key: String) = (c[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        fun polygons(key: String) = SceneMotion.polygons(c[key])
        water = polygons("waterPolygons")
        waterExclusions = polygons("occlusionPolygons") + polygons("waterExclusions") + polygons("islandPolygons")
        trees = list("trees").mapNotNull { t ->
            val center = t.point("center") ?: return@mapNotNull null
            val rx = t.number("rx")?.takeIf { it > 0 } ?: return@mapNotNull null
            val ry = t.number("ry")?.takeIf { it > 0 } ?: return@mapNotNull null
            Tree(center, rx, ry, t.text("kind"), t.number("phase"), t.number("period"), (t.number("gain") ?: 1.0).coerceIn(.2, 2.0))
        }
        facades = (c["glassFacades"] as? JsonArray).orEmpty().mapNotNull { v ->
            if (v is JsonArray) return@mapNotNull polygon(v)?.let { Facade(it, null, null, 1.0) }
            val o = v as? JsonObject ?: return@mapNotNull null
            val polygon = polygon(o["polygon"]) ?: return@mapNotNull null
            Facade(polygon, o.number("period"), o.number("phase"), (o.number("gain") ?: 1.0).coerceIn(.2, 2.0))
        }
        shores = list("shorelines").mapNotNull { s ->
            val waterPolygon = polygon(s["waterPolygon"]) ?: return@mapNotNull null
            val points = (s["points"] as? JsonArray)?.map { point(it) ?: return@mapNotNull null }?.takeIf { it.size >= 2 } ?: return@mapNotNull null
            val flowNumbers = (s["flow"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 }
            Shore(
                waterPolygon, points, flowNumbers?.let { it[0] to it[1] } ?: (0.0 to 1.0), s.number("period"),
                s.number("phase") ?: 0.0, (s.number("gain") ?: 1.0).coerceIn(.2, 1.8), s.number("reach")?.takeIf { it != 0.0 } ?: .006, polygon(s["shallowPolygon"]),
            )
        }
        eaves = list("eaves").mapNotNull { e -> Eave(e.point("a") ?: return@mapNotNull null, e.point("b") ?: return@mapNotNull null, e.number("fall")?.takeIf { it != 0.0 } ?: .008) }
        accents = list("accentLights").mapNotNull { a ->
            val polygon = polygon(a["polygon"]) ?: return@mapNotNull null
            Accent(polygon, a.color("color") ?: Triple(247, 208, 155), a.number("period"), a.number("phase"), (a.number("power") ?: .7).coerceIn(.1, 1.5))
        }
        jets = list("waterJets").mapNotNull { j ->
            val base = j.point("base") ?: return@mapNotNull null
            val top = j.point("top") ?: return@mapNotNull null
            if (base.y <= top.y) return@mapNotNull null
            Jet(base, top, (j.number("spread")?.takeIf { it != 0.0 } ?: .01).coerceIn(.001, .04), polygon(j["clipPolygon"]),
                (j.number("period")?.takeIf { it != 0.0 } ?: 4.8).coerceIn(2.0, 12.0), (j.number("power") ?: 1.0).coerceIn(.2, 2.0))
        }
        wheels = list("wheels").mapNotNull { w ->
            val center = w.point("center") ?: return@mapNotNull null
            val rx = w.number("rx")?.takeIf { it > 0 } ?: return@mapNotNull null
            val ry = w.number("ry")?.takeIf { it > 0 } ?: return@mapNotNull null
            Wheel(center, rx, ry, w.color("color"), (w.number("period")?.takeIf { it != 0.0 } ?: 100.0).coerceIn(45.0, 240.0), w.number("phase") ?: 0.0)
        }
        grounds = list("dryWind").mapNotNull { g ->
            val polygon = polygon(g["polygon"]) ?: return@mapNotNull null
            val kind = g.text("kind")?.takeIf { it == "sand" || it == "grass" } ?: return@mapNotNull null
            val flow = (g["flow"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 }
            Ground(polygon, kind, g.number("period"), g.number("phase"), (g.number("gain") ?: 1.0).coerceIn(.1, 2.0), flow?.let { it[0] to it[1] } ?: (1.0 to 0.0))
        }
        heats = list("heatShimmer").mapNotNull { h ->
            val polygon = polygon(h["polygon"]) ?: return@mapNotNull null
            Heat(polygon, (h.number("period")?.takeIf { it != 0.0 } ?: 5.0).coerceIn(2.0, 20.0), (h.number("gain") ?: 1.0).coerceIn(.1, 1.8), h.number("minTemperature") ?: 22.0)
        }
        roads = polygons("roads")
        plazas = polygons("walkPolygons")
        hidden = polygons("occlusionPolygons")
        val aspect = scene.crop.aspect
        traffic = list("traffic").mapNotNull { errand(it) }.filter { r ->
            safe(r, roads, pedestrian = false, aspect).also { if (!it) rejected += r.id to "vehicle envelope outside reviewed road" }
        }
        walkers = list("walkers").mapNotNull { errand(it) }.filter { r ->
            safe(r, plazas, pedestrian = true, aspect).also { if (!it) rejected += r.id to "pedestrian envelope outside reviewed plaza" }
        }
    }

    private fun errand(o: JsonObject): Errand? {
        val points = (o["points"] as? JsonArray)?.map { point(it) ?: return null }?.takeIf { it.size >= 2 } ?: return null
        val period = o.number("period")?.takeIf { it != 0.0 } ?: 42.0
        return Errand(
            o.text("id"), points, o.number("size")?.takeIf { it != 0.0 } ?: .007, o.number("height")?.takeIf { it != 0.0 } ?: .004,
            period, o.number("travel")?.takeIf { it != 0.0 } ?: period * .8, o.number("phase") ?: 0.0, o.text("color"),
        )
    }

    /** The whole car or walker stays on its road or square all the way, at any heading. */
    private fun safe(route: Errand, polygons: List<Polygon>, pedestrian: Boolean, aspect: Double): Boolean {
        if (polygons.isEmpty()) return false
        val rx = if (pedestrian) .0018 else maxOf(.0012, hypot(route.size / 2, route.size * .17))
        val top = if (pedestrian) -route.height * 1.3 else -rx * aspect
        val bottom = if (pedestrian) .0003 else rx * aspect
        for (step in 0..100) {
            val p = along(route.points, step / 100.0)
            for ((ox, oy) in listOf(-rx to top, rx to top, rx to bottom, -rx to bottom, 0.0 to 0.0)) {
                val probe = Point(p.x + ox, p.y + oy)
                if (polygons.none { probe in it }) return false
            }
        }
        return true
    }

    /** Where a car or walker is at [time], how far along and how faded in, or null while it is away or hidden. */
    fun position(route: Errand, time: Double): Going? {
        val phase = ((time + route.phase * route.period) % route.period + route.period) % route.period
        if (phase >= route.travel) return null
        val progress = phase / route.travel
        val fade = smooth(0.0, .055, progress) * (1 - smooth(.945, 1.0, progress))
        val p = along(route.points, progress)
        if (hidden.any { Point(p.x, p.y) in it }) return null
        return Going(p.x, p.y, p.dx, p.dy, progress, fade)
    }

    class Going(val x: Double, val y: Double, val dx: Double, val dy: Double, val progress: Double, val fade: Double)

    companion object {
        /** A point [phase] of the way along a path, by length, and the direction of its piece there. */
        fun along(points: List<Point>, phase: Double): Going {
            val lengths = (1 until points.size).map { hypot(points[it].x - points[it - 1].x, points[it].y - points[it - 1].y) }
            var remaining = unit(phase) * lengths.sum()
            var i = 0
            while (i < lengths.size - 1 && remaining > lengths[i]) remaining -= lengths[i++]
            val a = points[i]
            val b = points[i + 1]
            val f = if (lengths[i] != 0.0) remaining / lengths[i] else 0.0
            return Going(a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, b.x - a.x, b.y - a.y, phase, 1.0)
        }

        private fun point(json: JsonElement?): Point? =
            (json as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 2 && it.all { v -> v in 0.0..1.0 } }?.let { Point(it[0], it[1]) }

        private fun polygon(json: JsonElement?): Polygon? = SceneMotion.polygons(JsonArray(listOfNotNull(json))).firstOrNull()

        private fun JsonObject.point(key: String) = point(this[key])
        private fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
        private fun JsonObject.color(key: String) = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }?.takeIf { it.size == 3 }
            ?.map { it.coerceIn(0.0, 255.0).toInt() }?.let { Triple(it[0], it[1], it[2]) }

        private val cache = HashMap<String, SceneLife>()

        fun of(scene: CityScene): SceneLife = synchronized(cache) { cache.getOrPut(scene.id) { SceneLife(scene) } }
    }
}
