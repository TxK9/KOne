package io.nekohasekai.sfa.compose.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrightSceneGeometryTest {
    @Test fun brightPhotoEndsAtTheMeasuredFirstReadingRow() {
        val crop = BrightSceneGeometry.photoTransform(4000, 2250, 1240f, 2772f, 1252f, BackdropFocus(0.535f, 0.58f))
        assertEquals(1252f, crop.height, 0.001f)
        assertEquals(0f, crop.y, 0.001f)
        assertEquals(620f, crop.x + 4000 * 0.535f * crop.scale, 0.001f)
    }

    @Test fun cherryBlossomHandsAndFaceFitAboveControlsAtPhoneGeometry() {
        val crop = BrightSceneGeometry.photoTransform(4000, 2250, 1240f, 2772f, 1252f, BackdropFocus(0.535f, 0.58f))
        // Source-image landmarks checked against the original owner2 image.
        assertTrue(crop.x + crop.width * 0.27f >= 0f)
        assertTrue(crop.x + crop.width * 0.73f <= 1240f)
        assertTrue(crop.y + crop.height * 0.94f < 1252f)
        assertTrue(crop.y + crop.height * 0.48f < 1252f - crop.height * 0.1f)
    }

    @Test fun anUnmeasuredSceneHasFiniteSafeInitialGeometry() {
        assertEquals(2772f * 0.44f, BrightSceneGeometry.photoHeight(2772f, null), 0.001f)
        assertEquals(1f, BrightSceneGeometry.photoHeight(2772f, -20f), 0f)
        assertEquals(2772f, BrightSceneGeometry.photoHeight(2772f, 3000f), 0f)
    }
}
