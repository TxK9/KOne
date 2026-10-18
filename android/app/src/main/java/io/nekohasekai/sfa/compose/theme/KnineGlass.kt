package io.nekohasekai.sfa.compose.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog as NativeAlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.DropdownMenu as NativeDropdownMenu
import androidx.compose.material3.Badge as NativeBadge
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.ModalBottomSheet as NativeModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun GlassTheme(freezeKey: Any? = Unit, content: @Composable () -> Unit) {
    if (LocalTransparentUi.current) {
        BackdropContrastScope { content() }
        return
    }
    val initialDark = MaterialTheme.colorScheme.onSurface.red > 0.5f
    val dark = remember(freezeKey) { initialDark }
    val ink = Color(KninePalette.ink(dark))
    val accent = Color(if (dark) KninePalette.LIGHT_ACCENT else KninePalette.DARK_ACCENT)
    val colors = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = accent,
        onPrimary = Color(if (dark) KninePalette.DARK_INK else KninePalette.LIGHT_INK),
        primaryContainer = accent.copy(alpha = 0.12f),
        onPrimaryContainer = ink,
        secondary = accent,
        secondaryContainer = ink.copy(alpha = 0.08f),
        onSecondaryContainer = ink,
        outline = ink.copy(alpha = 0.32f),
        outlineVariant = ink.copy(alpha = 0.14f),
        onSurface = ink,
        onSurfaceVariant = ink,
        surface = Color.Transparent,
        surfaceContainer = Color.Transparent,
        surfaceContainerLow = Color.Transparent,
        surfaceContainerHigh = Color.Transparent,
        surfaceContainerHighest = Color.Transparent,
    )
    // The panel has its own readable foreground; don't sample the photo behind it.
    CompositionLocalProvider(LocalBackdrop provides null, LocalContentColor provides ink) {
        MaterialTheme(colorScheme = colors, typography = backdropTypography(MaterialTheme.typography, dark)) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UNUSED_PARAMETER")
@Composable
fun KnineGlassBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
    containerColor: Color = Color.Transparent,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    dragHandle: (@Composable () -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    WallpaperPopupVisibility()
    GlassTheme {
        NativeModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = sheetState,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            containerColor = wallpaperPanelColor(Color(if (MaterialTheme.colorScheme.onSurface.red > 0.5f) KninePalette.DARK_READING_SURFACE else KninePalette.LIGHT_READING_SURFACE).copy(alpha = 0.96f)),
            tonalElevation = 0.dp,
            contentColor = MaterialTheme.colorScheme.onSurface,
            scrimColor = Color.Black.copy(alpha = 0.30f),
            dragHandle = dragHandle,
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

@Composable
fun KnineGlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    WallpaperPopupVisibility()
    GlassTheme {
        val dark = MaterialTheme.colorScheme.onSurface.red > 0.5f
        NativeAlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            title = title,
            text = text,
            shape = RoundedCornerShape(24.dp),
            containerColor = wallpaperPanelColor(Color(if (dark) KninePalette.DARK_READING_SURFACE else KninePalette.LIGHT_READING_SURFACE).copy(alpha = 0.91f)),
            tonalElevation = 0.dp,
        )
    }
}

/** Popup menus need a surface of their own, including over dark photographs. */
@Composable
fun KnineDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    WallpaperPopupVisibility(expanded)
    GlassTheme(freezeKey = expanded) {
        val dark = MaterialTheme.colorScheme.onSurface.red > 0.5f
        NativeDropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            shape = RoundedCornerShape(20.dp),
            containerColor = wallpaperPanelColor(Color(if (dark) KninePalette.DARK_READING_SURFACE else KninePalette.LIGHT_READING_SURFACE).copy(alpha = 0.97f)),
            tonalElevation = 0.dp,
            shadowElevation = if (LocalTransparentUi.current) 0.dp else 8.dp,
            content = content,
        )
    }
}

/** A filled badge has its own foreground, independent of the wallpaper. */
@Composable
fun KnineBadge(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.error,
    contentColor: Color = contentColorFor(containerColor),
    content: (@Composable RowScope.() -> Unit)? = null,
) {
    CompositionLocalProvider(LocalBackdrop provides null) {
        NativeBadge(modifier, wallpaperPanelColor(containerColor), if (LocalTransparentUi.current) MaterialTheme.colorScheme.onSurface else contentColor, content)
    }
}

/** Readable, bounded material for custom dialogs such as QR sharing. */
@Composable
fun KnineDialogSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    WallpaperPopupVisibility()
    GlassTheme {
        val dark = MaterialTheme.colorScheme.onSurface.red > 0.5f
        androidx.compose.material3.Surface(
            modifier = modifier,
            shape = RoundedCornerShape(24.dp),
            color = wallpaperPanelColor(Color(if (dark) KninePalette.DARK_READING_SURFACE else KninePalette.LIGHT_READING_SURFACE).copy(alpha = 0.97f)),
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
        ) { Column(content = content) }
    }
}
