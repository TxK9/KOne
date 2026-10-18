package io.nekohasekai.sfa.compose.theme

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

internal data class WallpaperTheme(val lightInk: Boolean, val brightComposition: Boolean = false) {
    val ink: Int get() = KninePalette.ink(lightInk)
    val accent: Int get() = if (lightInk) KninePalette.LIGHT_ACCENT else KninePalette.DARK_ACCENT
    val readingSurface: Int get() = if (lightInk) KninePalette.DARK_READING_SURFACE else KninePalette.LIGHT_READING_SURFACE
}

internal data class ReadingSupport(val alpha: Float, val sampledCount: Int, val sampledMinimumContrast: Float) {
    // This describes the decoded sample map, not every pixel in the original photo.
    val hasSamples: Boolean get() = sampledCount > 0
}

internal object KninePalette {
    const val LIGHT_INK = 0xFFF4F1E8.toInt()
    const val DARK_INK = 0xFF193229.toInt()
    const val LIGHT_ACCENT = 0xFFCBDCC9.toInt()
    const val DARK_ACCENT = 0xFF1A372C.toInt()
    const val DARK_READING_SURFACE = 0xFF112A23.toInt()
    const val LIGHT_READING_SURFACE = 0xFFF6F5EE.toInt()
    const val DARK_ERROR = 0xFF650C0C.toInt()

    fun ink(light: Boolean) = if (light) LIGHT_INK else DARK_INK

    fun canTweenInk(from: Int, to: Int): Boolean =
        (luminance(from) >= 0.25f) == (luminance(to) >= 0.25f)

    fun prefersLight(lightScore: Float, darkScore: Float, previous: Boolean?): Boolean {
        if (previous == true && darkScore < lightScore * 1.15f) return true
        if (previous == false && lightScore < darkScore * 1.15f) return false
        return lightScore >= darkScore
    }

    fun luminance(argb: Int): Float {
        fun linear(shift: Int): Double {
            val channel = ((argb ushr shift) and 255) / 255.0
            return if (channel <= 0.04045) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)
        }
        return (0.2126 * linear(16) + 0.7152 * linear(8) + 0.0722 * linear(0)).toFloat()
    }

    fun contrast(foreground: Int, backgroundLuminance: Float): Float {
        val foregroundLuminance = luminance(foreground)
        return (max(foregroundLuminance, backgroundLuminance) + 0.05f) / (min(foregroundLuminance, backgroundLuminance) + 0.05f)
    }

    fun composite(overlay: Int, background: Int, alpha: Float): Int {
        val opacity = alpha.coerceIn(0f, 1f)
        fun channel(shift: Int): Int = ((((overlay ushr shift) and 255) * opacity) +
            (((background ushr shift) and 255) * (1f - opacity))).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    }

    fun minimumSupportAlpha(foreground: Int, background: Int, surface: Int, target: Float): Float {
        if (contrast(foreground, luminance(background)) >= target) return 0f
        if (contrast(foreground, luminance(surface)) < target) return 1f
        var low = 0f
        var high = 1f
        repeat(16) {
            val mid = (low + high) / 2f
            if (contrast(foreground, luminance(composite(surface, background, mid))) >= target) high = mid else low = mid
        }
        // A small margin covers 8-bit alpha rounding; it is not a claim about unsampled pixels.
        return (high + 1f / 255f).coerceAtMost(1f)
    }

    fun readingSupport(foreground: Int, backgrounds: List<Int>, surface: Int, target: Float): ReadingSupport {
        if (backgrounds.isEmpty()) return ReadingSupport(1f, 0, contrast(foreground, luminance(surface)))
        val alpha = backgrounds.maxOf { minimumSupportAlpha(foreground, it, surface, target) }
        return ReadingSupport(alpha, backgrounds.size, backgrounds.minOf {
            contrast(foreground, luminance(composite(surface, it, alpha)))
        })
    }

    fun wallpaperTheme(backgrounds: List<Int>): WallpaperTheme {
        if (backgrounds.isEmpty()) return WallpaperTheme(true)
        val luminances = backgrounds.map(::luminance)
        val brightComposition = BrightWallpaperPolicy.shouldCompose(luminances)
        if (brightComposition) return WallpaperTheme(lightInk = false, brightComposition = true)
        fun cost(light: Boolean): Double {
            val theme = WallpaperTheme(light)
            val body = luminance(theme.ink)
            val accent = luminance(theme.accent)
            fun score(foreground: Float, background: Float) =
                (max(foreground, background) + 0.05f) / (min(foreground, background) + 0.05f)
            return luminances.sumOf { background ->
                (4.5f - min(score(body, background), score(accent, background))).coerceAtLeast(0f).toDouble()
            } / luminances.size
        }
        // The decision uses the whole visible crop once, rather than moving labels.
        return WallpaperTheme(cost(true) <= cost(false), brightComposition = false)
    }

    fun stableForeground(requested: Int, theme: WallpaperTheme, target: Float): Int {
        val brightness = luminance(requested)
        // Keep semantic colors only when they share the theme's contrast direction.
        // Midtones can cross through unreadable contrast during a surface blend.
        val sameDirection = if (theme.lightInk) brightness > 1.05f / target - 0.05f else brightness < (target - 1f) * 0.05f
        val background = if (theme.brightComposition) LightFilmMaterial.minimumBackground else theme.readingSurface
        return if (sameDirection && contrast(requested, luminance(background)) >= target) requested else theme.ink
    }

    fun accent(light: Boolean, backgroundSamples: List<Float>): Int {
        val candidate = if (light) LIGHT_ACCENT else DARK_ACCENT
        // Emphasis must pass every local sample; ordinary ink remains the fallback.
        return if (backgroundSamples.isNotEmpty() && backgroundSamples.all { contrast(candidate, it) >= 4.5f }) candidate else ink(light)
    }
}
