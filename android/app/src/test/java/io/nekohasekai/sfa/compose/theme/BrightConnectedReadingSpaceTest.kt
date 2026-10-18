package io.nekohasekai.sfa.compose.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightConnectedReadingSpaceTest {
    @Test fun runningDashboardGetsMoreRoomAfterTheConnectionBarIsMeasured() {
        // A 900 dp portrait phone: the 80 dp connection bar is removed by the
        // parent; the frame accounts for the toolbar and four-item bottom bar.
        val frameHeight = 900f - 80f
        val usable = frameHeight - 88f - 100f
        val stoppedPhoto = BrightWallpaperPolicy.portraitSpace(frameHeight, 88f, 100f)
        val runningPhoto = BrightWallpaperPolicy.portraitSpace(frameHeight, 88f, 100f, serviceRunning = true)
        assertTrue(usable - runningPhoto >= 320f)
        assertTrue(runningPhoto < stoppedPhoto)
        assertTrue((usable - runningPhoto) / (usable - stoppedPhoto) >= 1.25f)
    }

    @Test fun tallConnectionBarDoesNotConsumeTheMinimumReadingFrame() {
        val frameHeight = 800f - 128f
        val usable = frameHeight - 88f - 100f
        val photo = BrightWallpaperPolicy.portraitSpace(frameHeight, 88f, 100f, serviceRunning = true)
        assertEquals(320f, usable - photo, 0.001f)
    }

    @Test fun veryShortViewportKeepsItsAvailableSpaceScrollable() {
        assertEquals(0f, BrightWallpaperPolicy.portraitSpace(300f, 64f, 80f, serviceRunning = true), 0f)
        assertEquals(0f, BrightWallpaperPolicy.portraitSpace(40f, 64f, 80f, serviceRunning = true), 0f)
    }

    @Test fun runningPhotoKeepsKnownCherryBlossomFaceAndHandsAboveTheReadingSeam() {
        // Same landscape source and landmarks as BrightSceneGeometryTest.
        val density = 1240f / 400f
        val frameHeight = 2772f / density - 80f
        val readingTop = (88f + BrightWallpaperPolicy.portraitSpace(
            frameHeight, 88f, 100f, serviceRunning = true,
        )) * density
        val crop = BrightSceneGeometry.photoTransform(
            4000, 2250, 1240f, 2772f, readingTop, BackdropFocus(0.535f, 0.58f),
        )
        assertTrue(crop.x + crop.width * 0.27f >= 0f)
        assertTrue(crop.x + crop.width * 0.73f <= 1240f)
        assertEquals(0f, crop.y, 0.001f)
        assertTrue(crop.y + crop.height * 0.94f < readingTop)
        assertTrue(crop.y + crop.height * 0.48f < readingTop - crop.height * 0.10f)
    }
}
