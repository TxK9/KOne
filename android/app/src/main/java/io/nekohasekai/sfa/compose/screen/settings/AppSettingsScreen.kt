package io.nekohasekai.sfa.compose.screen.settings

import android.app.LocaleConfig
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import io.nekohasekai.sfa.selfuse.KoneBackup
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog as AlertDialog
import io.nekohasekai.sfa.compose.theme.KnineBadge as Badge
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import io.nekohasekai.sfa.compose.theme.KnineDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import androidx.compose.material3.RadioButton
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
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
import androidx.compose.ui.semantics.Role
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.KnineAppearance
import io.nekohasekai.sfa.compose.theme.KnineInkStyle
import io.nekohasekai.sfa.compose.theme.KnineSectionLabel
import io.nekohasekai.sfa.compose.theme.KnineSwitcherStyle
import io.nekohasekai.sfa.compose.theme.KnineThemeMode
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.component.UpdateAvailableDialog
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.clipboardText
import io.nekohasekai.sfa.update.UpdateCheckException
import io.nekohasekai.sfa.update.UpdateSource
import io.nekohasekai.sfa.update.UpdateState
import io.nekohasekai.sfa.update.UpdateTrack
import io.nekohasekai.sfa.utils.HookStatusClient
import io.nekohasekai.sfa.vendor.Vendor
import io.nekohasekai.sfa.xposed.XposedActivation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Locale
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.DarkMode
import io.nekohasekai.sfa.compose.theme.WallpaperCatalog
import io.nekohasekai.sfa.compose.theme.WallpaperMode

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AppSettingsScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.title_app_settings)) },
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
    var systemVpnProtected by remember { mutableStateOf(io.nekohasekai.sfa.connection.SystemVpnProtection.enabled()) }
    val vpnProtectionLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(vpnProtectionLifecycle, serviceStatus) {
        systemVpnProtected = io.nekohasekai.sfa.connection.SystemVpnProtection.enabled()
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                systemVpnProtected = io.nekohasekai.sfa.connection.SystemVpnProtection.enabled()
            }
        }
        vpnProtectionLifecycle.lifecycle.addObserver(observer)
        onDispose { vpnProtectionLifecycle.lifecycle.removeObserver(observer) }
    }
    // Show the app version and KOne's core display label in the same entry.
    val appName = stringResource(R.string.app_name)
    val versionLine = remember {
        "$appName ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}\nCore KOne0.0.1"
    }
    val hasUpdate by UpdateState.hasUpdate
    val updateInfo by UpdateState.updateInfo
    val isChecking by UpdateState.isChecking
    val koneUpdate by io.nekohasekai.sfa.update.KoneUpdates.state.collectAsState()
    var checkingKoneUpdate by remember { mutableStateOf(false) }
    // The last manual check's outcome, said on the row instead of in a passing toast.
    var koneCheckOutcome by remember { mutableStateOf<Boolean?>(null) }
    // Read once and again on return, as signing in happens on the console's page.
    var ownerNeedsSignIn by remember { mutableStateOf(BuildConfig.KNINE_OWNER && io.nekohasekai.sfa.update.OwnerUpdateAccess.credential(context) == null) }
    fun checkKoneUpdate() {
        if (BuildConfig.KNINE_OWNER && io.nekohasekai.sfa.update.OwnerUpdateAccess.credential(context) == null) {
            context.startActivity(android.content.Intent(context, io.nekohasekai.sfa.compose.screen.console.ConsoleActivity::class.java))
            return
        }
        if (io.nekohasekai.sfa.update.KoneUpdates.state.value.release != null) {
            io.nekohasekai.sfa.update.KoneUpdates.show()
            return
        }
        if (checkingKoneUpdate) return
        checkingKoneUpdate = true
        scope.launch {
            try {
                val checked = io.nekohasekai.sfa.update.KoneUpdates.check(context.applicationContext)
                io.nekohasekai.sfa.update.KoneUpdates.show()
                koneCheckOutcome = checked
            } finally { checkingKoneUpdate = false }
        }
    }
    var showSourceDialog by remember { mutableStateOf(false) }
    var currentSource by remember { mutableStateOf(Settings.updateSource) }
    var showTrackDialog by remember { mutableStateOf(false) }
    var currentTrack by remember { mutableStateOf(Settings.updateTrack) }
    var showGitHubTokenDialog by remember { mutableStateOf(false) }
    var githubToken by remember { mutableStateOf(Settings.githubToken) }
    var checkUpdateEnabled by remember { mutableStateOf(Settings.checkUpdateEnabled) }
    var showErrorDialog by remember { mutableStateOf<String?>(null) }

    var silentInstallEnabled by remember { mutableStateOf(Settings.silentInstallEnabled) }
    var silentInstallMethod by remember { mutableStateOf(Settings.silentInstallMethod) }
    val systemHookStatus by HookStatusClient.status.collectAsState()
    val xposedActivated = systemHookStatus?.active == true || XposedActivation.isActivated(context)
    var isMethodAvailable by remember { mutableStateOf(true) }
    var autoUpdateEnabled by remember { mutableStateOf(Settings.autoUpdateEnabled) }
    var showInstallMethodMenu by remember { mutableStateOf(false) }
    var isVerifyingMethod by remember { mutableStateOf(false) }
    var silentInstallError by remember { mutableStateOf<String?>(null) }

    var showDownloadDialog by remember { mutableStateOf(false) }
    var downloadJob by remember { mutableStateOf<Job?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }
    var showUpdateAvailableDialog by remember { mutableStateOf(false) }
    var showVersionMenu by remember { mutableStateOf(false) }

    var notificationEnabled by remember { mutableStateOf(true) }
    var dynamicNotification by remember { mutableStateOf(Settings.dynamicNotification) }
    var showDisableNotificationDialog by remember { mutableStateOf(false) }
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)

    var showLanguageDialog by remember { mutableStateOf(false) }
    val availableLocales = remember { getSupportedLocales(context) }
    var currentLocaleTag by remember {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        mutableStateOf(if (appLocales.isEmpty) "" else appLocales.toLanguageTags())
    }

    var cacheSize by remember { mutableStateOf(0L) }
    var cacheSizeText by remember { mutableStateOf("") }

    fun refreshCacheSize() {
        scope.launch(Dispatchers.IO) {
            val size = calculateDirSize(context.cacheDir)
            withContext(Dispatchers.Main) {
                cacheSize = size
                cacheSizeText = Formatter.formatFileSize(context, size)
            }
        }
    }

    LaunchedEffect(Unit) {
        HookStatusClient.refresh()
        refreshCacheSize()
    }

    // Re-check states when returning from background (e.g., after granting permission)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        HookStatusClient.refresh()
        ownerNeedsSignIn = BuildConfig.KNINE_OWNER && io.nekohasekai.sfa.update.OwnerUpdateAccess.credential(context) == null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Application.notification.createNotificationChannel(
                NotificationChannel(
                    "service",
                    "Service Notifications",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val channel = Application.notification.getNotificationChannel("service")
            notificationEnabled = channel?.importance != NotificationManager.IMPORTANCE_NONE
        } else {
            notificationEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        if (silentInstallEnabled) {
            scope.launch {
                val success = withContext(Dispatchers.IO) {
                    Vendor.verifySilentInstallMethod(silentInstallMethod)
                }
                isMethodAvailable = success
                silentInstallError = if (success) {
                    null
                } else {
                    when (silentInstallMethod) {
                        "PACKAGE_INSTALLER" -> context.getString(R.string.package_installer_not_available)
                        "SHIZUKU" -> context.getString(R.string.shizuku_not_available)
                        else -> context.getString(R.string.silent_install_verify_failed, silentInstallMethod)
                    }
                }
            }
        }
    }

    if (showSourceDialog) {
        UpdateSourceDialog(
            currentSource = currentSource,
            onSourceSelected = { source ->
                currentSource = source
                UpdateState.clear()
                scope.launch(Dispatchers.IO) {
                    Settings.updateSource = source
                }
                showSourceDialog = false
            },
            onDismiss = { showSourceDialog = false },
        )
    }

    if (showTrackDialog) {
        UpdateTrackDialog(
            currentTrack = currentTrack,
            onTrackSelected = { track ->
                currentTrack = track
                UpdateState.clear()
                scope.launch(Dispatchers.IO) {
                    Settings.updateTrack = track
                }
                showTrackDialog = false
            },
            onDismiss = { showTrackDialog = false },
        )
    }

    if (showGitHubTokenDialog) {
        GitHubTokenDialog(
            currentToken = githubToken,
            onSave = { token ->
                githubToken = token
                scope.launch(Dispatchers.IO) {
                    Settings.githubToken = token
                }
                showGitHubTokenDialog = false
            },
            onDismiss = { showGitHubTokenDialog = false },
        )
    }

    showErrorDialog?.let { message ->
        AlertDialog(
            onDismissRequest = { showErrorDialog = null },
            title = { Text(stringResource(R.string.check_update)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { showErrorDialog = null }) {
                    Text(stringResource(R.string.ok))
                }
            },
        )
    }

    if (showDownloadDialog) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update)) },
            text = {
                Column {
                    if (downloadError != null) {
                        Text(
                            downloadError!!,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        val progress by UpdateState.downloadProgress
                        val progressValue = progress
                        Column {
                            if (progressValue != null) {
                                Text("${stringResource(R.string.downloading)} ${(progressValue * 100).toInt()}%")
                            } else {
                                Text(stringResource(R.string.downloading))
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            if (progressValue != null) {
                                LinearProgressIndicator(
                                    progress = { progressValue },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        downloadJob?.cancel()
                        downloadJob = null
                        showDownloadDialog = false
                        downloadError = null
                        UpdateState.downloadProgress.value = null
                    },
                ) {
                    Text(stringResource(if (downloadError != null) R.string.ok else android.R.string.cancel))
                }
            },
        )
    }

    if (showInstallMethodMenu) {
        InstallMethodDialog(
            currentMethod = silentInstallMethod,
            onMethodSelected = { method ->
                showInstallMethodMenu = false
                if (silentInstallMethod == method) return@InstallMethodDialog
                silentInstallMethod = method
                Settings.silentInstallMethod = method
                isVerifyingMethod = true
                scope.launch {
                    val success = withContext(Dispatchers.IO) {
                        Vendor.verifySilentInstallMethod(method)
                    }
                    isVerifyingMethod = false
                    isMethodAvailable = success
                    silentInstallError = if (success) {
                        null
                    } else {
                        when (method) {
                            "PACKAGE_INSTALLER" -> context.getString(R.string.package_installer_not_available)
                            "SHIZUKU" -> context.getString(R.string.shizuku_not_available)
                            else -> context.getString(R.string.silent_install_verify_failed, method)
                        }
                    }
                }
            },
            onDismiss = { showInstallMethodMenu = false },
        )
    }

    if (showDisableNotificationDialog) {
        AlertDialog(
            onDismissRequest = { showDisableNotificationDialog = false },
            title = { Text(stringResource(R.string.enable_notification)) },
            text = {
                Text(
                    stringResource(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            R.string.disable_notification_description
                        } else {
                            R.string.disable_notification_description_legacy
                        },
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisableNotificationDialog = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startActivity(
                            Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                                putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                                putExtra(AndroidSettings.EXTRA_CHANNEL_ID, "service")
                            },
                        )
                    } else {
                        context.startActivity(
                            Intent(
                                AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}"),
                            ),
                        )
                    }
                }) {
                    Text(stringResource(R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableNotificationDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (showUpdateAvailableDialog && updateInfo != null) {
        UpdateAvailableDialog(
            updateInfo = updateInfo!!,
            onDismiss = { showUpdateAvailableDialog = false },
            onUpdate = {
                showDownloadDialog = true
                downloadError = null
                downloadJob = scope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            Vendor.downloadAndInstall(context, updateInfo!!.downloadUrl)
                        }
                        showDownloadDialog = false
                    } catch (e: Exception) {
                        Log.e("AppSettingsScreen", "Error downloading update", e)
                        downloadError = e.message
                    }
                }
            },
        )
    }

    if (showLanguageDialog) {
        LanguageDialog(
            currentTag = currentLocaleTag,
            availableLocales = availableLocales,
            onLocaleSelected = { tag ->
                currentLocaleTag = tag
                val localeList = if (tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tag)
                }
                AppCompatDelegate.setApplicationLocales(localeList)
                showLanguageDialog = false
            },
            onDismiss = { showLanguageDialog = false },
        )
    }

    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            )
            .verticalScroll(rememberScrollState()),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            ListItem(
                headlineContent = { Text(stringResource(R.string.kone_app_block_without_vpn), style = MaterialTheme.typography.bodyLarge) },
                supportingContent = {
                    Text(
                        stringResource(if (systemVpnProtected == true) R.string.kone_app_block_without_vpn_on else R.string.kone_app_block_without_vpn_off),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.VpnLock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                modifier = Modifier.clickable(role = Role.Button) {
                    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) }
                        .onFailure { Toast.makeText(context, context.getString(R.string.kone_app_vpn_settings_missing), Toast.LENGTH_SHORT).show() }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        // Info Card
        Card(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column {
                Box {
                    ListItem(
                        headlineContent = {
                            Text(
                                stringResource(R.string.app_version_title),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        },
                        supportingContent = {
                            Text(
                                versionLine,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            if (koneUpdate.release != null) Badge(containerColor = MaterialTheme.colorScheme.primary)
                        },
                        modifier =
                        Modifier
                            .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                            .combinedClickable(
                                onClick = { showVersionMenu = true },
                                onLongClick = { showVersionMenu = true },
                            ),
                        colors =
                        ListItemDefaults.colors(
                            containerColor = Color.Transparent,
                        ),
                    )
                    Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                        DropdownMenu(
                            expanded = showVersionMenu,
                            onDismissRequest = { showVersionMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.per_app_proxy_action_copy)) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = null,
                                    )
                                },
                                onClick = {
                                    clipboardText = versionLine
                                    Toast.makeText(
                                        context,
                                        R.string.copied_to_clipboard,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                    showVersionMenu = false
                                },
                            )
                        }
                    }
                }


                if (io.nekohasekai.sfa.update.KoneUpdates.enabled) {
                    val release = koneUpdate.release
                    val status = when {
                        checkingKoneUpdate -> stringResource(R.string.kone_update_checking)
                        release != null -> {
                            val line = io.nekohasekai.sfa.update.updateLine(koneUpdate, release)
                            stringResource(R.string.kone_update_ready, appName, release.versionName, listOfNotNull(line.title, line.value).joinToString(" "))
                        }
                        ownerNeedsSignIn -> stringResource(R.string.kone_update_sign_in)
                        koneCheckOutcome == true -> stringResource(R.string.kone_update_up_to_date)
                        koneCheckOutcome == false -> stringResource(R.string.kone_update_check_failed)
                        else -> null
                    }
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.kone_update_check), style = MaterialTheme.typography.bodyLarge) },
                        supportingContent = status?.let {
                            {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                )
                            }
                        },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.SystemUpdateAlt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        trailingContent = {
                            when {
                                checkingKoneUpdate -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                                release != null -> Badge(containerColor = MaterialTheme.colorScheme.primary)
                            }
                        },
                        modifier = Modifier.clickable(enabled = !checkingKoneUpdate, role = Role.Button) { checkKoneUpdate() },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }

                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.language),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        val displayName = if (currentLocaleTag.isEmpty()) {
                            stringResource(R.string.system_default)
                        } else {
                            val locale = Locale.forLanguageTag(currentLocaleTag)
                            locale.getDisplayName(Locale.ENGLISH).replaceFirstChar { it.uppercase(Locale.ENGLISH) }
                        }
                        Text(displayName, style = MaterialTheme.typography.bodyMedium)
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Language,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier =
                    Modifier
                        .clickable { showLanguageDialog = true },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )

                // How the categories lay out when the current one is tapped again; as small as the language row.
                var showSwitcherMenu by remember { mutableStateOf(false) }
                Box {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.kone_app_category_navigation), style = MaterialTheme.typography.bodyLarge) },
                        supportingContent = { Text(KnineAppearance.switcher.label, style = MaterialTheme.typography.bodyMedium) },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.Category,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier.clickable { showSwitcherMenu = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                        DropdownMenu(
                            expanded = showSwitcherMenu,
                            onDismissRequest = { showSwitcherMenu = false },
                        ) {
                            KnineSwitcherStyle.entries.forEach { style ->
                                DropdownMenuItem(
                                    text = { Text(style.label) },
                                    trailingIcon = if (style == KnineAppearance.switcher) {
                                        { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        KnineAppearance.switcher = style
                                        showSwitcherMenu = false
                                    },
                                )
                            }
                        }
                    }
                }

                // The wallpaper: the built-in photos when this copy has them, a photo of the
                // user's own, or none. Picking My photos again changes the photo.
                var showWallpaperMenu by remember { mutableStateOf(false) }
                val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                    if (uri != null) {
                        scope.launch {
                            try {
                                WallpaperCatalog.usePhoto(context, uri)
                            } catch (_: Exception) {
                                Toast.makeText(context, context.getString(R.string.kone_app_photo_unusable), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                Box {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.kone_app_wallpaper), style = MaterialTheme.typography.bodyLarge) },
                        supportingContent = { Text(WallpaperCatalog.mode.label, style = MaterialTheme.typography.bodyMedium) },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.Wallpaper,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier.clickable { showWallpaperMenu = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                        DropdownMenu(
                            expanded = showWallpaperMenu,
                            onDismissRequest = { showWallpaperMenu = false },
                        ) {
                            WallpaperMode.entries.filter { it != WallpaperMode.Builtin || WallpaperCatalog.hasBundledPhotos }.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    trailingIcon = if (mode == WallpaperCatalog.mode) {
                                        { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        showWallpaperMenu = false
                                        val pickNew = mode == WallpaperMode.Photo &&
                                            (WallpaperCatalog.mode == WallpaperMode.Photo || !WallpaperCatalog.photoFile.isFile)
                                        if (pickNew) {
                                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                                        } else {
                                            WallpaperCatalog.setMode(mode)
                                        }
                                    },
                                )
                            }
                        }
                    }
                }

                // Light or dark: as the phone is, or KOne's own; the paintings follow it too.
                var showThemeMenu by remember { mutableStateOf(false) }
                Box {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.kone_app_appearance), style = MaterialTheme.typography.bodyLarge) },
                        supportingContent = { Text(KnineAppearance.theme.label, style = MaterialTheme.typography.bodyMedium) },
                        leadingContent = {
                            Icon(
                                imageVector = Icons.Outlined.DarkMode,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        },
                        modifier = Modifier.clickable { showThemeMenu = true },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                    Box(modifier = Modifier.align(Alignment.BottomEnd)) {
                        DropdownMenu(
                            expanded = showThemeMenu,
                            onDismissRequest = { showThemeMenu = false },
                        ) {
                            KnineThemeMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = { Text(mode.label) },
                                    trailingIcon = if (mode == KnineAppearance.theme) {
                                        { Icon(imageVector = Icons.Filled.Check, contentDescription = null) }
                                    } else {
                                        null
                                    },
                                    onClick = {
                                        showThemeMenu = false
                                        KnineAppearance.theme = mode
                                    },
                                )
                            }
                        }
                    }
                }

                // One row: the size, and Clear beside it while there is something to clear.
                ListItem(
                    headlineContent = { Text(stringResource(R.string.kone_app_cache), style = MaterialTheme.typography.bodyLarge) },
                    supportingContent = {
                        if (cacheSizeText.isNotEmpty()) {
                            Text(cacheSizeText, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
                        }
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.DeleteSweep,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        if (cacheSize > 0L) {
                            TextButton(onClick = {
                                scope.launch(Dispatchers.IO) {
                                    context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() }
                                    withContext(Dispatchers.Main) {
                                        cacheSize = 0L
                                        cacheSizeText = Formatter.formatFileSize(context, 0L)
                                    }
                                }
                            }) { Text(stringResource(R.string.kone_app_cache_clear)) }
                        }
                    },
                    modifier = Modifier.clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        KnineSectionLabel(text = stringResource(R.string.notification_settings))

        Card(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        ) {
            Column {
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.enable_notification),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        KnineSwitch(
                            checked = notificationEnabled,
                            onCheckedChange = null,
                        )
                    },
                    modifier =
                    Modifier
                        .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                        .clickable { showDisableNotificationDialog = true },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.dynamic_notification),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    leadingContent = {
                        Icon(
                            imageVector = Icons.Outlined.Speed,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    trailingContent = {
                        KnineSwitch(
                            checked = dynamicNotification,
                            onCheckedChange = null,
                        )
                    },
                    modifier =
                    Modifier
                        .toggleable(value = dynamicNotification, role = Role.Switch) { checked ->
                            dynamicNotification = checked
                            scope.launch(Dispatchers.IO) {
                                Settings.dynamicNotification = checked
                                withContext(Dispatchers.Main) {
                                    notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Restart)
                                }
                            }
                        },
                    colors =
                    ListItemDefaults.colors(
                        containerColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

@Composable
private fun UpdateSourceDialog(
    currentSource: String,
    onSourceSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sources = listOf(
        "github" to stringResource(R.string.update_source_github),
        "fdroid" to stringResource(R.string.update_source_fdroid),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_source)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                sources.forEach { (value, label) ->
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .heightIn(min = 48.dp)
                            .selectable(selected = currentSource == value, role = Role.RadioButton) { onSourceSelected(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentSource == value,
                            onClick = null,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun UpdateTrackDialog(
    currentTrack: String,
    onTrackSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tracks = listOf(
        "stable" to stringResource(R.string.update_track_stable),
        "beta" to stringResource(R.string.update_track_beta),
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_track)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                tracks.forEach { (value, label) ->
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .heightIn(min = 48.dp)
                            .selectable(selected = currentTrack == value, role = Role.RadioButton) { onTrackSelected(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentTrack == value,
                            onClick = null,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

@Composable
private fun GitHubTokenDialog(
    currentToken: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var token by remember(currentToken) { mutableStateOf(currentToken) }
    var tokenVisible by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.github_token)) },
        text = {
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(if (tokenVisible) R.string.knine_settings_hide_secret else R.string.knine_settings_show_secret),
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(token.trim()) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun LanguageDialog(
    currentTag: String,
    availableLocales: List<Locale>,
    onLocaleSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .heightIn(min = 48.dp)
                            .selectable(selected = currentTag.isEmpty(), role = Role.RadioButton) { onLocaleSelected("") }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = currentTag.isEmpty(),
                        onClick = null,
                    )
                    Text(
                        text = stringResource(R.string.system_default),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                availableLocales.forEach { locale ->
                    val tag = locale.toLanguageTag()
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .heightIn(min = 48.dp)
                            .selectable(selected = currentTag == tag, role = Role.RadioButton) { onLocaleSelected(tag) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentTag == tag,
                            onClick = null,
                        )
                        Text(
                            text = locale.getDisplayName(Locale.ENGLISH).replaceFirstChar { it.uppercase(Locale.ENGLISH) },
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}

private fun calculateDirSize(dir: File?): Long {
    if (dir == null || !dir.exists()) return 0
    var size = 0L
    dir.listFiles()?.forEach { file ->
        size += if (file.isDirectory) calculateDirSize(file) else file.length()
    }
    return size
}

private fun getSupportedLocales(context: Context): List<Locale> {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val localeConfig = LocaleConfig(context)
        val localeList = localeConfig.supportedLocales ?: return emptyList()
        return (0 until localeList.size()).map { localeList.get(it) }
    }
    return parseLocalesConfig(context)
}

private fun parseLocalesConfig(context: Context): List<Locale> {
    val locales = mutableListOf<Locale>()
    try {
        val resId = context.resources.getIdentifier(
            "_generated_res_locale_config",
            "xml",
            context.packageName,
        )
        if (resId == 0) return emptyList()
        val parser = context.resources.getXml(resId)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                val name = parser.getAttributeValue(
                    "http://schemas.android.com/apk/res/android",
                    "name",
                )
                if (name != null) {
                    locales.add(Locale.forLanguageTag(name))
                }
            }
        }
    } catch (_: Exception) {
    }
    return locales
}

@Composable
private fun InstallMethodDialog(
    currentMethod: String,
    onMethodSelected: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val methods = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add("PACKAGE_INSTALLER" to stringResource(R.string.install_method_package_installer))
        }
        add("SHIZUKU" to stringResource(R.string.install_method_shizuku))
        add("ROOT" to stringResource(R.string.install_method_root))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.silent_install_method)) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).selectableGroup()) {
                methods.forEach { (value, label) ->
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .heightIn(min = 48.dp)
                            .selectable(selected = currentMethod == value, role = Role.RadioButton) { onMethodSelected(value) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = currentMethod == value,
                            onClick = null,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    )
}
