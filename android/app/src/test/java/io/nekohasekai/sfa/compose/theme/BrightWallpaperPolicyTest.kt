package io.nekohasekai.sfa.compose.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightWallpaperPolicyTest {
    @Test fun oneBrightPatchDoesNotReplaceDarkPhotoLayout() {
        assertFalse(BrightWallpaperPolicy.shouldCompose(List(95) { 0.04f } + List(5) { 0.95f }))
        assertFalse(BrightWallpaperPolicy.shouldCompose(emptyList()))
    }

    @Test fun broadHighlightsComposeReadingAreaDespiteDarkHair() {
        assertTrue(BrightWallpaperPolicy.shouldCompose(List(30) { 0.75f } + List(70) { 0.12f }))
        assertFalse(BrightWallpaperPolicy.shouldCompose(List(100) { 0.24f }))
    }

    @Test fun pixelOrderCannotMakeTypographyChange() {
        val pixels = List(35) { 0.65f } + List(65) { 0.13f }
        assertEquals(BrightWallpaperPolicy.shouldCompose(pixels), BrightWallpaperPolicy.shouldCompose(pixels.reversed()))
    }

    @Test fun shortScreensAlwaysKeepAUsableScrollableReadingArea() {
        assertEquals(0f, BrightWallpaperPolicy.portraitSpace(300f, 64f, 80f), 0f)
        val inset = BrightWallpaperPolicy.portraitSpace(700f, 64f, 80f)
        assertEquals((700f - 64f - 80f) * 0.50f, inset, 0.001f)
        assertTrue(700f - 64f - 80f - inset >= 240f)
    }

    @Test fun plainReadingInkAndAccentHaveContrastOverDarkestSupportedBackground() {
        val background = KninePalette.composite(KninePalette.LIGHT_READING_SURFACE, 0xFF000000.toInt(), 0.68f)
        for (foreground in listOf(KninePalette.DARK_INK, KninePalette.DARK_ACCENT)) {
            assertTrue(KninePalette.contrast(foreground, KninePalette.luminance(background)) >= 4.5f)
        }
    }
}
