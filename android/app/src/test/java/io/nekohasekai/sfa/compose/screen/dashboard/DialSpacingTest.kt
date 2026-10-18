package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.sin

class DialSpacingTest {
    // Pixels at density 1: the dial radius, padding and gap used on the phone, in dp.
    private val radius = 360f
    private val pad = 10f
    private val gap = 8f

    /** Text widths that scale with the type factor; padding does not. */
    private fun fit(texts: List<Float>, halfScreen: Float) =
        DialSpacing.fit(texts.size, { i, type -> texts[i] * type + 2 * pad }, radius, halfScreen, pad, gap)

    private fun arcBetween(spacing: DialSpacing, i: Int) =
        Math.toRadians((spacing.stops[i] - spacing.stops[i - 1]).toDouble()).toFloat() * radius

    @Test
    fun neighboursNeverTouchWhicheverIsFocused() {
        val texts = listOf(96f, 120f, 40f, 160f)
        val spacing = fit(texts, halfScreen = 1_000f)
        assertEquals(1f, spacing.type)
        for (i in 1 until texts.size) {
            val a = texts[i - 1] + 2 * pad
            val b = texts[i] + 2 * pad
            val needed = max(1.1f * a + 0.9f * b, 1.1f * b + 0.9f * a) / 2f
            assertTrue("pair $i overlaps", arcBetween(spacing, i) >= needed)
        }
    }

    @Test
    fun narrowScreensShrinkTheTypeUntilTheNeighbourFits() {
        // Adjacent category labels measured on a 320 dp wide display.
        val texts = listOf(92f, 102f)
        val half = 160f - 6f
        val roomy = fit(texts, halfScreen = 1_000f)
        val narrow = fit(texts, halfScreen = half)
        assertTrue(narrow.type < roomy.type)
        assertTrue(narrow.type >= DialSpacing.MIN_TYPE)
        val step = Math.toRadians(narrow.stops[1].toDouble()).toFloat()
        val widest = max(texts[0], texts[1]) * narrow.type + 2 * pad
        val reach = radius * sin(step) + 0.9f * (widest / 2f - pad)
        assertTrue("neighbour reaches $reach of $half", reach <= half)
    }

    @Test
    fun typeNeverDropsBelowTheFloor() {
        val spacing = fit(listOf(400f, 400f), halfScreen = 100f)
        assertEquals(DialSpacing.MIN_TYPE, spacing.type, 0.001f)
    }

    @Test
    fun turningFollowsTheStopsBetweenNames() {
        val spacing = fit(listOf(40f, 200f, 40f), halfScreen = 1_000f)
        assertEquals(0f, spacing.angleAt(0f), 0.001f)
        assertEquals(spacing.stops[1], spacing.angleAt(1f), 0.001f)
        assertEquals((spacing.stops[1] + spacing.stops[2]) / 2f, spacing.angleAt(1.5f), 0.001f)
        assertEquals(spacing.stops[2] - spacing.stops[1], spacing.stepAfter(1.2f), 0.001f)
    }
}
