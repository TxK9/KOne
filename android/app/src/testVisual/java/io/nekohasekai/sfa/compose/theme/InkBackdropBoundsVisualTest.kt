package io.nekohasekai.sfa.compose.theme

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h780dp-xhdpi")
class InkBackdropBoundsVisualTest {
    @get:Rule val compose = createComposeRule()

    private data class Frame(
        val width: Dp = 320.dp,
        val height: Dp = 240.dp,
        val scale: Float = 1.12f,
        val left: Float = -.06f,
        val motion: Float = .012f,
        val phase: Float = 0f,
    )

    @Test fun paintingAndMistCoverBothEdgesThroughoutDrift() {
        val frame = mutableStateOf(Frame())
        val pigment = Color(0xFFB8C6CC)
        compose.setContent {
            val f = frame.value
            Box(Modifier.size(f.width, f.height).clipToBounds().background(Color.Black).testTag("viewport")) {
                Box(
                    Modifier.offset(x = f.width * f.left)
                        .inkLayerSize(f.width * f.scale, f.height)
                        .graphicsLayer { translationX = f.phase * size.width * f.motion }
                        .background(pigment),
                )
            }
        }
        for (width in listOf(320.dp, 360.dp)) {
            for ((scale, left, motion) in listOf(Triple(1.12f, -.06f, .012f), Triple(1.6f, -.3f, .11f))) {
                for (phase in listOf(-1f, 0f, 1f)) {
                    compose.runOnIdle { frame.value = Frame(width, if (width == 320.dp) 240.dp else 120.dp, scale, left, motion, phase) }
                    compose.waitForIdle()
                    val image = compose.onNodeWithTag("viewport").captureToImage().asAndroidBitmap()
                    val y = image.height / 2
                    val dir = File(System.getProperty("knine.previewDir"), "ink-edge").apply { mkdirs() }
                    File(dir, "edge-${width.value.toInt()}-${scale}-${phase}.png").outputStream().use {
                        image.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                    assertEquals("left edge uncovered: $scale / $phase", 0xFFB8C6CC.toInt(), image.getPixel(1, y))
                    assertEquals("right edge uncovered: $scale / $phase", 0xFFB8C6CC.toInt(), image.getPixel(image.width - 2, y))
                }
            }
        }
    }
}
