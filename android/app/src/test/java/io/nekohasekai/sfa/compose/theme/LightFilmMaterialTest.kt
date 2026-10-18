package io.nekohasekai.sfa.compose.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightFilmMaterialTest {
    @Test fun normalAccentAndErrorRemainReadableOverWorstPhotoDetail() {
        val backgrounds = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF003C98.toInt(), 0xFFEA5B92.toInt())
        val foregrounds = listOf(KninePalette.DARK_INK, KninePalette.DARK_ACCENT, KninePalette.DARK_ERROR)
        for (a in listOf(LightFilmMaterial.START_ALPHA, LightFilmMaterial.endAlpha)) {
            for (photo in backgrounds) for (ink in foregrounds) {
                val underneath = KninePalette.composite(KninePalette.LIGHT_READING_SURFACE, photo, a)
                assertTrue(KninePalette.contrast(ink, KninePalette.luminance(underneath)) >= 5f)
            }
        }
        assertTrue(LightFilmMaterial.endAlpha < 0.70f)
    }
    @Test fun unsafeSemanticMidtoneFallsBackOnClearFilm() {
        val bright = WallpaperTheme(lightInk = false, brightComposition = true)
        assertEquals(KninePalette.DARK_INK, KninePalette.stableForeground(0xFFBA1A1A.toInt(), bright, 5f))
        assertEquals(KninePalette.DARK_ERROR, KninePalette.stableForeground(KninePalette.DARK_ERROR, bright, 5f))
    }
}
