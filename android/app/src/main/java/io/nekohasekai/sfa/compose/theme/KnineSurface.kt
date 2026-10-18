package io.nekohasekai.sfa.compose.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as NativeSurface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun KnineSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val clear = LocalTransparentUi.current
    NativeSurface(
        modifier = modifier, shape = shape, color = wallpaperPanelColor(color),
        contentColor = if (clear) MaterialTheme.colorScheme.onSurface else contentColor,
        tonalElevation = if (clear) 0.dp else tonalElevation,
        shadowElevation = if (clear) 0.dp else shadowElevation,
        border = if (clear) null else border, content = content,
    )
}

@Composable
fun KnineSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable () -> Unit,
) {
    val clear = LocalTransparentUi.current
    NativeSurface(
        onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
        color = wallpaperPanelColor(color), contentColor = if (clear) MaterialTheme.colorScheme.onSurface else contentColor,
        tonalElevation = if (clear) 0.dp else tonalElevation,
        shadowElevation = if (clear) 0.dp else shadowElevation,
        border = if (clear) null else border,
        interactionSource = interactionSource, content = content,
    )
}
