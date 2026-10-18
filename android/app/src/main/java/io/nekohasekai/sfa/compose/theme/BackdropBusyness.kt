package io.nekohasekai.sfa.compose.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.cbrt

/**
 * How busy the photo is behind an area, from 0 (calm) to 1 (busy): the mean step in
 * perceived lightness between neighbouring pixels of the reduced theme map. Busy detail
 * defeats a text glow, so the panel keeps more of its film there.
 *
 * Calibrated on the built-in wallpapers' panel areas: the portrait scores 0.007, the
 * window landscape 0.090, the cherry and ginkgo trees 0.116, the corridor with a dark
 * uniform against a white wall 0.123.
 */
internal object BackdropBusyness {
    const val CALM_STEP = 0.05f
    const val BUSY_STEP = 0.12f

    fun of(pixels: BackdropPixels, root: Rect, area: Rect, focus: BackdropFocus): Float {
        if (root.width <= 0f || root.height <= 0f || area.width <= 0f || area.height <= 0f) return 0f
        if (pixels.luminance.size != pixels.width * pixels.height) return 0f
        val framing = BackdropFraming.transform(pixels.sourceWidth, pixels.sourceHeight, root.width, root.height, focus)
        val local = area.translate(Offset(-root.left, -root.top))
        fun imageX(x: Float) = ((x - framing.x) / framing.width * pixels.width).toInt().coerceIn(0, pixels.width - 1)
        fun imageY(y: Float) = ((y - framing.y) / framing.height * pixels.height).toInt().coerceIn(0, pixels.height - 1)
        return score(pixels.luminance, pixels.width, imageX(local.left), imageY(local.top), imageX(local.right), imageY(local.bottom))
    }

    /** [luminance] is relative luminance, row-major with [width] columns; bounds are inclusive. */
    fun score(luminance: FloatArray, width: Int, x0: Int, y0: Int, x1: Int, y1: Int): Float {
        if (x1 <= x0 || y1 <= y0) return 0f
        var total = 0f
        var count = 0
        for (y in y0 until y1) {
            for (x in x0 until x1) {
                val here = lightness(luminance[y * width + x])
                total += abs(lightness(luminance[y * width + x + 1]) - here) + abs(lightness(luminance[(y + 1) * width + x]) - here)
                count++
            }
        }
        val step = total / count
        return ((step - CALM_STEP) / (BUSY_STEP - CALM_STEP)).coerceIn(0f, 1f)
    }

    /** CIE L* scaled to 0..1, so a step means the same to the eye in shadows and highlights. */
    fun lightness(luminance: Float): Float {
        val f = if (luminance > 0.008856f) cbrt(luminance) else 7.787f * luminance + 16f / 116f
        return (116f * f - 16f) / 100f
    }
}
