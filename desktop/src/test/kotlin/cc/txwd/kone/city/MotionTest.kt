package cc.txwd.kone.city

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The port against Codex's own motion code, run in Node by desktop/tools/motion_reference.mjs. */
class MotionTest {
    private val reference = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/city/motion-reference.json")).use { it.readBytes().decodeToString() },
    ).jsonObject

    private fun scene(id: String) = Cities.all.first { it.id == id }
    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double
    private fun near(expected: Double, actual: Double, what: String, within: Double = 1e-9) =
        assertTrue(abs(expected - actual) <= within, "$what: $actual, Codex $expected")

    @Test
    fun seedsAndHashesMatchBitForBit() {
        reference.getValue("seeds").jsonObject.forEach { (id, seed) -> assertEquals(seed.jsonPrimitive.int, Seeds.of(id), id) }
        reference.getValue("unitHash").jsonArray.map { it.jsonObject }.forEach {
            near(it.number("value"), Seeds.unitHash(it.getValue("seed").jsonPrimitive.int, it.getValue("cell").jsonPrimitive.int), "unitHash", 0.0)
        }
        val random = Seeds.Random(Seeds.of("tokyo-bay") xor 0x71ac49)
        reference.getValue("random").jsonArray.forEach { near(it.jsonPrimitive.double, random.next(), "random", 0.0) }
    }

    @Test
    fun theWindIsCodexs() {
        reference.getValue("wind").jsonArray.map { it.jsonObject }.forEach {
            val wind = Wind.at(it.getValue("id").jsonPrimitive.content, it.number("seconds"))
            near(it.number("wind"), wind.wind, "wind")
            near(it.number("advection"), wind.advection, "advection")
            near(it.number("gust"), wind.gust, "gust")
            near(it.number("cloudCover"), wind.cloudCover, "cloud cover")
            near(it.number("waveGroup"), wind.waveGroup, "wave group")
            near(it.number("shimmer"), wind.shimmer, "shimmer")
        }
    }

    @Test
    fun cloudPigmentIsCodexs() {
        reference.getValue("pigments").jsonArray.map { it.jsonObject }.forEach {
            val seed = it.getValue("seed").jsonPrimitive.int
            val night = it.getValue("night").jsonPrimitive.boolean
            val pixels = cloudPigment(seed, night)
            var sum = 0
            for (i in 3 until pixels.size step 4) sum += pixels[i].toInt() and 0xFF
            assertEquals(it.getValue("alphaSum").jsonPrimitive.int, sum, "pigment $seed night=$night")
            val sample = listOf((40 * 192 + 60) * 4 + 3, (48 * 192 + 96) * 4 + 3, (30 * 192 + 130) * 4 + 3).map { i -> pixels[i].toInt() and 0xFF }
            assertEquals(it.getValue("sample").jsonArray.map { v -> v.jsonPrimitive.int }, sample)
        }
    }

    @Test
    fun waterRowsKeepToTheWaterAsCodexsDo() {
        reference.getValue("water").jsonObject.forEach { (id, expected) ->
            val motion = SceneMotion(scene(id))
            expected.jsonArray.forEachIndexed { index, rows ->
                val ours = motion.waterRows(index, .004, 1 / 852.0, 1 / 1852.0)
                assertEquals(rows.jsonArray.size, ours.size, "$id water $index rows")
                rows.jsonArray.map { it.jsonObject }.zip(ours).forEach { (theirs, row) ->
                    near(theirs.number("top"), row.top, "$id row top")
                    val spans = theirs.getValue("spans").jsonArray.map { (it as JsonArray).let { pair -> pair[0].jsonPrimitive.double to pair[1].jsonPrimitive.double } }
                    assertEquals(spans.size, row.spans.size, "$id spans at ${row.top}")
                    spans.zip(row.spans).forEach { (a, b) ->
                        near(a.first, b.first, "$id span start")
                        near(a.second, b.second, "$id span end")
                    }
                }
            }
        }
    }

    @Test
    fun litWindowsAreFoundAsCodexFindsThem() {
        val width = 256
        val height = 512
        // The made-up night street of motion_reference.mjs: dark, with warm bright dots.
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val o = (y * width + x) * 4
            val lit = (x * 7 + y * 13) % 29 == 0
            pixels[o] = (if (lit) 200 + x % 50 else 40 + (x + y) % 30).toByte()
            pixels[o + 1] = (if (lit) 150 + y % 40 else 38 + (x * y) % 20).toByte()
            pixels[o + 2] = (if (lit) 60 + x % 30 else 45).toByte()
            pixels[o + 3] = 255.toByte()
        }
        reference.getValue("lights").jsonObject.forEach { (id, expected) ->
            val ours = windowLights(id, SceneMotion(scene(id)), pixels, width, height)
            val theirs = expected.jsonArray.map { it.jsonObject }
            assertEquals(theirs.size, ours.size, "$id lights")
            theirs.zip(ours).forEach { (a, b) ->
                near(a.number("x"), b.x, "$id light x")
                near(a.number("y"), b.y, "$id light y")
                assertEquals(listOf(a.getValue("r"), a.getValue("g"), a.getValue("b"), a.getValue("region"), a.getValue("seed")).map { it.jsonPrimitive.int }, listOf(b.r, b.g, b.b, b.region, b.seed))
                near(a.number("period"), b.period, "$id period")
                near(a.number("transition"), b.transition, "$id transition")
                near(a.number("offset"), b.offset, "$id offset")
                assertEquals(a.getValue("variable").jsonPrimitive.boolean, b.variable)
            }
        }
    }

    @Test
    fun aWindowHoldsItsLevelThenChangesGently() {
        val light = WindowLight(.5, .5, 220, 180, 80, 0, Seeds.of("x"), period = 60.0, transition = 10.0, offset = 0.0, variable = true)
        // Held through the first fifty seconds of each minute.
        assertEquals(light.activity(5.0), light.activity(45.0), 1e-12)
        // No jump: one frame apart, never more than a little.
        var previous = light.activity(0.0)
        var t = 0.0
        while (t < 600) {
            t += 1.0 / 30
            val now = light.activity(t)
            assertTrue(abs(now - previous) < .01, "a jump at $t")
            previous = now
        }
    }

    @Test
    fun cloudsKeepToTheirBand() {
        for (scene in Cities.all) {
            val (top, bottom) = SceneMotion.of(scene).cloudBand
            assertTrue(top >= .13 - 1e-12 && bottom <= .267 + 1e-12, "${scene.id} clouds $top..$bottom")
        }
    }
}
