package io.nekohasekai.sfa.compose.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InkStabilityTest {
    @Test fun oppositeInkModesNeverInterpolateThroughGrey() {
        assertFalse(KninePalette.canTweenInk(KninePalette.DARK_INK, KninePalette.LIGHT_INK))
        assertFalse(KninePalette.canTweenInk(KninePalette.LIGHT_INK, KninePalette.DARK_ACCENT))
    }

    @Test fun samePolarityAccentsRetainTheirGentleTransition() {
        assertTrue(KninePalette.canTweenInk(KninePalette.LIGHT_INK, KninePalette.LIGHT_ACCENT))
        assertTrue(KninePalette.canTweenInk(KninePalette.DARK_INK, KninePalette.DARK_ACCENT))
    }

    @Test fun smallContrastChangesRetainBothEstablishedModes() {
        assertTrue(KninePalette.prefersLight(4f, 4.3f, true))
        assertFalse(KninePalette.prefersLight(4.3f, 4f, false))
    }

    @Test fun substantialBackgroundChangeSwitchesMode() {
        assertFalse(KninePalette.prefersLight(3f, 5f, true))
        assertTrue(KninePalette.prefersLight(5f, 3f, false))
    }

    @Test fun repeatedBoundaryJitterDoesNotAlternateInk() {
        var light = true
        repeat(100) { index ->
            light = KninePalette.prefersLight(4f, if (index % 2 == 0) 4.1f else 3.9f, light)
            assertTrue(light)
        }
    }
}
