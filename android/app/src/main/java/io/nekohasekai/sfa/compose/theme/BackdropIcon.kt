package io.nekohasekai.sfa.compose.theme

import androidx.compose.material3.Icon as NativeIcon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector

@Composable
fun BackdropIcon(imageVector: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, emphasized: Boolean = false) {
    // Icons are graphics: 3:1 over the city, like large text.
    val placed = modifier.cityLine(large = true)
    if (LocalBackdrop.current == null) {
        NativeIcon(imageVector = imageVector, contentDescription = contentDescription, modifier = placed, tint = tint)
        return
    }
    val backdrop = LocalBackdrop.current ?: return
    val automatic = ordinaryBackdropInk(tint, MaterialTheme.colorScheme.onSurface)
    val selected = if (automatic) Color(if (emphasized) backdrop.theme.accent else backdrop.theme.ink) else tint
    val ink = Color(KninePalette.stableForeground(selected.copy(alpha = 1f).toArgb(), backdrop.theme, 3.4f)).copy(alpha = tint.alpha)
    NativeIcon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        modifier = placed,
        tint = ink,
    )
}
