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
import androidx.compose.ui.unit.dp

internal val LocalReadingGlass = staticCompositionLocalOf { false }

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
    Box(modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp)) {
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
