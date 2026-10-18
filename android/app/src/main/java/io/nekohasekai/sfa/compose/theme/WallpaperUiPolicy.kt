package io.nekohasekai.sfa.compose.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Dark photos use only text and controls; bright photos keep readable panels. */
internal val LocalTransparentUi = staticCompositionLocalOf { false }
internal class WallpaperOverlayState { val windows = mutableIntStateOf(0) }
internal val LocalWallpaperOverlay = staticCompositionLocalOf<WallpaperOverlayState?> { null }

@Composable
internal fun wallpaperPanelColor(color: Color): Color = if (LocalTransparentUi.current) Color.Transparent else color

/** Hide the page's text, but keep the photograph, behind a transparent popup. */
@Composable
internal fun WallpaperPopupVisibility(visible: Boolean = true) {
    val state = LocalWallpaperOverlay.current
    val hidePage = LocalTransparentUi.current && visible && state != null
    DisposableEffect(state, hidePage) {
        if (hidePage) state!!.windows.intValue++
        onDispose { if (hidePage) state!!.windows.intValue-- }
    }
}
