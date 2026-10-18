package io.nekohasekai.sfa.compose.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropBusynessTest {
    private fun map(width: Int, height: Int, value: (Int, Int) -> Float) =
        FloatArray(width * height) { value(it % width, it / width) }

    @Test
    fun flatAreaIsCalm() {
        val flat = map(20, 20) { _, _ -> 0.4f }
        assertEquals(0f, BackdropBusyness.score(flat, 20, 0, 0, 19, 19), 0f)
    }

    @Test
    fun checkerboardIsBusy() {
        val checker = map(20, 20) { x, y -> if ((x + y) % 2 == 0) 0.05f else 0.9f }
        assertEquals(1f, BackdropBusyness.score(checker, 20, 0, 0, 19, 19), 0f)
    }

    @Test
    fun gentleGradientStaysCalmAndOnlyTheAreaCounts() {
        val sky = map(40, 20) { x, _ -> 0.2f + 0.6f * x / 39f }
        assertTrue(BackdropBusyness.score(sky, 40, 0, 0, 39, 19) < 0.05f)
        // Busy detail outside the measured area is ignored.
        val half = map(40, 20) { x, y -> if (x < 20) 0.5f else if ((x + y) % 2 == 0) 0.05f else 0.9f }
        assertEquals(0f, BackdropBusyness.score(half, 40, 0, 0, 18, 19), 0f)
    }

    @Test
    fun lightnessIsPerceptual() {
        assertEquals(0f, BackdropBusyness.lightness(0f), 1e-4f)
        assertEquals(1f, BackdropBusyness.lightness(1f), 1e-4f)
        // Mid grey (18 % luminance) sits near half lightness.
        assertEquals(0.495f, BackdropBusyness.lightness(0.18f), 0.01f)
    }
}
