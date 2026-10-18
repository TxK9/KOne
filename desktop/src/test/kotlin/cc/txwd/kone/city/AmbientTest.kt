package cc.txwd.kone.city

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The soundscape: Codex's noise, silent until asked, never loud, and fading out when it is no longer wanted. */
class AmbientTest {
    @Test
    fun theNoiseIsCodexs() {
        val reference = Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/city/motion-reference.json")).use { it.readBytes().decodeToString() })
            .jsonObject.getValue("noise").jsonObject
        for ((kind, seed) in listOf("wind" to 913, "rain" to 419, "sea" to 2671)) {
            val expected = reference.getValue(kind).jsonObject
            val noise = Soundscape.noise(kind, seed, 44_100)
            assertEquals(expected.getValue("length").jsonPrimitive.int, noise.size)
            expected.getValue("first").jsonArray.forEachIndexed { i, v -> assertEquals(v.jsonPrimitive.double.toFloat(), noise[i], "$kind $i") }
            expected.getValue("seam").jsonArray.forEachIndexed { i, v -> assertEquals(v.jsonPrimitive.double.toFloat(), noise[noise.size - 3 + i], "$kind seam $i") }
            assertTrue(abs(expected.getValue("sum").jsonPrimitive.double - noise.sumOf { abs(it.toDouble()) }) < 1e-3, kind)
        }
    }

    private fun listen(scape: Soundscape, seconds: Double, mix: Soundscape.Mix?): FloatArray {
        val out = FloatArray((scape.rate * seconds).toInt())
        val block = FloatArray(scape.rate / 50)
        var at = 0
        var followed = -1.0
        while (at < out.size) {
            if (scape.time - followed >= .2) {
                scape.follow(mix)
                followed = scape.time
            }
            scape.render(block)
            block.copyInto(out, at, 0, minOf(block.size, out.size - at))
            at += block.size
        }
        return out
    }

    private fun FloatArray.rms(from: Int = 0) = sqrt(drop(from).sumOf { it.toDouble() * it } / (size - from))

    @Test
    fun silentUntilAskedQuietWhenPlayingSilentAgainAfter() {
        val scape = Soundscape()
        val untouched = FloatArray(4410)
        scape.render(untouched)
        assertTrue(untouched.all { it == 0f })
        val storm = listen(scape, 3.0, Soundscape.Mix(wind = 1.7, rain = .94, water = "bay"))
        val loud = storm.rms(scape.rate)
        assertTrue(loud > .002, "barely audible: $loud")
        assertTrue(storm.maxOf { abs(it) } < .12, "too loud: ${storm.maxOf { abs(it) }}")
        val after = listen(scape, 2.0, null)
        assertTrue(scape.silent)
        assertTrue(after.rms(scape.rate) < loud / 100)
    }

    @Test
    fun theSeaIsHeardOnlyByTheSea() {
        val inland = listen(Soundscape(), 3.0, Soundscape.Mix(wind = .5, water = "river")).rms(44_100)
        val coast = listen(Soundscape(), 3.0, Soundscape.Mix(wind = .5, water = "coast")).rms(44_100)
        assertTrue(coast > inland * 2, "coast $coast, river $inland")
    }
}
