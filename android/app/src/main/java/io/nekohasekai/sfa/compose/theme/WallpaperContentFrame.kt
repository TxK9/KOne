package io.nekohasekai.sfa.compose.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding

/** Photo and reading area separate only for the settled high-light wallpaper. */
@Composable
fun WallpaperContentFrame(
    preservePortrait: Boolean,
    groupedContent: Boolean = false,
    serviceRunning: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalBackdrop.current
    val composed = enabled && backdrop?.layoutTheme?.brightComposition == true
    val scaffold = LocalScaffoldPadding.current
    val density = LocalDensity.current
    val reportReadingTop = backdrop?.onReadingTopChanged
    DisposableEffect(composed, preservePortrait, reportReadingTop) {
        onDispose { reportReadingTop?.invoke(null) }
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val portraitSpace = if (composed && preservePortrait) {
            BrightWallpaperPolicy.portraitSpace(
                maxHeight.value, scaffold.calculateTopPadding().value,
                scaffold.calculateBottomPadding().value,
                serviceRunning = serviceRunning,
            ).dp
        } else 0.dp
        val top = if (composed) scaffold.calculateTopPadding() + portraitSpace else 0.dp
        Box(
            Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                // Report the same physical boundary used for content padding;
                // the photograph must end here instead of continuing behind it.
                reportReadingTop?.invoke(if (composed && preservePortrait) {
                    coordinates.boundsInWindow().top + with(density) { top.toPx() }
                } else null)
            }.padding(top = top),
        ) {
            // The reading frame already accounts for the toolbar. Keep the
            // upstream screen's bottom inset, scrolling, state and callbacks.
            val childPadding = if (composed) PaddingValues(bottom = scaffold.calculateBottomPadding()) else scaffold
            val readingPanel = composed && !preservePortrait && !groupedContent
            // Keep the NavHost at one composition site across pages and photos.
            // Only its decoration and insets change; its remembered state survives.
            WallpaperGlass(
                modifier = if (readingPanel) Modifier.fillMaxSize().padding(
                    start = 16.dp, end = 16.dp, top = 8.dp,
                    bottom = scaffold.calculateBottomPadding() + 8.dp,
                ) else Modifier.fillMaxSize(),
                shape = if (readingPanel) androidx.compose.foundation.shape.RoundedCornerShape(22.dp)
                    else androidx.compose.ui.graphics.RectangleShape,
                enabled = readingPanel,
            ) {
                CompositionLocalProvider(
                    LocalScaffoldPadding provides if (readingPanel) PaddingValues(0.dp) else childPadding,
                    LocalReadingGlass provides readingPanel,
                ) { content() }
            }
        }
    }
}
