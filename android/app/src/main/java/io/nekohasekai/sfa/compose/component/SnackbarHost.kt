package io.nekohasekai.sfa.compose.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.GlassTheme
import io.nekohasekai.sfa.compose.theme.KninePalette
import io.nekohasekai.sfa.compose.theme.wallpaperPanelColor

@Composable
fun SnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    androidx.compose.material3.SnackbarHost(
        hostState = hostState,
        modifier = modifier,
    ) { snackbarData ->
        GlassTheme {
        Snackbar(
            snackbarData = snackbarData,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            containerColor = wallpaperPanelColor(Color(if (MaterialTheme.colorScheme.onSurface.red > 0.5f) KninePalette.DARK_READING_SURFACE else KninePalette.LIGHT_READING_SURFACE).copy(alpha = 0.97f)),
            contentColor = MaterialTheme.colorScheme.onSurface,
            actionColor = MaterialTheme.colorScheme.primary,
            actionContentColor = MaterialTheme.colorScheme.primary,
            dismissActionContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        }
    }
}
