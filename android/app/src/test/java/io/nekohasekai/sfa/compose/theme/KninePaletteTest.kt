package io.nekohasekai.sfa.compose.theme

import org.junit.Assert.assertEquals
import org.junit.Test

class KninePaletteTest {
    @Test fun choosesSoftSageOnDarkAndDeepForestOnLight() {
        assertEquals(KninePalette.LIGHT_ACCENT, KninePalette.accent(true, listOf(0.01f, 0.02f)))
        assertEquals(KninePalette.DARK_ACCENT, KninePalette.accent(false, listOf(0.7f, 0.9f)))
    }

    @Test fun oneLowContrastPatchFallsBackToOrdinaryInk() {
        assertEquals(KninePalette.LIGHT_INK, KninePalette.accent(true, listOf(0.01f, 0.02f, 0.5f)))
        assertEquals(KninePalette.DARK_INK, KninePalette.accent(false, listOf(0.8f, 0.02f)))
    }

    @Test fun unloadedBackdropDoesNotUseUnverifiedAccent() {
        assertEquals(KninePalette.LIGHT_INK, KninePalette.accent(true, emptyList()))
        assertEquals(KninePalette.DARK_INK, KninePalette.accent(false, emptyList()))
    }
}
