package io.nekohasekai.sfa.compose.screen.settings

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FilterAlt
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.SelectableMessageDialog
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.component.KnineNavRow
import io.nekohasekai.sfa.compose.component.KnineRowGroup
import io.nekohasekai.sfa.compose.theme.KnineMotion
import io.nekohasekai.sfa.compose.theme.KnineSectionLabel
import io.nekohasekai.sfa.compose.theme.ServiceRunning
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.utils.DetectionResult
import io.nekohasekai.sfa.utils.HookModuleUpdateNotifier
import io.nekohasekai.sfa.utils.HookStatusClient
import io.nekohasekai.sfa.utils.PrivilegeSettingsClient
import io.nekohasekai.sfa.utils.VpnDetectionTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/**
 * Settings → Privileged enhancement (LSPosed): hides the VPN from chosen apps. The module's
 * state, the apps it hides from, the interface's name, and a test of what apps can still see.
 * The prefix is edited and the test read in place, under their rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivilegeSettingsScreen(navController: NavController, serviceStatus: Status = Status.Stopped) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.privilege_settings)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)
    val systemHookStatus by HookStatusClient.status.collectAsState()
    var privilegeSettingsEnabled by remember { mutableStateOf(Settings.privilegeSettingsEnabled) }
    var interfaceRenameEnabled by remember { mutableStateOf(Settings.privilegeSettingsInterfaceRenameEnabled) }
    var interfacePrefix by remember { mutableStateOf(Settings.privilegeSettingsInterfacePrefix) }
    var editingPrefix by remember { mutableStateOf(false) }
    var interfacePrefixInput by remember { mutableStateOf(interfacePrefix) }
    var testRunning by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<DetectionResult?>(null) }
    // A failure, said in a dialog whose text can be copied.
    var failureMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        HookStatusClient.refresh()
    }

    val hasPendingDowngrade = HookModuleUpdateNotifier.isDowngrade(systemHookStatus)
    val hasPendingUpdate = HookModuleUpdateNotifier.isUpgrade(systemHookStatus)
    val hasPendingChange = hasPendingDowngrade || hasPendingUpdate
    LaunchedEffect(systemHookStatus) {
        HookModuleUpdateNotifier.maybeNotify(context, systemHookStatus)
    }

    /** Hands the settings to the module, then reloads the service, or says why it could not. */
    fun syncAndReload() {
        scope.launch {
            val failure = withContext(Dispatchers.IO) { PrivilegeSettingsClient.sync() }
            if (failure != null) {
                failureMessage = failure.message ?: failure.toString()
            } else {
                notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
            }
        }
    }

    fun savePrefix() {
        val normalized = normalizeInterfacePrefix(interfacePrefixInput)
        editingPrefix = false
        if (normalized == interfacePrefix) return
        interfacePrefix = normalized
        scope.launch {
            withContext(Dispatchers.IO) { Settings.privilegeSettingsInterfacePrefix = normalized }
            syncAndReload()
        }
    }

    failureMessage?.let { message ->
        SelectableMessageDialog(
            title = stringResource(R.string.error_title),
            message = message,
            onDismiss = { failureMessage = null },
        )
    }

    val scaffoldPadding = LocalScaffoldPadding.current
    val isLsposedActivated = systemHookStatus?.active == true
    val controlsEnabled = isLsposedActivated && !hasPendingChange

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 16.dp,
            ),
    ) {
        KnineSectionLabel(text = stringResource(R.string.privilege_module_title))
        KnineRowGroup {
            ModuleStatusRow(
                activated = isLsposedActivated,
                pendingDowngrade = hasPendingDowngrade,
                pendingUpdate = hasPendingUpdate,
            )
            // A new module only takes over after a restart.
            AnimatedVisibility(hasPendingChange, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.privilege_module_restart_action), style = MaterialTheme.typography.bodyLarge) },
                    leadingContent = {
                        Icon(imageVector = Icons.Filled.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.clickable(role = Role.Button) {
                        scope.launch {
                            val failure = withContext(Dispatchers.IO) { rebootDevice() }
                            if (failure != null) {
                                failureMessage =
                                    if (failure == "unknown" || failure.startsWith("exit=")) {
                                        context.getString(R.string.root_access_required)
                                    } else {
                                        context.getString(R.string.privilege_module_restart_failed, failure)
                                    }
                            }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        KnineSectionLabel(text = stringResource(R.string.privilege_settings_hide_title))
        KnineRowGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.enabled),
                icon = Icons.Outlined.FilterAlt,
                checked = privilegeSettingsEnabled,
                enabled = controlsEnabled,
                onCheckedChange = { checked ->
                    privilegeSettingsEnabled = checked
                    // Hiding needs the interface renamed, so turning it on turns that on too.
                    if (checked) interfaceRenameEnabled = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            Settings.privilegeSettingsEnabled = checked
                            if (checked) Settings.privilegeSettingsInterfaceRenameEnabled = true
                        }
                        syncAndReload()
                    }
                },
            )
            AnimatedVisibility(privilegeSettingsEnabled, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                KnineNavRow(
                    title = stringResource(R.string.privilege_settings_hide_manage),
                    icon = Icons.Outlined.AppShortcut,
                    enabled = controlsEnabled,
                    modifier = Modifier.alpha(if (controlsEnabled) 1f else DISABLED_ROW_ALPHA),
                    onClick = { navController.navigate("settings/privilege/manage") },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        KnineSectionLabel(text = stringResource(R.string.privilege_settings_interface_rename_title))
        KnineRowGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.enabled),
                icon = Icons.Outlined.DriveFileRenameOutline,
                checked = interfaceRenameEnabled,
                enabled = controlsEnabled,
                onCheckedChange = { checked ->
                    interfaceRenameEnabled = checked
                    if (!checked) editingPrefix = false
                    scope.launch {
                        withContext(Dispatchers.IO) { Settings.privilegeSettingsInterfaceRenameEnabled = checked }
                        syncAndReload()
                    }
                },
            )
            AnimatedVisibility(interfaceRenameEnabled, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                Column {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.privilege_settings_interface_prefix), style = MaterialTheme.typography.bodyLarge) },
                        supportingContent = { Text(interfacePrefix, style = MaterialTheme.typography.bodyMedium) },
                        leadingContent = {
                            Icon(imageVector = Icons.Outlined.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        },
                        modifier = Modifier
                            .alpha(if (controlsEnabled) 1f else DISABLED_ROW_ALPHA)
                            .clickable(enabled = controlsEnabled, role = Role.Button) {
                                interfacePrefixInput = interfacePrefix
                                editingPrefix = !editingPrefix
                            },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    AnimatedVisibility(editingPrefix && controlsEnabled, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = interfacePrefixInput,
                                onValueChange = { interfacePrefixInput = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text(stringResource(R.string.privilege_settings_interface_prefix)) },
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { savePrefix() }),
                            )
                            TextButton(onClick = { savePrefix() }) { Text(stringResource(R.string.save)) }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        KnineSectionLabel(text = stringResource(R.string.privilege_settings_vpn_detection_title))
        KnineRowGroup {
            val testEnabled = !hasPendingChange && !testRunning
            ListItem(
                headlineContent = { Text(stringResource(R.string.privilege_settings_hide_test), style = MaterialTheme.typography.bodyLarge) },
                supportingContent = if (testRunning) {
                    { Text(stringResource(R.string.privilege_settings_hide_test_running), style = MaterialTheme.typography.bodyMedium) }
                } else {
                    null
                },
                leadingContent = {
                    Icon(imageVector = Icons.Outlined.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                },
                trailingContent = if (testRunning) {
                    { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
                modifier = Modifier
                    .alpha(if (hasPendingChange) DISABLED_ROW_ALPHA else 1f)
                    .clickable(enabled = testEnabled, role = Role.Button) {
                        testRunning = true
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) { VpnDetectionTest.runDetection(context) }
                            testRunning = false
                        }
                    },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
            val result = testResult
            AnimatedVisibility(result != null, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                if (result != null) DetectionResults(result)
            }
        }
    }
}

/** Whether LSPosed runs this module, and whether a different version waits for a restart. */
@Composable
private fun ModuleStatusRow(activated: Boolean, pendingDowngrade: Boolean, pendingUpdate: Boolean) {
    val label = when {
        pendingDowngrade -> R.string.lsposed_module_pending_downgrade
        pendingUpdate -> R.string.lsposed_module_pending_update
        activated -> R.string.lsposed_module_activated
        else -> R.string.lsposed_module_not_activated
    }
    val fine = activated && !pendingDowngrade && !pendingUpdate
    val tint = when {
        pendingDowngrade -> MaterialTheme.colorScheme.error
        pendingUpdate -> Color(0xFFFFC107)
        activated -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.error
    }
    ListItem(
        headlineContent = { Text(stringResource(label), style = MaterialTheme.typography.bodyLarge) },
        leadingContent = {
            Icon(imageVector = Icons.Outlined.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        },
        trailingContent = {
            Icon(imageVector = if (fine) Icons.Outlined.CheckBox else Icons.Outlined.WarningAmber, contentDescription = null, tint = tint)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/**
 * What the test found, under its row: what Android's own interfaces give away, then what a
 * native lookup does. Each says Not detected, or what it saw.
 */
@Composable
private fun DetectionResults(result: DetectionResult) {
    // Lined up with the rows' titles, past the icon column.
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        DetectionLine(
            title = "Framework",
            seen = listOfNotNull(
                result.frameworkDetected.takeIf { it.isNotEmpty() }?.joinToString(", "),
                result.frameworkInterfaces.takeIf { it.isNotEmpty() }?.joinToString(", "),
                result.httpProxy?.takeIf { it.isNotBlank() },
            ).takeIf { result.frameworkDetected.isNotEmpty() },
        )
        DetectionLine(
            title = "Native",
            seen = listOfNotNull(
                "getifaddrs()",
                result.nativeInterfaces.takeIf { it.isNotEmpty() }?.joinToString(", "),
            ).takeIf { result.nativeDetected },
        )
    }
}

/** One check's outcome: [seen] holds what gave the VPN away, or is null when nothing did. */
@Composable
private fun DetectionLine(title: String, seen: List<String>?) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (seen == null) R.string.privilege_settings_hide_test_not_detected else R.string.kone_settings_detected),
                style = MaterialTheme.typography.bodyMedium,
                color = if (seen == null) ServiceRunning else MaterialTheme.colorScheme.error,
            )
        }
        seen?.forEach { line ->
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * The prefix as the module takes it: letters, digits and underscores only, and "en" when nothing
 * is left, as an interface needs a name.
 */
internal fun normalizeInterfacePrefix(input: String): String =
    input.trim().filter { it.isLetterOrDigit() || it == '_' }.ifEmpty { "en" }

/** Restarts the phone through root; null when that worked, otherwise what went wrong. */
private fun rebootDevice(): String? = runCatching {
    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "/system/bin/svc power reboot || /system/bin/reboot"))
    val error = process.errorStream.bufferedReader().use { it.readText().trim() }
    process.inputStream.close()
    process.outputStream.close()
    val code = process.waitFor()
    if (code == 0) null else error.ifBlank { "exit=$code" }
}.getOrElse { it.message ?: "unknown" }
