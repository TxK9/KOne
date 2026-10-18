package cc.txwd.kone.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
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
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import cc.txwd.kone.ink.InkGallery
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * Whether Windows draws this window in software (a virtual machine, a remote desktop), where each
 * frame of a scroll costs a good part of a core: the scrolls then step a few times a second, and
 * the cities' water and clouds rest.
 */
val LocalSoftware = compositionLocalOf { false }

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
    // The scroll moves a few pixels a second; twenty steps a second keep it smooth for little cost.
    val software = LocalSoftware.current
    val clock = rememberClock(moving) { if (software) 4 else 20 }
    val paintings = images ?: return
    // Each painting scaled to the panel once, so a frame copies it rather than scaling it again.
    val sized = remember(paintings) { arrayOfNulls<ImageBitmap>(paintings.size) }
    Canvas(modifier.fillMaxSize()) {
        drawStrip(paintings.size, panAt(clock.value), shown.value) { index, x, w, h ->
            val image = sized[index]?.takeIf { it.width == w && it.height == h } ?: scaled(paintings[index], w, h).also { sized[index] = it }
            drawImage(image, topLeft = Offset(x.toFloat(), 0f))
        }
    }
}

/** [painting] at [w] by [h], scaled with care once. */
fun scaled(painting: ImageBitmap, w: Int, h: Int): ImageBitmap {
    val image = ImageBitmap(w, h)
    Canvas(image).drawImageRect(painting, IntOffset.Zero, IntSize(painting.width, painting.height), IntOffset.Zero, IntSize(w, h), Paint().apply { filterQuality = FilterQuality.High })
    return image
}

/**
 * Seconds of motion so far, published [perSecond] times a second (asked each time) while
 * [moving]. A stall (the window hidden, the machine asleep) counts as a step and a tenth of a
 * second at most, so nothing jumps.
 *
 * Between steps the clock sleeps rather than waiting on every frame: a coroutine waiting for a
 * frame makes the window draw one, all of it, at the screen's rate, so a clock that only needed
 * four steps a second kept a software-drawn window redrawing sixty times a second.
 */
@Composable
fun rememberClock(moving: Boolean, perSecond: () -> Int): State<Double> {
    val clock = remember { mutableDoubleStateOf(0.0) }
    if (moving) {
        LaunchedEffect(Unit) {
            var last = withFrameNanos { it }
            var time = clock.doubleValue
            while (true) {
                val step = 1.0 / perSecond().coerceAtLeast(1)
                delay((step * 1000).toLong())
                val now = withFrameNanos { it }
                time += ((now - last) / 1e9).coerceIn(0.0, step + 0.1)
                last = now
                clock.doubleValue = time
            }
        }
    }
    return clock
}

/** What drawing a frame costs on this computer, in milliseconds, smoothed over the last few seconds. */
class FrameCost {
    var millis = 0.0
        private set

    fun add(nanos: Long) {
        val now = nanos / 1e6
        millis = if (millis == 0.0) now else millis * .95 + now * .05
    }

    /** As many frames a second, up to [most], as keep drawing to about a fifth of the time; never fewer than [least]. */
    fun perSecond(most: Int, least: Int): Int = if (millis <= 0) most else (1000 / (millis * 5)).toInt().coerceIn(least, most)
}

/** Where along its way the scroll is after [seconds], 0 to 1 and back, eased at the ends as the preview's CSS does it. */
fun panAt(seconds: Double): Float {
    val t = (seconds * 1000).toLong() % (2 * PAN_MILLIS)
    val leg = if (t < PAN_MILLIS) t else 2 * PAN_MILLIS - t
    return CssEaseInOut.transform(leg / PAN_MILLIS.toFloat())
}

/**
 * [count] paintings side by side across the height, [pan] of the way along, each [panel] drawn
 * at x (whole pixels) w wide and h high, and faded into its neighbours where they share paper.
 * The two ends fade into the page too unless [closedEnds] and the strip spans the page: a city
 * at night on light paper would otherwise show a pale band at its edge.
 *
 * The paintings are opaque, so a painting laid over its left neighbour, rising across the stretch
 * they share, is the same cross-dissolve as adding the two: each is drawn as it is, and only the
 * shared stretch goes through a layer, to be faded.
 */
fun DrawScope.drawStrip(count: Int, pan: Float, alpha: Float, closedEnds: Boolean = false, panel: DrawScope.(index: Int, x: Int, w: Int, h: Int) -> Unit) {
    // In whole pixels: a painting or its fade starting part way into a pixel left that column
    // half masked, and the two neighbours added up to more than the paper there.
    val h = size.height.roundToInt()
    val w = (h * 0.75f).roundToInt()
    val overlap = (w * InkGallery.SHARED).roundToInt()
    val step = w - overlap
    val strip = count * w - (count - 1) * overlap
    val left = (pan * (size.width - strip)).roundToInt()
    val rise = ramp(rising = true)
    val fall = ramp(rising = false)
    val openEnds = !(closedEnds && strip >= size.width)
    val canvas = drawContext.canvas
    // Only while the scroll fades in does the whole of it need a layer of its own.
    if (alpha < 1f) canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint().apply { this.alpha = alpha })
    for (index in 0 until count) {
        val x = left + index * step
        if (x + w < 0 || x > size.width) continue
        val fadeIn = index > 0 || openEnds
        val fadeOut = index == count - 1 && openEnds
        val from = x + if (fadeIn) overlap else 0
        val to = x + w - if (fadeOut) overlap else 0
        canvas.save()
        canvas.clipRect(from.toFloat(), 0f, to.toFloat(), h.toFloat())
        panel(index, x, w, h)
        canvas.restore()
        if (fadeIn) fadedStretch(x, overlap, h, rise) { panel(index, x, w, h) }
        if (fadeOut) fadedStretch(x + w - overlap, overlap, h, fall) { panel(index, x, w, h) }
    }
    if (alpha < 1f) canvas.restore()
}

/** The part of a painting from [at], [width] across, drawn by [paint] and faded by [ramp] over what is already there. */
private inline fun DrawScope.fadedStretch(at: Int, width: Int, h: Int, ramp: Array<Pair<Float, Color>>, paint: DrawScope.() -> Unit) {
    val canvas = drawContext.canvas
    val stretch = Rect(at.toFloat(), 0f, (at + width).toFloat(), h.toFloat())
    canvas.saveLayer(stretch, Paint())
    canvas.clipRect(stretch)
    paint()
    // Eased across the whole stretch the two paintings share, so each melts into the other; a
    // quicker fade showed the mist of one painting stopping short against the next one's paper.
    drawRect(Brush.horizontalGradient(*ramp, startX = stretch.left, endX = stretch.right), stretch.topLeft, stretch.size, blendMode = BlendMode.DstIn)
    canvas.restore()
}

/** Smoothstep from clear to whole (or whole to clear) across a shared stretch; two neighbours' ramps add up to whole. */
private fun ramp(rising: Boolean): Array<Pair<Float, Color>> {
    val ramp = listOf(0f to 0f, 0.25f to 0.156f, 0.5f to 0.5f, 0.75f to 0.844f, 1f to 1f)
    return ramp.map { (t, a) -> t to Color.Black.copy(alpha = if (rising) a else 1 - a) }.toTypedArray()
}
