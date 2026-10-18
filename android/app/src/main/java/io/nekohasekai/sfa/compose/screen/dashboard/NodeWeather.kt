package io.nekohasekai.sfa.compose.screen.dashboard

/**
 * Weather seal: a node's health told as weather. Each has a seal in a painter's pigment with its
 * character cut into it, and a small ink glyph: sun (a sun) works normally, mist (drifting mist)
 * is slow, storm (a storm cloud) is not answering, moon (the moon) is not connected.
 *
 * Colours are ARGB so the notification can paint the seal without Compose. The ink shades are
 * for glyphs and text on the page: a deeper one on a light page, a brighter one on a dark page.
 */
internal enum class NodeWeather(
    val char: String,
    val word: String,
    val pigment: Long,
    val onPigment: Long,
    val inkOnLight: Long,
    val inkOnDark: Long,
) {
    Clear("S", "Good", 0xFF2F7D66, 0xFFFFFFFF, 0xFF2F7D66, 0xFF63C29E),
    Mist("M", "Slow", 0xFFE0A800, 0xFF1C2622, 0xFF9A6F00, 0xFFE8B520),
    Cut("X", "Unreachable", 0xFFB3362A, 0xFFFFFFFF, 0xFFB3362A, 0xFFF0806F),
    Rest("Z", "Disconnected", 0xFF6B7470, 0xFFFFFFFF, 0xFF5C6561, 0xFF9AA3A0),
    ;

    companion object {
        fun of(health: NodeHealth): NodeWeather = when (health) {
            NodeHealth.Good -> Clear
            NodeHealth.Slow -> Mist
            NodeHealth.Down -> Cut
        }
    }
}

/**
 * The glyphs on a 24-unit square, as SVG path data: drawn by Compose on the page and by
 * android.graphics in the notification's seal picture.
 */
internal object WeatherGlyphs {
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
