package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A glyph or a word in the weather's ink: deeper on a light page, brighter on a dark one. */
internal fun weatherInk(weather: NodeWeather, ink: Color): Color =
    Color(if (ink.luminance() > 0.5f) weather.inkOnDark else weather.inkOnLight)

/**
 * The weather's seal: its glyph cut into a square of the pigment, as a seal is, and as the
 * notification's picture draws it. A glyph reads in any language; a letter in the seal did not.
 */
@Composable
internal fun WeatherSeal(weather: NodeWeather, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.2f)
    val paths = remember(weather) { WeatherGlyphs.paths(weather).map { PathParser().parsePathString(it).toPath() } }
    val cut = Color(weather.onPigment)
    val edge = LocalContentColor.current.copy(alpha = 0.16f)
    Canvas(
        modifier
            .size(size)
            .clip(shape)
            .background(Color(weather.pigment))
            .border(1.dp, edge, shape)
            .semantics { contentDescription = weather.word },
    ) {
        val unit = this.size.width * 0.6f / 24f
        translate(this.size.width * 0.2f, this.size.height * 0.2f) {
            scale(unit, pivot = Offset.Zero) {
                paths.forEach { drawPath(it, cut, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            }
        }
    }
}

/** The weather drawn in ink: a sun, drifting mist, a storm cloud, the moon. */
@Composable
internal fun WeatherGlyph(weather: NodeWeather, size: Dp = 16.dp, ink: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    val color = weatherInk(weather, ink)
    val paths = remember(weather) { WeatherGlyphs.paths(weather).map { PathParser().parsePathString(it).toPath() } }
    Canvas(modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            paths.forEach { drawPath(it, color, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
        }
    }
}
