package cc.txwd.kone.city

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The weather and sunlight against Codex's own code (desktop/tools/motion_reference.mjs), and the air easing between weathers. */
class WeatherTest {
    private val reference = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/city/motion-reference.json")).use { it.readBytes().decodeToString() },
    ).jsonObject

    private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.double
    private fun near(expected: Double, actual: Double, what: String, within: Double = 1e-9) =
        assertTrue(abs(expected - actual) <= within, "$what: $actual, Codex $expected")

    @Test
    fun openMeteoReadsAsCodexReadsIt() {
        reference.getValue("openMeteo").jsonArray.map { it.jsonObject }.forEach { case ->
            val ours = checkNotNull(WeatherProfile.fromOpenMeteo(case.getValue("answer").jsonObject))
            val theirs = case.getValue("profile").jsonObject
            near(theirs.number("cloudCover"), ours.cloudCover, "cloud")
            near(theirs.number("rain"), ours.rain, "rain")
            near(theirs.number("snow"), ours.snow, "snow")
            near(theirs.number("fog"), ours.fog, "fog")
            near(theirs.number("wind"), ours.wind, "wind")
            near(theirs.number("wetness"), ours.wetness, "wetness")
            near(theirs.number("temperature"), ours.temperature, "temperature")
        }
    }

    @Test
    fun anAnswerInOtherUnitsIsNotRead() {
        val answer = reference.getValue("openMeteo").jsonArray[0].jsonObject.getValue("answer").jsonObject
        val units = answer.getValue("current_units").jsonObject
        val fahrenheit = JsonObject(answer + ("current_units" to JsonObject(units + ("temperature_2m" to Json.parseToJsonElement("\"°F\"")))))
        assertNull(WeatherProfile.fromOpenMeteo(fahrenheit))
        assertNull(WeatherProfile.fromOpenMeteo(JsonObject(mapOf("current" to answer.getValue("current")))))
    }

    @Test
    fun severalCitiesComeInOneAnswer() {
        val answer = reference.getValue("openMeteo").jsonArray.map { it.jsonObject.getValue("answer") }
        val cities = Cities.pick("JP", java.time.LocalDate.of(2026, 10, 17)).take(3)
        val read = CityWeather.parse(JsonArray(answer), cities)
        assertEquals(cities.map { it.cityId }.toSet(), read.keys)
        assertTrue(read.getValue(cities[1].cityId).snow > .9)
        // One place asked for: one forecast, not a list.
        assertEquals(setOf(cities[0].cityId), CityWeather.parse(answer[0], cities.take(1)).keys)
    }

    @Test
    fun facadeMaskIsCodexs() {
        val expected = reference.getValue("facades").jsonObject
        val width = expected.getValue("width").jsonPrimitive.int
        val height = expected.getValue("height").jsonPrimitive.int
        val mask = facadeMask(picture(width, height), width, height)
        var sum = 0
        var lit = 0
        var weighted = 0L
        for (i in 3 until mask.size step 4) {
            val alpha = mask[i].toInt() and 0xFF
            sum += alpha
            weighted += alpha.toLong() * ((i shr 2) % 997 + 1)
            if (alpha > 0) lit++
        }
        assertEquals(expected.getValue("alphaSum").jsonPrimitive.int, sum)
        assertEquals(expected.getValue("lit").jsonPrimitive.int, lit)
        assertEquals(expected.getValue("weighted").jsonPrimitive.long, weighted)
    }

    @Test
    fun rainSnowAndRipplesFallWhereCodexsDo() {
        reference.getValue("fall").jsonObject.forEach { (id, expected) ->
            val fall = Precipitation(Cities.all.first { it.id == id }, SceneMotion(Cities.all.first { it.id == id }))
            val e = expected.jsonObject
            assertEquals(e.getValue("rings").jsonPrimitive.int, fall.rings.size, "$id rings")
            val drops = e.getValue("drops").jsonArray.map { it.jsonObject } + e.getValue("lastDrop").jsonObject
            drops.zip(fall.drops.take(4) + fall.drops[209]).forEach { (a, b) ->
                near(a.number("x"), b.x, "$id drop x", 0.0)
                near(a.number("y"), b.y, "$id drop y", 0.0)
                near(a.number("depth"), b.depth, "$id drop depth")
                near(a.number("speed"), b.speed, "$id drop speed", 0.0)
                near(a.number("alpha"), b.alpha, "$id drop alpha", 0.0)
            }
            val flakes = e.getValue("flakes").jsonArray.map { it.jsonObject } + e.getValue("lastFlake").jsonObject
            flakes.zip(fall.flakes.take(3) + fall.flakes[124]).forEach { (a, b) ->
                near(a.number("x"), b.x, "$id flake x", 0.0)
                near(a.number("period"), b.period, "$id flake period", 0.0)
            }
            val rings = e.getValue("firstRings").jsonArray.map { it.jsonObject } + e.getValue("lastRing").jsonObject
            rings.zip(fall.rings.take(3) + fall.rings.last()).forEach { (a, b) ->
                near(a.number("x"), b.x, "$id ring x", 0.0)
                near(a.number("y"), b.y, "$id ring y", 0.0)
                near(a.number("scale"), b.scale, "$id ring scale", 0.0)
            }
        }
    }

    @Test
    fun nothingLeapsWhenTheWeatherChanges() {
        val air = CityAir("tokyo-bay")
        val storm = WeatherProfile(cloudCover = .98, rain = .94, fog = .26, wind = 1.7, wetness = 1.0, temperature = 17.0)
        var last = air.at(0.0, null)
        var t = 0.0
        while (t < 120) {
            t += 1.0 / 30
            val weather = when {
                t < 30 -> null
                t < 60 -> WeatherProfile.CLEAR
                t < 90 -> storm
                else -> null
            }
            val now = air.at(t, weather)
            // The wind carries things on, never back and never by a jump.
            val step = now.advection - last.advection
            assertTrue(step > 0 && step < .2, "advection stepped $step at $t")
            assertTrue(abs(now.rain - last.rain) < .02, "rain jumped at $t")
            last = now
        }
    }

    @Test
    fun theSunWarmsLowAndFadesAtNight() {
        val noon = SkyState(daylight = 1.0, body = null, sunAltitude = 50.0)
        val low = SkyState(daylight = 1.0, body = null, sunAltitude = 6.0)
        val night = SkyState(daylight = 0.0, body = null, sunAltitude = -20.0)
        assertTrue(Sunlight.of(noon).strength > Sunlight.of(low).strength)
        assertTrue(Sunlight.of(low).warm > Sunlight.of(noon).warm)
        assertEquals(0.0, Sunlight.of(night).strength)
        // Heavy cloud and fog hide the sun and moon, but never quite.
        assertEquals(1.0, bodyShows(null, night = false, fogVisibility = 1.0))
        assertTrue(bodyShows(WeatherProfile(cloudCover = 1.0, rain = 1.0), night = false, fogVisibility = 1.0) in .4..0.6)
    }

    /** The made-up night street of motion_reference.mjs. */
    private fun picture(width: Int, height: Int): ByteArray {
        val pixels = ByteArray(width * height * 4)
        for (y in 0 until height) for (x in 0 until width) {
            val o = (y * width + x) * 4
            val lit = (x * 7 + y * 13) % 29 == 0
            pixels[o] = (if (lit) 200 + x % 50 else 40 + (x + y) % 30).toByte()
            pixels[o + 1] = (if (lit) 150 + y % 40 else 38 + (x * y) % 20).toByte()
            pixels[o + 2] = (if (lit) 60 + x % 30 else 45).toByte()
            pixels[o + 3] = 255.toByte()
        }
        return pixels
    }
}
