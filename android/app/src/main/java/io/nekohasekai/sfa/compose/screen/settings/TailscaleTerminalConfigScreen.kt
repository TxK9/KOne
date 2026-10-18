package io.nekohasekai.sfa.compose.screen.settings

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import io.github.sagernet.libghostty.extras.GhosttyThemeStore
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.KnineSectionLabel
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.database.Settings

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscaleTerminalConfigScreen(navController: NavController) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.tailscale_terminal_config)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                }
            },
        )
    }

    val storedLightTheme = Settings.tailscaleSSHLightTheme
    val storedDarkTheme = Settings.tailscaleSSHDarkTheme
    var lightTheme by remember {
        mutableStateOf(storedLightTheme.ifEmpty { GhosttyThemeStore.DEFAULT_LIGHT_THEME })
    }
    var lightCustomEnabled by remember { mutableStateOf(storedLightTheme.isEmpty()) }
    var darkTheme by remember {
        mutableStateOf(storedDarkTheme.ifEmpty { GhosttyThemeStore.DEFAULT_DARK_THEME })
    }
    var darkCustomEnabled by remember { mutableStateOf(storedDarkTheme.isEmpty()) }
    var fontFollowTheme by remember { mutableStateOf(Settings.tailscaleSSHFontFollowTheme) }
    var fontFamily by remember { mutableStateOf(Settings.tailscaleSSHFontFamily) }
    var customFontPath by remember { mutableStateOf(Settings.tailscaleSSHCustomFontPath) }
    var fontSize by remember { mutableIntStateOf(Settings.tailscaleSSHFontSize) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val lightStored = Settings.tailscaleSSHLightTheme
        lightCustomEnabled = lightStored.isEmpty()
        lightTheme = lightStored.ifEmpty { GhosttyThemeStore.DEFAULT_LIGHT_THEME }
        val darkStored = Settings.tailscaleSSHDarkTheme
        darkCustomEnabled = darkStored.isEmpty()
        darkTheme = darkStored.ifEmpty { GhosttyThemeStore.DEFAULT_DARK_THEME }
        fontFollowTheme = Settings.tailscaleSSHFontFollowTheme
        fontFamily = Settings.tailscaleSSHFontFamily
        customFontPath = Settings.tailscaleSSHCustomFontPath
        fontSize = Settings.tailscaleSSHFontSize
    }

    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        SchemeSection(
            title = stringResource(R.string.tailscale_terminal_light_config),
            themeName = lightTheme,
            customEnabled = lightCustomEnabled,
            onThemeClick = {
                navController.navigate("settings/tailscale/theme_picker/${Uri.encode("false")}")
            },
            onCustomEnabledChange = { enabled ->
                lightCustomEnabled = enabled
                Settings.tailscaleSSHLightTheme = if (enabled) "" else lightTheme
            },
            onEditCustomClick = {
                navController.navigate("settings/tailscale/config_editor/${Uri.encode("false")}")
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        SchemeSection(
            title = stringResource(R.string.tailscale_terminal_dark_config),
            themeName = darkTheme,
            customEnabled = darkCustomEnabled,
            onThemeClick = {
                navController.navigate("settings/tailscale/theme_picker/${Uri.encode("true")}")
            },
            onCustomEnabledChange = { enabled ->
                darkCustomEnabled = enabled
                Settings.tailscaleSSHDarkTheme = if (enabled) "" else darkTheme
            },
            onEditCustomClick = {
                navController.navigate("settings/tailscale/config_editor/${Uri.encode("true")}")
            },
        )

        Spacer(modifier = Modifier.height(16.dp))

        KnineSectionLabel(text = stringResource(R.string.tailscale_terminal_font_config))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.tailscale_terminal_font_follow_theme)) },
                    trailingContent = {
                        KnineSwitch(
                            checked = fontFollowTheme,
                            onCheckedChange = null,
                        )
                    },
                    modifier = Modifier
                        .clip(
                            if (fontFollowTheme) {
                                RoundedCornerShape(12.dp)
                            } else {
                                RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
                            },
                        )
                        .clickable {
                            fontFollowTheme = !fontFollowTheme
                            Settings.tailscaleSSHFontFollowTheme = fontFollowTheme
                        },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                if (!fontFollowTheme) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.tailscale_terminal_font)) },
                        supportingContent = {
                            val fontDisplayName = when {
                                customFontPath.isNotBlank() -> java.io.File(customFontPath).nameWithoutExtension
                                fontFamily.isNotBlank() -> fontFamily
                                else -> stringResource(R.string.tailscale_terminal_font_follow_theme)
                            }
                            Text(
                                fontDisplayName,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier.clickable {
                            navController.navigate("settings/tailscale/font_picker")
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.tailscale_terminal_font_size)) },
                        trailingContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                IconButton(
                                    onClick = {
                                        if (fontSize > 8) {
                                            fontSize--
                                            Settings.tailscaleSSHFontSize = fontSize
                                        }
                                    },
                                    enabled = fontSize > 8,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Default.Remove, contentDescription = stringResource(R.string.knine_settings_decrease_font), modifier = Modifier.size(20.dp))
                                }
                                Text(
                                    "$fontSize",
                                    modifier = Modifier.width(32.dp),
                                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                                    textAlign = TextAlign.Center,
                                )
                                IconButton(
                                    onClick = {
                                        if (fontSize < 32) {
                                            fontSize++
                                            Settings.tailscaleSSHFontSize = fontSize
                                        }
                                    },
                                    enabled = fontSize < 32,
                                    modifier = Modifier.size(48.dp),
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.knine_settings_increase_font), modifier = Modifier.size(20.dp))
                                }
                            }
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SchemeSection(
    title: String,
    themeName: String,
    customEnabled: Boolean,
    onThemeClick: () -> Unit,
    onCustomEnabledChange: (Boolean) -> Unit,
    onEditCustomClick: () -> Unit,
) {
    KnineSectionLabel(text = title)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column {
            ListItem(
                headlineContent = { Text(stringResource(R.string.tailscale_terminal_theme)) },
                supportingContent = {
                    Text(
                        themeName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent = {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .clickable(enabled = !customEnabled, onClick = onThemeClick),
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent,
                    headlineColor = if (customEnabled) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    trailingIconColor = if (customEnabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ),
            )
            ListItem(
                headlineContent = { Text(stringResource(R.string.tailscale_terminal_custom_config)) },
                trailingContent = {
                    KnineSwitch(
                        checked = customEnabled,
                        onCheckedChange = null,
                    )
                },
                modifier = Modifier
                    .clip(
                        if (customEnabled) {
                            RoundedCornerShape(0.dp)
                        } else {
                            RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)
                        },
                    )
                    .clickable { onCustomEnabledChange(!customEnabled) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            if (customEnabled) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.tailscale_terminal_edit_custom_config)) },
                    modifier = Modifier
                        .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                        .clickable(onClick = onEditCustomClick),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
    }
}
