package io.nekohasekai.sfa.compose.theme

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Design-only material candidates; production UI is not changed. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
class GlassStudyTest {
    @get:Rule val compose = createComposeRule()
    @Test fun appleFrost() = render("apple-frost", 3)
    @Test fun lightFilm() = render("light-film", 0)
    @Test fun gradientFrost() = render("gradient-frost", 1)
    @Test fun localFloating() = render("local-floating", 2)
    private fun render(name: String, variant: Int) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.15f)) {
                MaterialTheme(colorScheme = backdropColorScheme(false), typography = Typography) {
                    CompositionLocalProvider(LocalContentColor provides Color(KninePalette.DARK_INK)) { StudyScene(variant) }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Example remote management server").assertIsDisplayed()
        compose.onNodeWithText("Disconnect").assertIsDisplayed()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val out = File(File(System.getProperty("knine.previewDir") ?: "build/visual-previews", "glass-study"), "$name.png")
        requireNotNull(out.parentFile).mkdirs()
        out.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}

@Composable
private fun StudyScene(variant: Int) {
    val painter = painterResource(R.drawable.owner_wallpaper_2)
    var rootBounds by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootBounds = it.boundsInWindow() }) {
        Canvas(Modifier.matchParentSize()) {
            val crop = BackdropFraming.transform(painter.intrinsicSize.width.toInt(), painter.intrinsicSize.height.toInt(), size.width, size.height, BackdropFocus(0.535f, 0.58f))
            translate(crop.x, crop.y) { with(painter) { draw(Size(crop.width, crop.height)) } }
        }
        if (variant < 2 || variant == 3) {
            StudyMaterial(
                painter, rootBounds, variant,
                Modifier.align(if (variant == 0) Alignment.TopCenter else Alignment.BottomCenter)
                    .padding(horizontal = 18.dp, vertical = 28.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Example remote management server", style = MaterialTheme.typography.titleMedium)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("128", style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
                            Text("Connection", style = MaterialTheme.typography.labelMedium)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("12", style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
                            Text("Groups", style = MaterialTheme.typography.labelMedium)
                        }
                        TextButton(onClick = {}) {
                            Icon(Icons.Default.LinkOff, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Disconnect", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        } else {
            Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 18.dp, vertical = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StudyMaterial(painter, rootBounds, 2, Modifier.fillMaxWidth()) {
                    Text("Example remote management server", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(18.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    StudyMaterial(painter, rootBounds, 2, Modifier.weight(1f), radius = 22) {
                        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            Text("128 connections", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
                            Text("12 groups", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"))
                        }
                    }
                    StudyMaterial(painter, rootBounds, 2, radius = 22) {
                        TextButton(onClick = {}, modifier = Modifier.heightIn(min = 48.dp)) {
                            Icon(Icons.Default.LinkOff, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Disconnect", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StudyMaterial(painter: Painter, root: Rect, variant: Int, modifier: Modifier = Modifier, radius: Int = 26, content: @Composable () -> Unit) {
    var bounds by remember { mutableStateOf(Rect.Zero) }
    val shape = RoundedCornerShape(radius.dp)
    val frost = when (variant) { 1 -> 10.dp; 3 -> 22.dp; else -> 0.dp }
    Box(modifier.shadow(if (variant == 0) 3.dp else 6.dp, shape, clip = false).clip(shape).onGloballyPositioned { bounds = it.boundsInWindow() }, propagateMinConstraints = true) {
        if (variant == 1 || variant == 3) Canvas(Modifier.matchParentSize().blur(frost)) {
            if (root.width > 0 && root.height > 0) {
                val crop = BackdropFraming.transform(painter.intrinsicSize.width.toInt(), painter.intrinsicSize.height.toInt(), root.width, root.height, BackdropFocus(0.535f, 0.58f))
                translate(crop.x + root.left - bounds.left, crop.y + root.top - bounds.top) {
                    with(painter) {
                        draw(Size(crop.width, crop.height), colorFilter = if (variant == 3)
                            ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.35f) }) else null)
                    }
                }
            }
        }
        val tone = Color(if (variant == 3) 0xFFF9FBFC else 0xFFF6F5EE)
        val brush = when (variant) {
            0 -> Brush.verticalGradient(listOf(tone.copy(alpha = 0.70f), tone.copy(alpha = 0.58f)))
            1 -> Brush.verticalGradient(listOf(tone.copy(alpha = 0.72f), tone.copy(alpha = 0.56f)))
            3 -> Brush.verticalGradient(listOf(tone.copy(alpha = 0.62f), tone.copy(alpha = 0.54f)))
            else -> Brush.verticalGradient(listOf(tone.copy(alpha = 0.82f), tone.copy(alpha = 0.76f)))
        }
        Box(Modifier.matchParentSize().background(brush).border(BorderStroke(if (variant == 3) 0.5.dp else 0.65.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.8f), Color.White.copy(alpha = 0.18f)))), shape))
        content()
    }
}
