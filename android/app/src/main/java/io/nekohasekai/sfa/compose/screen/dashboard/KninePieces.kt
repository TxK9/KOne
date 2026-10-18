package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Status green that keeps contrast with the current ink direction. */
internal fun okColor(ink: Color): Color = if (ink.luminance() > 0.5f) Color(0xFF7FD4A2) else Color(0xFF2F8F5B)

/** Clear glass: a light film and a bright hairline edge, without blur or shadow. */
internal fun Modifier.knineFilm(shape: Shape, alpha: Float, surface: Color, lightInk: Boolean): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(surface.copy(alpha = (alpha + 0.06f).coerceAtMost(0.78f)), surface.copy(alpha = alpha))), shape)
    .border(
        0.7.dp,
        Brush.linearGradient(
            if (lightInk) {
                listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.12f))
            } else {
                listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.3f))
            },
        ),
        shape,
    )

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
    val transition = rememberInfiniteTransition(label = "dot")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing), RepeatMode.Restart), label = "pulse")
    val blink by transition.animateFloat(0.2f, 0.85f, infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "blink")
    Canvas(modifier.size(14.dp)) {
        val r = 3.5.dp.toPx()
        when {
            started -> {
                drawCircle(ok.copy(alpha = 0.28f * (1f - pulse)), radius = r + 4.dp.toPx() * pulse)
                drawCircle(ok, radius = r)
            }
            busy -> drawCircle(ink.copy(alpha = blink), radius = r)
            else -> drawCircle(ink.copy(alpha = 0.38f), radius = r)
        }
    }
}

/** A quiet placeholder while the exit address is being checked. */
@Composable
internal fun Shimmer(width: Dp, height: Dp = 14.dp, modifier: Modifier = Modifier) {
    val ink = LocalContentColor.current
    val transition = rememberInfiniteTransition(label = "shimmer")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "sweep")
    Box(
        modifier
            .size(width, height)
            .clip(RoundedCornerShape(height / 2))
            .drawBehind {
                val x = size.width * (t * 3f - 1f)
                drawRect(
                    Brush.linearGradient(
                        listOf(ink.copy(alpha = 0.1f), ink.copy(alpha = 0.22f), ink.copy(alpha = 0.1f)),
                        start = Offset(x - size.width, 0f),
                        end = Offset(x + size.width, 0f),
                    ),
                )
            },
    )
}
