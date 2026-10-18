package cc.txwd.kone.city

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Boats, waves, birds and the city's errands against Codex's own layers, run in Node by desktop/tools/motion_reference.mjs. */
class MarineTest {
    private val reference = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/city/motion-reference.json")).use { it.readBytes().decodeToString() },
    ).jsonObject

    private fun scene(id: String) = Cities.all.first { it.id == id }
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double
    private fun near(expected: Double, actual: Double, what: String, within: Double = 1e-9) =
        assertTrue(abs(expected - actual) <= within, "$what: $actual, Codex $expected")

    @Test
    fun wavesBirdsAndBoatsAreCodexs() {
        reference.getValue("marine").jsonObject.forEach { (id, e) ->
            val expected = e.jsonObject
            val marine = SceneMarine(scene(id), SceneMotion(scene(id)))
            near(expected.number("seed"), marine.seed, "$id seed", 0.0)
            assertEquals(expected.getValue("waves").jsonArray.map { it.jsonPrimitive.int }, marine.waves.map { it.size }, "$id wave marks")
            for ((key, ours) in listOf("firstWave" to marine.waves.flatten().firstOrNull(), "lastWave" to marine.waves.flatten().lastOrNull())) {
                val theirs = expected[key] as? JsonObject ?: continue
                checkNotNull(ours)
                near(theirs.number("x"), ours.x, "$id $key x", 0.0)
                near(theirs.number("y"), ours.y, "$id $key y", 0.0)
                near(theirs.number("length"), ours.length, "$id $key length", 0.0)
                near(theirs.number("glint"), ours.glint, "$id $key glint", 0.0)
            }
            val birds = expected.getValue("birds").jsonArray.map { it.jsonObject }
            assertEquals(birds.size, marine.birds.size, "$id birds")
            birds.zip(marine.birds).forEach { (a, b) ->
                near(a.number("phase"), b.phase, "$id bird phase")
                near(a.number("lane"), b.lane, "$id bird lane")
                near(a.number("size"), b.size, "$id bird size")
                assertEquals(a.getValue("direction").jsonPrimitive.int, b.direction)
            }
            val flight = expected.getValue("flight").jsonObject
            near(flight.number("top"), marine.flight.top, "$id flight top")
            near(flight.number("bottom"), marine.flight.bottom, "$id flight bottom")
            val accepted = expected.getValue("accepted").jsonArray.map { it.jsonObject }
            assertEquals(accepted.map { it.getValue("id").jsonPrimitive.content }, marine.routes.map { it.id }, "$id routes")
            accepted.zip(marine.routes).forEach { (a, route) ->
                val travel = route.travel(73.5)
                near(a.number("progress"), ((travel % 1) + 1) % 1, "$id ${route.id} progress", 1e-9)
                near(a.number("speed"), route.speed(73.5), "$id ${route.id} speed", 1e-9)
            }
        }
    }

    @Test
    fun theCitysErrandsAreCodexs() {
        reference.getValue("life").jsonObject.forEach { (id, e) ->
            val expected = e.jsonObject
            val life = SceneLife(scene(id))
            assertEquals(expected.getValue("traffic").jsonPrimitive.int, life.traffic.size, "$id traffic")
            assertEquals(expected.getValue("walkers").jsonPrimitive.int, life.walkers.size, "$id walkers")
            assertEquals(expected.getValue("trees").jsonPrimitive.int, life.trees.size, "$id trees")
            assertEquals(expected.getValue("shores").jsonPrimitive.int, life.shores.size, "$id shores")
        }
    }

    @Test
    fun everySceneSailsAndGoesAsCodexsDoes() {
        reference.getValue("everyScene").jsonObject.forEach { (id, e) ->
            val (routes, birds, traffic, walkers) = e.jsonArray.map { it.jsonPrimitive.int }
            val marine = SceneMarine.of(scene(id))
            val life = SceneLife.of(scene(id))
            assertEquals(listOf(routes, birds, traffic, walkers), listOf(marine.routes.size, marine.birds.size, life.traffic.size, life.walkers.size), id)
        }
    }

    @Test
    fun aBoatNeverGoesBackOrJumps() {
        for (route in SceneMarine.of(scene("sydney-harbour")).routes) {
            var last = route.travel(0.0)
            var t = 0.0
            while (t < 600) {
                t += 1.0 / 30
                val now = route.travel(t)
                assertTrue(now > last && now - last < .01, "${route.id} at $t")
                last = now
            }
        }
    }
}
