package io.nekohasekai.sfa.compose.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackdropFramingTest {
    @Test fun floatingPointUndershootCannotCreateAnEmptyCropRange() {
        val horizontal = BackdropFraming.transform(148, 73, 1240f, 500f, BackdropFocus())
        val vertical = BackdropFraming.transform(73, 148, 500f, 1240f, BackdropFocus())
        assertEquals(0f, horizontal.x, 0.001f)
        assertEquals(0f, vertical.y, 0.001f)
        assertTrue(horizontal.width >= 1239.999f)
        assertTrue(vertical.height >= 1239.999f)
    }

    @Test fun rightHandPersonMovesToHorizontalCentreWithoutZoom() {
        val crop = BackdropFraming.transform(3840, 2160, 1240f, 2772f, BackdropFocus(0.74f, 0.61f))
        assertEquals(620f, crop.x + 3840 * 0.74f * crop.scale, 0.01f)
        assertEquals(2772f / 2160, crop.scale, 0.001f)
        assertTrue(crop.x <= 0 && crop.x + crop.width >= 1240)
        assertTrue(crop.y <= 0 && crop.y + crop.height >= 2772)
    }

    @Test fun nearPortraitKeepsOriginalPhotoClearThroughTheBottomControls() {
        val crop = BackdropFraming.transform(941, 1672, 1240f, 2772f, BackdropFocus(0.56f, 0.71f))
        assertEquals(2772f / 1672, crop.scale, 0.001f)
        assertEquals(0f, crop.y, 0.001f)
        assertTrue(crop.y + crop.height >= 2771.999f)
        // The former shifted composition exposed its blurred extension here.
        val sourceY = (2600f - crop.y) / crop.scale
        assertTrue(sourceY in 0f..1672f)
    }

    @Test fun extremeFocalPointsNeverExposeAnEmptyPhotoEdge() {
        for ((imageWidth, imageHeight) in listOf(941 to 1672, 4000 to 2250, 1200 to 3600)) {
            for (focusY in listOf(-1f, 0f, 0.5f, 0.71f, 1f, 2f)) {
                val crop = BackdropFraming.transform(imageWidth, imageHeight, 1240f, 2772f, BackdropFocus(0.56f, focusY))
                assertTrue(crop.x <= 0f && crop.x + crop.width >= 1239.999f)
                assertTrue(crop.y <= 0f && crop.y + crop.height >= 2771.999f)
            }
        }
    }

    @Test fun wideWindowKeepsImageCoveredAtItsLegalCropLimits() {
        val crop = BackdropFraming.transform(1100, 619, 2772f, 1240f, BackdropFocus(0.99f, 0.99f))
        assertTrue(crop.x <= 0 && crop.x + crop.width >= 2772)
        assertTrue(crop.y <= 0 && crop.y + crop.height >= 1240)
    }
}
