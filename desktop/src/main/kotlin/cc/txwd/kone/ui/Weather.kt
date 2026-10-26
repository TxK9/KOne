package cc.txwd.kone.ui

import cc.txwd.kone.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Weather seal: a node's health told as weather, as on the phone (NodeWeather.kt). Each has a seal in a
 * painter's pigment with its character cut into it, and a small ink glyph: S (a sun) works
 * normally, M (drifting mist) is slow, ! (a storm cloud) is not answering, Z (the moon) is not
 * connected. The ink shades are for glyphs and text: deeper on a light page, brighter on a dark one.
 */
enum class NodeWeather(
    private val englishChar: String,
    private val englishWord: String,
    val pigment: Long,
    val onPigment: Long,
    val inkOnLight: Long,
    val inkOnDark: Long,
) {
    Clear("S", "Healthy", 0xFF2F7D66, 0xFFFFFFFF, 0xFF2F7D66, 0xFF63C29E),
    Mist("M", "Slow", 0xFFE0A800, 0xFF1C2622, 0xFF9A6F00, 0xFFE8B520),
    Cut("!", "Unreachable", 0xFFB3362A, 0xFFFFFFFF, 0xFFB3362A, 0xFFF0806F),
    Rest("Z", "Disconnected", 0xFF6B7470, 0xFFFFFFFF, 0xFF5C6561, 0xFF9AA3A0),
    ;

    fun ink(dark: Boolean) = Color(if (dark) inkOnDark else inkOnLight)

    companion object {
        /** A delay as the phone reads it: no answer is !, a second or more is M. */
        fun of(delayMillis: Int?): NodeWeather = when {
            delayMillis == null -> Cut
            delayMillis >= 1000 -> Mist
            else -> Clear
        }
    }
    val char: String get() = if (cc.txwd.kone.i18n.Strings.chinese) when (this) { Clear -> "晴"; Mist -> "缓"; Cut -> "断"; Rest -> "歇" } else englishChar
    val word: String get() = tr(englishWord)
}

/** The glyphs on a 24-unit square, as SVG path data: the same paths as the phone's WeatherGlyphs. */
object WeatherGlyphs {
    fun paths(weather: NodeWeather): List<String> = when (weather) {
        NodeWeather.Clear -> listOf(
            "M16.2 12a4.2 4.2 0 1 1-8.4 0a4.2 4.2 0 1 1 8.4 0",
            "M12 2.8v2.4M12 18.8v2.4M2.8 12h2.4M18.8 12h2.4M5.5 5.5l1.7 1.7M16.8 16.8l1.7 1.7M5.5 18.5l1.7-1.7M16.8 7.2l1.7-1.7",
        )
        NodeWeather.Mist -> listOf(
            "M3.5 8.5c2-1.3 4-1.3 6 0s4 1.3 6 0 3.5-1.1 5-.5",
            "M3 12.5c2-1.3 4-1.3 6 0s4 1.3 6 0 4-1.1 6-.4",
            "M5.5 16.5c1.7-1.1 3.4-1.1 5.1 0s3.4 1.1 5.1 0 2.6-.8 3.8-.3",
        )
        NodeWeather.Cut -> listOf(
            "M7.3 15.2h-.6a3.6 3.6 0 0 1-.3-7.2a5.2 5.2 0 0 1 10-1a3.8 3.8 0 0 1 1.3 7.4",
            "M12.8 11.2l-2.5 4.1h3.4l-2.3 4.2",
        )
        NodeWeather.Rest -> listOf(
            "M18.8 14.6A7.4 7.4 0 0 1 9.4 5.2a7.4 7.4 0 1 0 9.4 9.4z",
            "M17.8 3.6v2.6M16.5 4.9h2.6",
        )
    }
}

/** The small glyph of [weather], drawn in its ink for this page. */
@Composable
fun WeatherGlyph(weather: NodeWeather, size: Dp = 18.dp) {
    Glyph(WeatherGlyphs.paths(weather), weather.ink(LocalInk.current.dark), size, strokeWidth = 1.8f)
}

/** The seal alone: the pigment, a pale inner rim, the character cut in a serif. */
@Composable
fun WeatherSeal(weather: NodeWeather, size: Dp = 22.dp, corner: Dp = 4.dp, fontSize: Float = 14f, rim: Dp = 1.5.dp) {
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier.size(size).background(Color(weather.pigment), shape).border(rim, Color.White.copy(alpha = 0.22f), shape),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            weather.char,
            style = TextStyle(color = Color(weather.onPigment), fontFamily = Fonts.seal, fontWeight = FontWeight.Bold, fontSize = fontSize.sp),
        )
    }
}

/** Weather seal as it sits in a line of text: the seal with the glyph beside it. */
@Composable
fun WeatherTag(weather: NodeWeather) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        WeatherSeal(weather)
        WeatherGlyph(weather)
    }
}
