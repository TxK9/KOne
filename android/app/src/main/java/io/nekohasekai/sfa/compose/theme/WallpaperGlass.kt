package io.nekohasekai.sfa.compose.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

internal val LocalReadingGlass = staticCompositionLocalOf { false }

/** The reading panel's side margin; see [WallpaperContentFrame]. */
internal val READING_PANEL_MARGIN = 16.dp

/**
 * Inside the reading panel the panel itself is the card, so a group padded in from the screen
 * edge would add a second margin and leave rows with a switch only a few characters of width.
 * Such a group widens back out by [READING_PANEL_MARGIN] on each side; one that already spans
 * the panel is left alone.
 */
@Composable
internal fun Modifier.readingBleed(): Modifier {
    if (!LocalReadingGlass.current) return this
    val density = LocalDensity.current
    val panel = with(density) { (LocalConfiguration.current.screenWidthDp.dp - READING_PANEL_MARGIN * 2).roundToPx() }
    val extra = with(density) { (READING_PANEL_MARGIN * 2).roundToPx() }
    return layout { measurable, constraints ->
        if (!constraints.hasBoundedWidth || constraints.maxWidth + extra > panel + 3) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth + extra, maxWidth = constraints.maxWidth + extra))
        layout(constraints.maxWidth, placeable.height) { placeable.place(-extra / 2, 0) }
    }
}

/** Selected clear film: no photo redraw or blur, and no fill on dark photos. */
@Composable
internal fun WallpaperGlass(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(26.dp),
    enabled: Boolean = true,
    showBorder: Boolean = true,
    content: @Composable () -> Unit,
) {
    val backdrop = LocalBackdrop.current
    val visible = enabled && !LocalTransparentUi.current && backdrop?.theme?.brightComposition == true && !LocalReadingGlass.current
    Box(
        modifier.clip(shape),
        propagateMinConstraints = true,
    ) {
        if (visible) {
            val tone = Color(backdrop.theme.readingSurface)
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(
                tone.copy(alpha = LightFilmMaterial.START_ALPHA),
                tone.copy(alpha = LightFilmMaterial.endAlpha),
            ))))
            if (showBorder) Box(Modifier.matchParentSize().border(BorderStroke(0.65.dp, Brush.linearGradient(listOf(
                Color.White.copy(alpha = 0.80f), Color.White.copy(alpha = 0.18f),
            ))), shape))
        }
        content()
    }
}

/** Section headings remain readable in the gaps between translucent groups. */
@Composable
fun KnineSectionLabel(text: String, modifier: Modifier = Modifier) {
    // Lined up with the rows' icons, which sit closer to the edge inside the reading panel.
    val start = if (LocalReadingGlass.current) 24.dp - READING_PANEL_MARGIN else 24.dp
    Box(modifier.padding(start = start, top = 8.dp, bottom = 8.dp)) {
        WallpaperGlass(shape = RoundedCornerShape(50)) {
            BackdropText(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}
