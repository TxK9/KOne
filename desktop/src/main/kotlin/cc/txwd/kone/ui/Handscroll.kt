package cc.txwd.kone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import cc.txwd.kone.ink.InkGallery
import kotlin.math.roundToInt

/** How long the handscroll takes to unroll one way, as in the preview (CSS: 150s ease-in-out, alternate). */
private const val PAN_MILLIS = 150_000L
private val CssEaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

/**
 * The handscroll: the four American scenes side by side, each fading into the next where they
 * overlap, panning slowly one way and back. The paintings are painted once (see [InkGallery])
 * and fade in when ready; the page's paper shows until then.
 */
@Composable
fun Handscroll(modifier: Modifier = Modifier, moving: Boolean = true) {
    val dark = LocalInk.current.dark
    var images by remember(dark) { mutableStateOf(InkGallery.readyScroll(dark)?.map { it.toComposeImageBitmap() }) }
    val shown = remember(dark) { Animatable(if (images != null) 1f else 0f) }
    LaunchedEffect(dark) {
        if (images == null) {
            images = InkGallery.scroll(dark).map { it.toComposeImageBitmap() }
            shown.animateTo(1f, tween(900))
        }
    }
    var pan by remember { mutableFloatStateOf(0f) }
    if (moving) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it }
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                // The scroll moves a few pixels a second; twenty steps a second keep it smooth for little cost.
                if (now - last < 50_000_000L) continue
                last = now
                val t = ((now - start) / 1_000_000L) % (2 * PAN_MILLIS)
                val leg = if (t < PAN_MILLIS) t else 2 * PAN_MILLIS - t
                pan = CssEaseInOut.transform(leg / PAN_MILLIS.toFloat())
            }
        }
    }
    val paintings = images ?: return
    Canvas(modifier.fillMaxSize()) {
        // In whole pixels: a painting or its fade starting part way into a pixel left that column
        // half masked, and the two neighbours added up to more than the paper there.
        val h = size.height.roundToInt()
        val w = (h * 0.75f).roundToInt()
        val overlap = (w * InkGallery.SHARED).roundToInt()
        val step = w - overlap
        val strip = 4 * w - 3 * overlap
        val left = (pan * (size.width - strip)).roundToInt()
        val ramps = edges(overlap / w.toFloat())
        // Added, not laid over each other: where two paintings share paper, each fades as the
        // other rises, a true cross-dissolve, and neither covers the other's mist.
        drawContext.canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { alpha = shown.value })
        paintings.forEachIndexed { index, image ->
            val x = left + index * step
            if (x + w < 0 || x > size.width) return@forEachIndexed
            drawContext.canvas.saveLayer(Rect(x.toFloat(), 0f, (x + w).toFloat(), h.toFloat()), Paint().apply { blendMode = BlendMode.Plus })
            drawImage(image, dstOffset = IntOffset(x, 0), dstSize = IntSize(w, h), filterQuality = FilterQuality.Medium)
            // Each painting fades out at both ends, eased across the whole stretch it shares with
            // its neighbour, so it melts into it; a quicker fade showed the mist of one painting
            // stopping short against the next one's paper.
            drawRect(
                Brush.horizontalGradient(*ramps, startX = x.toFloat(), endX = (x + w).toFloat()),
                topLeft = Offset(x.toFloat(), 0f), size = Size(w.toFloat(), h.toFloat()), blendMode = BlendMode.DstIn,
            )
            drawContext.canvas.restore()
        }
        drawContext.canvas.restore()
    }
}

/** Smoothstep from clear to whole over the [shared] fraction at each end of a painting; two neighbours' ramps add up to whole. */
private fun edges(shared: Float): Array<Pair<Float, Color>> {
    val ramp = listOf(0f to 0f, 0.25f to 0.156f, 0.5f to 0.5f, 0.75f to 0.844f, 1f to 1f)
    return (ramp.map { (t, a) -> t * shared to Color.Black.copy(alpha = a) } +
        ramp.reversed().map { (t, a) -> 1f - t * shared to Color.Black.copy(alpha = a) }).toTypedArray()
}
