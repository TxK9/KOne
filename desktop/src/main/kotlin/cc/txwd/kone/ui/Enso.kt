package cc.txwd.kone.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

/**
 * The connection's ring around the power sign, brushed as an ensō, the circle of ink painting:
 * a hairline while disconnected; while connecting, a stroke that turns, thick in its middle and
 * dry at both ends; once connected, a closed stroke that draws itself in the node's weather
 * (green when healthy, amber when slow, red when it does not answer) with one soft bloom of
 * ink, and then rests. [tint], when given, is used in every state (on the tray's dark pill).
 * It turns only while [moving]; when Windows keeps still it is drawn at once, without a turn.
 */
@Composable
fun ConnectionRing(connection: Connection, weather: NodeWeather, size: Dp, moving: Boolean, modifier: Modifier = Modifier, tint: Color? = null) {
    val ink = LocalInk.current
    val still = LocalStill.current
    val software = LocalSoftware.current
    val online = connection == Connection.Online
    val tone by animateColorAsState(
        tint ?: if (online) weather.ink(ink.dark) else ink.ink,
        Motion.spec(still, Motion.STANDARD), label = "ring",
    )
    // The closed stroke drawing itself, and the bloom of ink as it closes.
    val drawn = remember { Animatable(if (online) 1f else 0f) }
    val bloom = remember { Animatable(1f) }
    LaunchedEffect(online, still) {
        if (!online) {
            drawn.snapTo(0f)
        } else if (drawn.value < 1f) {
            if (still) {
                drawn.snapTo(1f)
            } else {
                bloom.snapTo(0f)
                kotlinx.coroutines.coroutineScope {
                    launch { drawn.animateTo(1f, tween(Motion.CALM, easing = Motion.Settle)) }
                    launch { bloom.animateTo(1f, tween(Motion.CALM + 450, delayMillis = Motion.CALM / 2, easing = Motion.Settle)) }
                }
            }
        }
    }
    // Turning while connecting: slowly, and in a few steps a second when Windows draws in software.
    val clock = rememberClock(moving && !still && connection == Connection.Connecting) { if (software) 8 else 40 }
    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val widest = 2.6.dp.toPx()
        val radius = this.size.minDimension / 2 - widest
        when (connection) {
            Connection.Idle -> drawCircle(tone.copy(alpha = tone.alpha * 0.28f), radius, center, style = Stroke(1.dp.toPx()))
            Connection.Connecting -> {
                drawCircle(tone.copy(alpha = tone.alpha * 0.12f), radius, center, style = Stroke(1.dp.toPx()))
                val start = (clock.value * 216.0 % 360.0).toFloat() - 90f
                brush(center, radius, start, 230f, widest, tone) { t -> sin(PI * t).pow(0.7).toFloat().coerceAtLeast(0.12f) }
            }
            Connection.Online -> {
                val b = bloom.value
                // Rising and fading as it spreads; nothing before it starts or after it ends.
                if (b > 0f && b < 1f) drawCircle(tone.copy(alpha = tone.alpha * 0.22f * sin(PI * b).toFloat()), radius * (1 + 0.55f * b), center)
                // Pressed down where it starts, thinning to a dry tail short of closing, as a brush leaves it.
                brush(center, radius, 112f, 326f * drawn.value, widest, tone) { t ->
                    (0.32f + 0.68f * (1 - t).pow(0.8f)) * (0.72f + 0.28f * (t / 0.05f).coerceAtMost(1f))
                }
            }
        }
    }
}

/**
 * An arc from [start] degrees through [sweep] (clockwise), [widest] at its thickest, its width
 * along the way given by [profile] (0 at the start, 1 at the end): short pieces of arc, each as
 * wide as the brush is there, laid end to end.
 */
private fun DrawScope.brush(center: Offset, radius: Float, start: Float, sweep: Float, widest: Float, color: Color, profile: (Float) -> Float) {
    if (sweep <= 0.5f) return
    val pieces = (abs(sweep) / 5f).toInt().coerceAtLeast(6)
    val step = sweep / pieces
    val topLeft = Offset(center.x - radius, center.y - radius)
    val box = Size(radius * 2, radius * 2)
    for (i in 0 until pieces) {
        val width = (widest * profile((i + 0.5f) / pieces)).coerceAtLeast(0.6f)
        // A sliver more than the step, so the pieces meet without a seam.
        drawArc(color, start + i * step, step + 0.6f, false, topLeft, box, style = Stroke(width, cap = StrokeCap.Butt))
    }
}

/**
 * Work under way (testing, checking, updating, activating): a short brush stroke turning, the
 * connection's ring in small, so every wait in KOne looks the same. Still when Windows keeps
 * still, and when the window is out of sight; a few steps a second in software drawing.
 */
@Composable
fun Working(size: Dp = 14.dp, color: Color = LocalInk.current.soft, modifier: Modifier = Modifier) {
    val still = LocalStill.current
    val software = LocalSoftware.current
    val clock = rememberClock(!still) { if (software) 6 else 30 }
    Canvas(modifier.size(size)) {
        val widest = (this.size.minDimension * 0.13f).coerceAtLeast(1.4f)
        val radius = this.size.minDimension / 2 - widest
        val start = (clock.value * 300.0 % 360.0).toFloat() - 90f
        brush(Offset(this.size.width / 2, this.size.height / 2), radius, start, 250f, widest, color) { t ->
            sin(PI * t).pow(0.7).toFloat().coerceAtLeast(0.15f)
        }
    }
}

/** [word] with the turning stroke before it: what is under way, in its own words. */
@Composable
fun Busy(word: String, size: androidx.compose.ui.unit.TextUnit = 14.sp, color: Color = LocalInk.current.soft) {
    androidx.compose.foundation.layout.Row(
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(7.dp),
    ) {
        Working((size.value + 1).dp, color)
        Label(word, size, color)
    }
}
