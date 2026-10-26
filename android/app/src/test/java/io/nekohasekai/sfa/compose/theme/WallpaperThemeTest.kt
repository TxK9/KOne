package io.nekohasekai.sfa.compose.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperThemeTest {
    @Test fun cityVeilKeepsBothInkAndAccentReadableEvenOverWhiteArtwork() {
        val brightest = KninePalette.composite(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), CITY_WALLPAPER_VEIL_ALPHA)
        val background = KninePalette.luminance(brightest)
        assertTrue(KninePalette.contrast(KninePalette.LIGHT_INK, background) >= 5f)
        assertTrue(KninePalette.contrast(KninePalette.LIGHT_ACCENT, background) >= 5f)
    }
    @Test fun cityShowcaseAlternatesHomeAndAbroadWithoutRepeats() {
        val cities = CityShowcase.order()
        assertTrue(cities.first() == "original" && cities[1] == "tokyo-bay")
        assertTrue(cities.size == cities.toSet().size)
        assertTrue(cities.size >= 60)
    }
    @Test fun wallpaperDistributionSelectsOneStablePalette() {
        assertFalse(KninePalette.wallpaperTheme(List(98) { 0xFFF3EFFF.toInt() } + List(2) { 0xFF16131E.toInt() }).lightInk)
        assertTrue(KninePalette.wallpaperTheme(List(98) { 0xFF16131E.toInt() } + List(2) { 0xFFF3EFFF.toInt() }).lightInk)
    }

    @Test fun pixelOrderingDoesNotChangeTheme() {
        val samples = listOf(0xFF7E7298.toInt(), 0xFFEDD9EF.toInt(), 0xFF1C172B.toInt(), 0xFFFCF8FF.toInt())
        assertEquals(KninePalette.wallpaperTheme(samples), KninePalette.wallpaperTheme(samples.reversed()))
    }

    @Test fun transparentTextRemainsTransparentWhereContrastIsEnough() {
        val theme = WallpaperTheme(false)
        val support = KninePalette.readingSupport(theme.ink, listOf(0xFFF8F3FF.toInt()), theme.readingSurface, 4.5f)
        assertEquals(0f, support.alpha, 0f)
        assertTrue(support.hasSamples)
        assertTrue(support.sampledMinimumContrast >= 4.5f)
    }

    @Test fun oneBadPatchCannotBeHiddenByAverageOrFifthPercentile() {
        val theme = WallpaperTheme(true)
        val samples = List(100) { 0xFF181320.toInt() } + 0xFFFFFFFF.toInt()
        val support = KninePalette.readingSupport(theme.ink, samples, theme.readingSurface, 4.5f)
        assertTrue(support.alpha > 0.5f)
        assertEquals(101, support.sampledCount)
        assertTrue(support.sampledMinimumContrast >= 4.5f)
    }

    @Test fun bothThemesAndAccentsMeetEveryReducedImageSampleAfterSupport() {
        val samples = listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF7B7B7B.toInt(), 0xFFDDBCDF.toInt(), 0xFF276F5B.toInt())
        for (light in listOf(true, false)) {
            val theme = WallpaperTheme(light)
            for (foreground in listOf(theme.ink, theme.accent)) {
                val support = KninePalette.readingSupport(foreground, samples, theme.readingSurface, 4.5f)
                for (sample in samples) assertTrue(KninePalette.contrast(foreground, KninePalette.luminance(KninePalette.composite(theme.readingSurface, sample, support.alpha))) >= 4.5f)
            }
        }
    }

    @Test fun iconTargetNeedsNoMoreUnderlayThanSmallText() {
        val theme = WallpaperTheme(false)
        val samples = listOf(0xFF252036.toInt())
        val icon = KninePalette.readingSupport(theme.ink, samples, theme.readingSurface, 3f)
        val text = KninePalette.readingSupport(theme.ink, samples, theme.readingSurface, 4.5f)
        assertTrue(icon.alpha <= text.alpha)
        assertTrue(icon.sampledMinimumContrast >= 3f)
        assertTrue(text.sampledMinimumContrast >= 4.5f)
    }

    @Test fun blendUsesRenderedSrgbChannelsInsteadOfLinearLuminanceAveraging() {
        assertEquals(0xFF808080.toInt(), KninePalette.composite(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }

    @Test fun unloadedPhotoUsesSafeUnderlayWithoutPretendingItWasMeasured() {
        val theme = WallpaperTheme(true)
        val support = KninePalette.readingSupport(theme.ink, emptyList(), theme.readingSurface, 4.5f)
        assertEquals(1f, support.alpha, 0f)
        assertFalse(support.hasSamples)
        assertEquals(0, support.sampledCount)
    }

    @Test fun unsafeSemanticMidtonesFallBackToSettledBodyColor() {
        assertEquals(KninePalette.DARK_INK, KninePalette.stableForeground(0xFF777777.toInt(), WallpaperTheme(false), 4.5f))
        assertEquals(KninePalette.LIGHT_INK, KninePalette.stableForeground(0xFF777777.toInt(), WallpaperTheme(true), 4.5f))
        assertEquals(KninePalette.DARK_ACCENT, KninePalette.stableForeground(KninePalette.DARK_ACCENT, WallpaperTheme(false), 4.5f))
        assertEquals(KninePalette.LIGHT_ACCENT, KninePalette.stableForeground(KninePalette.LIGHT_ACCENT, WallpaperTheme(true), 4.5f))
    }
}
