package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.compose.theme.BackdropText
import io.nekohasekai.sfa.compose.theme.LocalBackdrop
import io.nekohasekai.sfa.compose.theme.WallpaperCatalog
import io.nekohasekai.sfa.compose.theme.WallpaperMode
import io.nekohasekai.sfa.compose.theme.cityTone
import io.nekohasekai.sfa.compose.theme.knineSurface

/** Status green that keeps contrast with the current ink direction. */
internal fun okColor(ink: Color): Color = if (ink.luminance() > 0.5f) Color(0xFF7FD4A2) else Color(0xFF2F8F5B)

/** Material's emphasized deceleration, as KnineMotion arrives: quick to come, gentle to land, never past its place. */
internal val KnineArrive = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * The one tone laid under ink. On the city it is the city's own shade, night blue under light ink
 * and paper under dark, as CityCanvas gathers it behind every line; elsewhere the wallpaper's
 * reading surface.
 */
@Composable
internal fun knineTone(): Color {
    val backdrop = LocalBackdrop.current
    return if (backdrop != null && WallpaperCatalog.mode == WallpaperMode.City) Color(cityTone(backdrop.theme.lightInk)) else knineSurface()
}

/**
 * False when the phone's animations are off (animator duration scale 0). Compose already lands
 * finite animations at once then; loops check this and rest instead of breathing.
 */
@Composable
internal fun rememberMotionOn(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) {
        runCatching { android.provider.Settings.Global.getFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f
    }
}

/** Clear glass: a light film and a hairline in the page's own ink, without blur or shadow. */
internal fun Modifier.knineFilm(shape: Shape, alpha: Float, surface: Color, ink: Color): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(surface.copy(alpha = (alpha + 0.06f).coerceAtMost(0.78f)), surface.copy(alpha = alpha))), shape)
    .border(0.7.dp, Brush.linearGradient(listOf(ink.copy(alpha = 0.16f), ink.copy(alpha = 0.04f), ink.copy(alpha = 0.08f))), shape)

/**
 * [style] stepped down by half a point until [text] fits [maxWidth] on one line. The second value
 * is true when even [minSp] does not fit, and the text has to wrap at that size instead.
 */
internal fun fitType(measurer: TextMeasurer, text: String, style: TextStyle, maxWidth: Int, minSp: Float): Pair<TextStyle, Boolean> {
    var current = style
    if (maxWidth == Constraints.Infinity || !style.fontSize.isSp) return current to false
    while (measurer.measure(text, current, maxLines = 1, softWrap = false).size.width > maxWidth) {
        if (current.fontSize.value <= minSp) return current to true
        current = current.copy(fontSize = (current.fontSize.value - 0.5f).sp)
    }
    return current to false
}

/**
 * One line whose font steps down until the whole text fits the width it is given. Past [minSp]
 * it wraps onto up to [lines] lines at that size instead, so nothing is cut off on a narrow phone.
 */
@Composable
internal fun FitText(text: String, style: TextStyle, alignEnd: Boolean = false, minSp: Float = 7f, lines: Int = 1, modifier: Modifier = Modifier.fillMaxWidth()) {
    val measurer = rememberTextMeasurer()
    val alignment = when {
        style.textAlign == TextAlign.Center -> Alignment.Center
        alignEnd -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    BoxWithConstraints(modifier, contentAlignment = alignment) {
        val maxWidth = constraints.maxWidth
        val (fitted, wrap) = remember(text, style, maxWidth) { fitType(measurer, text, style, maxWidth, minSp) }
        BackdropText(text, style = fitted, maxLines = if (wrap) lines else 1, softWrap = wrap && lines > 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The number in a reading such as "12.5 MB/s" or "86 ms", for telling which way it moved. */
internal fun readingValue(text: String): Double? = Regex("""\d+(?:[.,]\d+)?""").find(text)?.value?.replace(',', '.')?.toDoubleOrNull()

/**
 * A value that changes in place, fitted to its width like [FitText]. With [drift] a new value rolls
 * in, up when the number grows and down when it falls; without it the two simply cross-fade, for
 * figures that change every second.
 */
@Composable
internal fun RollingText(text: String, style: TextStyle, modifier: Modifier = Modifier.fillMaxWidth(), drift: Boolean = true, minSp: Float = 9f) {
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val maxWidth = constraints.maxWidth
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                val rising = (readingValue(targetState) ?: 0.0) >= (readingValue(initialState) ?: 0.0)
                if (drift) {
                    (slideInVertically(tween(280, easing = KnineArrive)) { if (rising) it / 2 else -it / 2 } + fadeIn(tween(220, delayMillis = 40))) togetherWith
                        (slideOutVertically(tween(200)) { if (rising) -it / 2 else it / 2 } + fadeOut(tween(140))) using SizeTransform(clip = false)
                } else {
                    fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(160)) using SizeTransform(clip = false)
                }
            },
            label = "rolling",
        ) { shown ->
            val (fitted, wrap) = remember(shown, style, maxWidth) { fitType(measurer, shown, style, maxWidth, minSp) }
            BackdropText(shown, style = fitted, maxLines = if (wrap) 2 else 1, softWrap = wrap, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A check mark that draws itself when [checked] becomes true. */
@Composable
internal fun AnimatedCheck(checked: Boolean, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) {
    val progress by animateFloatAsState(if (checked) 1f else 0f, tween(380, easing = FastOutSlowInEasing), label = "check")
    Canvas(modifier.size(18.dp)) {
        if (progress <= 0f) return@Canvas
        val unit = size.width / 24f
        val path = Path().apply {
            moveTo(5f * unit, 12.5f * unit)
            lineTo(9.2f * unit, 16.7f * unit)
            lineTo(19f * unit, 7f * unit)
        }
        val measure = PathMeasure().apply { setPath(path, false) }
        val segment = Path()
        measure.getSegment(0f, measure.length * progress, segment, true)
        drawPath(segment, color, style = Stroke(width = 2.2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Breathes while connected, blinks while connecting, rests when stopped. */
@Composable
internal fun StatusDot(started: Boolean, busy: Boolean, modifier: Modifier = Modifier) {
    val ink = LocalContentColor.current
    val ok = okColor(ink)
    val moving = rememberMotionOn()
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    // Connecting breathes rather than blinks: slow enough to read as waiting, not as an alarm.
    val breath by transition.animateFloat(0.25f, 0.85f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    Canvas(modifier.size(14.dp)) {
        val r = 3.5.dp.toPx()
        when {
            started -> {
                if (moving) drawCircle(ok.copy(alpha = 0.28f * (1f - pulse)), radius = r + 4.dp.toPx() * pulse)
                drawCircle(ok, radius = r)
            }
            busy -> drawCircle(ink.copy(alpha = if (moving) breath else 0.6f), radius = r)
            else -> drawCircle(ink.copy(alpha = 0.38f), radius = r)
        }
    }
}
