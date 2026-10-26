package io.nekohasekai.sfa.compose.theme

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CityCanvasTest {
    private val white = 0xFFFFFFFF.toInt()
    private val sky = 0xFFE8ECF0.toInt()
    private val water = 0xFF0C1626.toInt()

    private fun profile(vararg lines: CityScrim.Line) = CityScrim.profile(lines.toList(), cells = 200, cell = 4f, bridge = 56f, soften = 12f, feather = 40f)

    @Test fun aListReadsAsOneBandWithoutStripes() {
        // Rows 48 px apart with 20 px of text each: the gaps never dip below the lighter row's need.
        val rows = (0 until 6).map { CityScrim.Line(200f + it * 48f, 220f + it * 48f, if (it % 2 == 0) 0.5f else 0.3f) }
        val shade = profile(*rows.toTypedArray())
        for (i in (200 / 4)..(460 / 4)) assertTrue("cell $i", shade[i] >= 0.3f)
    }

    @Test fun everyLineGetsAtLeastWhatItNeeds() {
        val lines = listOf(CityScrim.Line(100f, 120f, 0.7f), CityScrim.Line(124f, 144f, 0.25f), CityScrim.Line(300f, 316f, 0.4f))
        val shade = profile(*lines.toTypedArray())
        for (line in lines) for (i in (line.top / 4).toInt()..(line.bottom / 4).toInt()) assertTrue(shade[i] >= line.alpha)
    }

    @Test fun theOpenCityAboveAndBelowStaysClear() {
        val shade = profile(CityScrim.Line(400f, 420f, 0.6f))
        // Feather 40 px and softening 12 px on each side; past that the city shows unshaded.
        for (i in 0 until (400 - 60) / 4) assertEquals(0f, shade[i], 0f)
        for (i in (420 + 64) / 4 until 200) assertEquals(0f, shade[i], 0f)
        // And the edge fades rather than stops.
        assertTrue(shade[(400 - 24) / 4] in 0.01f..0.59f)
    }

    @Test fun distantGroupsStaySeparate() {
        val shade = profile(CityScrim.Line(100f, 120f, 0.5f), CityScrim.Line(400f, 420f, 0.5f))
        assertEquals(0f, shade[260 / 4], 0f)
    }

    @Test fun shadeDeepensQuicklyAndGivesTheCityBackSlowly() {
        val rising = floatArrayOf(0f)
        val falling = floatArrayOf(0.6f)
        CityScrim.ease(rising, floatArrayOf(0.6f), 60f)
        CityScrim.ease(falling, floatArrayOf(0f), 60f)
        assertTrue(rising[0] > 0.6f - falling[0])
        val shown = floatArrayOf(0f)
        var settled = false
        repeat(200) { if (!settled) settled = CityScrim.ease(shown, floatArrayOf(0.5f), 16f) }
        assertTrue(settled)
        assertEquals(0.5f, shown[0], 0f)
    }

    @Test fun measuredShadeKeepsInkAndAccentAt4_5OverABrightSky() {
        val map = CityShade.measure(1, 1, intArrayOf(white), intArrayOf(water), daylight = 1f)
        val need = map.need(Rect(0f, 0f, 10f, 10f), Size(10f, 10f), large = false)
        assertTrue(need in CITY_SHADE_MIN..CITY_SHADE_MAX)
        val lifted = KninePalette.composite(white, white, 0.08f)
        val veiled = KninePalette.composite(CITY_SHADE, lifted, CITY_WALLPAPER_LIVELY_VEIL)
        val behind = KninePalette.luminance(KninePalette.composite(CITY_SHADE, veiled, need))
        assertTrue(KninePalette.contrast(KninePalette.LIGHT_INK, behind) >= 4.5f)
        assertTrue(KninePalette.contrast(KninePalette.LIGHT_ACCENT, behind) >= 4.5f)
    }

    @Test fun nightAsksForLessThanDayAndUnknownLightAssumesTheBrighter() {
        val area = Rect(0f, 0f, 10f, 10f)
        val canvas = Size(10f, 10f)
        val day = CityShade.measure(1, 1, intArrayOf(sky), intArrayOf(water), 1f).need(area, canvas, false)
        val night = CityShade.measure(1, 1, intArrayOf(sky), intArrayOf(water), 0f).need(area, canvas, false)
        val unknown = CityShade.measure(1, 1, intArrayOf(sky), intArrayOf(water), null).need(area, canvas, false)
        assertTrue(night < day)
        assertEquals(day, unknown, 0f)
        // Icons and large titles ask for no more than body text.
        assertTrue(CityShade.measure(1, 1, intArrayOf(sky), intArrayOf(water), 1f).need(area, canvas, true) <= day)
    }

    @Test fun theMapLiesOnTheScreenAsTheStageCoversIt() {
        // A 2 x 4 artwork, bright sky above dark water, on a screen of the same aspect.
        val day = intArrayOf(white, white, white, white, water, water, water, water)
        val map = CityShade.measure(2, 4, day, day, 1f)
        val canvas = Size(100f, 200f)
        assertTrue(map.need(Rect(0f, 10f, 100f, 30f), canvas, false) > map.need(Rect(0f, 160f, 100f, 180f), canvas, false))
        // A taller screen scales the stage up and crops its sides; the halves keep their places.
        val tall = Size(100f, 400f)
        assertTrue(map.need(Rect(40f, 20f, 60f, 40f), tall, false) > map.need(Rect(40f, 360f, 60f, 380f), tall, false))
    }

    @Test fun daylightIsReadFromTheStageOnly() {
        assertFalse(CityLight.read("original", "\"\""))
        assertFalse(CityLight.read("original", null))
        assertTrue(CityLight.read("original", "\"0.73\""))
        assertEquals(0.73f, CityLight.shown!!.daylight, 0.0001f)
        assertTrue(CityLight.read("original", "\"1.4\""))
        assertEquals(1f, CityLight.shown!!.daylight, 0f)
    }

    @Test fun popupFocusVeilMatchesTheOldWholeCityVeil() {
        val total = 1f - (1f - CITY_WALLPAPER_LIVELY_VEIL) * (1f - CITY_FOCUS_VEIL)
        assertEquals(CITY_WALLPAPER_VEIL_ALPHA, total, 0.0001f)
    }

    @Test fun largeTextFollowsWcag() {
        assertTrue(largeText(TextStyle(fontSize = 24.sp)))
        assertTrue(largeText(TextStyle(fontSize = 19.sp, fontWeight = FontWeight.Bold)))
        assertFalse(largeText(TextStyle(fontSize = 22.sp)))
        assertFalse(largeText(TextStyle()))
    }
}
