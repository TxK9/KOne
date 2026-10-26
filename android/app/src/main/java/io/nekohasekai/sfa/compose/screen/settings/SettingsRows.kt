package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.KnineDropdownMenu as DropdownMenu

/*
 * The rows every settings page shares, on a KnineRowGroup card. A row never rounds itself: the
 * card clips a press to its own corners. Titles wrap rather than cut off on a narrow phone.
 */

/** How far a row that cannot be used now fades. */
internal const val DISABLED_ROW_ALPHA = 0.38f

@Composable
private fun RowIcon(icon: ImageVector) {
    Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
}

/**
 * A setting that is on or off. The whole row takes the tap and the switch only shows the state;
 * while [busy], a small spinner holds the switch's place.
 */
@Composable
internal fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    supporting: String? = null,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = supporting?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
        leadingContent = icon?.let { { RowIcon(it) } },
        trailingContent = {
            // The switch's own size, so a row keeps its edge while the spinner stands in.
            Box(Modifier.size(46.dp, 28.dp), contentAlignment = Alignment.Center) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    KnineSwitch(checked = checked, onCheckedChange = null)
                }
            }
        },
        modifier = modifier
            .alpha(if (enabled) 1f else DISABLED_ROW_ALPHA)
            .toggleable(value = checked, enabled = enabled && !busy, role = Role.Switch, onValueChange = onCheckedChange),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * A setting with a few choices. The row says the current one ([value], the selected option by
 * default), and a tap opens the choices right under it, the current one ticked. [onSelect] is
 * called for every pick, the current one included, so a row can treat picking it again as asking
 * for it anew.
 */
@Composable
internal fun SettingsPickRow(
    title: String,
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    value: String? = options.getOrNull(selected),
    enabled: Boolean = true,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.alpha(if (enabled) 1f else DISABLED_ROW_ALPHA)) {
        ListItem(
            headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
            supportingContent = value?.let { { Text(it, style = MaterialTheme.typography.bodyMedium) } },
            leadingContent = icon?.let { { RowIcon(it) } },
            modifier = Modifier.clickable(enabled = enabled, role = Role.DropdownList) { open = true },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        Box(Modifier.align(Alignment.BottomEnd)) {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEachIndexed { index, option ->
                    DropdownMenuItem(
                        text = { Text(option) },
                        trailingIcon = if (index == selected) {
                            { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
                        } else {
                            null
                        },
                        onClick = {
                            open = false
                            onSelect(index)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Choices that wrap onto as many lines as they need, so none is ever cut off on a narrow phone.
 * Drawn in the wallpaper's ink rather than the theme's outline, which dark wallpapers clear.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsChips(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = LocalContentColor.current
    val colors = FilterChipDefaults.filterChipColors(
        containerColor = ink.copy(alpha = 0.06f),
        labelColor = ink,
        selectedContainerColor = ink.copy(alpha = 0.16f),
        selectedLabelColor = ink,
    )
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selected
            FilterChip(
                selected = isSelected,
                onClick = { onSelect(index) },
                label = { Text(option, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                colors = colors,
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = isSelected,
                    borderColor = ink.copy(alpha = 0.22f),
                    selectedBorderColor = ink.copy(alpha = 0.5f),
                ),
            )
        }
    }
}

/** A row that hands over to Android's own settings, marked by the small arrow out. */
@Composable
internal fun SettingsLinkRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    supportingColor: Color = Color.Unspecified,
    supportingContent: (@Composable () -> Unit)? = supporting?.let {
        { Text(it, style = MaterialTheme.typography.bodyMedium, color = supportingColor) }
    },
) {
    ListItem(
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = supportingContent,
        leadingContent = { RowIcon(icon) },
        trailingContent = {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        },
        modifier = modifier.clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
