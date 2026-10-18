package io.nekohasekai.sfa.compose.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.max

/** [BackdropBusyness] of the photo behind [area]; 0 while no photo map is ready. */
@Composable
internal fun rememberBackdropBusyness(backdrop: BackdropContext, area: Rect): Float = remember(backdrop.pixels, backdrop.bounds, backdrop.focus, area) {
    backdrop.pixels?.let { BackdropBusyness.of(it, backdrop.bounds, area, backdrop.focus) } ?: 0f
}

@Composable
internal fun rememberReadingAlpha(backdrop: BackdropContext, area: Rect, foreground: Color, target: Float): Float {
    if (area.width <= 0 || area.height <= 0) return 0f
    val support = remember(backdrop, area, foreground, target) {
        KninePalette.readingSupport(foreground.toArgb(), backdropColors(backdrop, area), backdrop.theme.readingSurface, target)
    }
    // A new traffic number or scroll position never selects a new text color.
    // Keep the strongest support needed during this wallpaper's lifetime, so
    // crossing light/dark photo detail cannot pulse the same label's underlay.
    var retainedAlpha by remember(backdrop.pixels, backdrop.theme, backdrop.previousPixels != null, foreground, target) { mutableStateOf(0f) }
    val alpha = max(retainedAlpha, support.alpha)
    SideEffect { retainedAlpha = alpha }
    return alpha
}
