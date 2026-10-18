package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A glyph or a word in the weather's ink: deeper on a light page, brighter on a dark one. */
internal fun weatherInk(weather: NodeWeather, ink: Color): Color =
    Color(if (ink.luminance() > 0.5f) weather.inkOnDark else weather.inkOnLight)

/** The weather's seal: its character cut into a square of the pigment, as a seal is. */
@Composable
internal fun WeatherSeal(weather: NodeWeather, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.2f)
    val type = with(LocalDensity.current) { (size * 0.64f).toSp() }
    Box(
        modifier
            .size(size)
            .clip(shape)
            .background(Color(weather.pigment))
            .border(1.dp, Color.White.copy(alpha = 0.22f), shape)
            .semantics { contentDescription = weather.word },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            weather.char,
            color = Color(weather.onPigment),
            style = TextStyle(
                fontSize = type,
                lineHeight = type,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                platformStyle = PlatformTextStyle(includeFontPadding = false),
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
        )
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
