package io.nekohasekai.sfa.compose.screen.tools

import io.nekohasekai.sfa.compose.component.KnineChevron
import io.nekohasekai.sfa.compose.theme.KnineMotion
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.selfuse.HomeLink
import io.nekohasekai.sfa.selfuse.HomeSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog as AlertDialog

private enum class HomeField { Subnets, Hostname }

/** One settings owner inside Tailscale; expanding or cancelling never saves or restarts it. */
@Composable
internal fun TailscaleSettingsSection(
    stateText: String,
    onlineDevices: Int?,
    onTerminalConfig: () -> Unit,
    onChanged: () -> Unit,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val revision = HomeSettings.revision
    val enabled = HomeSettings.isEnabled
    val subnets = remember(revision) { HomeSettings.subnets() }
    val hostname = remember(revision) { HomeSettings.hostname() }
    var editing by remember { mutableStateOf<HomeField?>(null) }
    var saving by remember { mutableStateOf(false) }

    fun save(newEnabled: Boolean = enabled, newSubnets: List<String> = subnets, newHostname: String = hostname) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                HomeSettings.save(newEnabled, newSubnets, newHostname)
                onChanged()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                Toast.makeText(context, "Settings were not fully applied. Try again.", Toast.LENGTH_SHORT).show()
            } finally {
                saving = false
            }
        }
    }

    TailscaleConnectionHeader(
        enabled = enabled,
        stateText = if (saving) "Applying" else stateText,
        onlineDevices = onlineDevices,
        saving = saving,
        onEnabledChange = { save(newEnabled = it) },
    )
    content()
    ToolSectionHeader("Settings")
    TailscaleSettingsCard(
        hasSubnets = subnets.isNotEmpty(),
        hasHostname = hostname.isNotBlank(),
        enabled = !saving,
        onSubnets = { editing = HomeField.Subnets },
        onHostname = { editing = HomeField.Hostname },
        onTerminalConfig = onTerminalConfig,
    )

    when (editing) {
        HomeField.Subnets -> HomeFieldDialog(
            title = "Home network",
            hint = "Enter subnets such as 192.168.1.0/24, separated by spaces. Leave empty to access connected devices only.",
            initial = HomeLink.subnetsText(subnets),
            check = { text -> if (runCatching { HomeLink.parseSubnets(text) }.isFailure) "Invalid subnet format" else null },
            onDismiss = { editing = null },
            onSave = { text -> editing = null; save(newSubnets = HomeLink.parseSubnets(text)) },
        )
        HomeField.Hostname -> HomeFieldDialog(
            title = "Device name",
            hint = "Use letters, digits and hyphens.",
            initial = hostname,
            check = { text -> if (HomeLink.cleanHostname(text).isEmpty()) "Use at least one letter or digit" else null },
            onDismiss = { editing = null },
            onSave = { text -> editing = null; save(newHostname = HomeLink.cleanHostname(text)) },
        )
        null -> Unit
    }
}

@Composable
internal fun TailscaleConnectionHeader(
    enabled: Boolean,
    stateText: String,
    onlineDevices: Int?,
    saving: Boolean,
    onEnabledChange: (Boolean) -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        // The state in one line beside its switch, lined up with the rows below it.
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Outlined.Hub, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stateText, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (enabled && onlineDevices != null) "$onlineDevices devices online" else "Private network",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                KnineSwitch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    enabled = !saving,
                    modifier = Modifier.semantics { contentDescription = "Enable Tailscale" },
                )
            }
        }
    }
}

/** Uses the same reading surfaces and type as the rest of the app; no private values in summaries. */
@Composable
internal fun TailscaleSettingsCard(
    hasSubnets: Boolean,
    hasHostname: Boolean,
    enabled: Boolean,
    onSubnets: () -> Unit,
    onHostname: () -> Unit,
    onTerminalConfig: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        ListItem(
            headlineContent = { Text("Connection settings", style = MaterialTheme.typography.bodyLarge) },
            leadingContent = { Icon(Icons.Outlined.Tune, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Collapse connection settings" else "Expand connection settings") },
            modifier = Modifier.clickable { expanded = !expanded },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        AnimatedVisibility(visible = expanded, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)) {
                ListItem(
                    headlineContent = { Text("Home network", style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text(if (hasSubnets) "Configured" else "Not configured", style = MaterialTheme.typography.bodyMedium) },
                    leadingContent = { Icon(Icons.Outlined.Router, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable(enabled = enabled, onClick = onSubnets),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
                ListItem(
                    headlineContent = { Text("Device name", style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = { Text(if (hasHostname) "Configured" else "Not configured", style = MaterialTheme.typography.bodyMedium) },
                    leadingContent = { Icon(Icons.Outlined.Badge, null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable(enabled = enabled, onClick = onHostname),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }
        ListItem(
            headlineContent = { Text("Terminal appearance", style = MaterialTheme.typography.bodyLarge) },
            leadingContent = { Icon(Icons.Default.Terminal, null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { KnineChevron() },
            modifier = Modifier.clickable(onClick = onTerminalConfig),
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
    }
}

@Composable
private fun HomeFieldDialog(
    title: String,
    hint: String,
    initial: String,
    check: (String) -> String?,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var visible by remember { mutableStateOf(false) }
    val problem = check(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(hint, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (visible) "Hide content" else "Show content",
                            )
                        }
                    },
                    isError = problem != null && text.isNotBlank(),
                    supportingText = if (problem != null && text.isNotBlank()) {
                        { Text(problem) }
                    } else {
                        null
                    },
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = problem == null) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
