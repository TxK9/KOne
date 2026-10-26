package io.nekohasekai.sfa.compose.screen.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.RootClient
import io.nekohasekai.sfa.compose.base.GlobalEventBus
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.component.KnineNavRow
import io.nekohasekai.sfa.compose.component.KnineRowGroup
import io.nekohasekai.sfa.compose.screen.profileoverride.PerAppProxyScanner
import io.nekohasekai.sfa.compose.theme.KnineMotion
import io.nekohasekai.sfa.compose.theme.KnineSectionLabel
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.vendor.PackageQueryManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog as AlertDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileOverrideScreen(
    navController: NavController,
    serviceStatus: Status = Status.Stopped,
) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.profile_override)) },
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

    var autoRedirect by remember { mutableStateOf(Settings.autoRedirect) }
    var perAppProxyEnabled by remember { mutableStateOf(Settings.perAppProxyEnabled) }
    var managedModeEnabled by remember { mutableStateOf(Settings.perAppProxyManagedMode) }
    var isScanning by remember { mutableStateOf(false) }
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)
    val applyManagedChange = rememberApplyServiceChangeNotifier(serviceStatus, automatic = true)

    fun scanAndSaveManagedList(shouldNotify: Boolean = false, enableManaged: Boolean = false) {
        isScanning = true
        scope.launch {
            try {
                val chinaApps = PerAppProxyScanner.scanAllChinaApps()
                withContext(Dispatchers.IO) {
                    Settings.perAppProxyManagedList = chinaApps
                    if (enableManaged) Settings.perAppProxyManagedMode = true
                }
                if (shouldNotify) applyManagedChange(UiEvent.ApplyServiceChange.Mode.Reload)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                managedModeEnabled = Settings.perAppProxyManagedMode
                GlobalEventBus.tryEmit(UiEvent.ErrorMessage(context.getString(R.string.kone_settings_managed_failed)))
            } finally { isScanning = false }
        }
    }

    var showShizukuDialog by remember { mutableStateOf(false) }
    var showRootDialog by remember { mutableStateOf(false) }

    val showModeSelector = PackageQueryManager.showModeSelector
    var packageQueryMode by remember { mutableStateOf(Settings.perAppProxyPackageQueryMode) }
    val useRootMode = packageQueryMode == Settings.PACKAGE_QUERY_MODE_ROOT

    val isShizukuInstalled by PackageQueryManager.shizukuInstalled.collectAsState()
    val isShizukuBinderReady by PackageQueryManager.shizukuBinderReady.collectAsState()
    val isShizukuPermissionGranted by PackageQueryManager.shizukuPermissionGranted.collectAsState()
    val isShizukuAvailable = isShizukuBinderReady && isShizukuPermissionGranted
    var isShizukuStateInitialized by remember(showModeSelector) { mutableStateOf(!showModeSelector) }

    DisposableEffect(showModeSelector) {
        if (showModeSelector) {
            isShizukuStateInitialized = false
            PackageQueryManager.registerListeners()
            PackageQueryManager.refreshShizukuState()
            isShizukuStateInitialized = true
        }
        onDispose {
            if (showModeSelector) {
                PackageQueryManager.unregisterListeners()
                isShizukuStateInitialized = false
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, showModeSelector) {
        if (!showModeSelector) return@DisposableEffect onDispose { }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                PackageQueryManager.refreshShizukuState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Auto-disable per-app proxy if Shizuku authorization is revoked (only when using Shizuku mode)
    LaunchedEffect(isShizukuAvailable, useRootMode, isShizukuStateInitialized, perAppProxyEnabled, showModeSelector) {
        if (
            showModeSelector &&
            !useRootMode &&
            isShizukuStateInitialized &&
            perAppProxyEnabled &&
            !PackageQueryManager.isShizukuAvailable()
        ) {
            perAppProxyEnabled = false
            withContext(Dispatchers.IO) {
                Settings.perAppProxyEnabled = false
            }
        }
    }

    // Auto-close dialog and enable feature when Shizuku becomes available
    LaunchedEffect(isShizukuAvailable) {
        if (showModeSelector && isShizukuAvailable && showShizukuDialog) {
            showShizukuDialog = false
            perAppProxyEnabled = true
            withContext(Dispatchers.IO) {
                Settings.perAppProxyEnabled = true
            }
            if (managedModeEnabled) {
                scanAndSaveManagedList(shouldNotify = true)
            } else {
                notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
            }
        }
    }

    val scaffoldPadding = LocalScaffoldPadding.current

    // Picking how installed apps are read: only while the per-app proxy is off, as the lists depend on it.
    fun pickQueryMode(mode: String) {
        if (mode == packageQueryMode) return
        packageQueryMode = mode
        PackageQueryManager.setQueryMode(mode)
        scope.launch(Dispatchers.IO) { Settings.perAppProxyPackageQueryMode = mode }
        if (
            mode == Settings.PACKAGE_QUERY_MODE_SHIZUKU &&
            perAppProxyEnabled &&
            isShizukuStateInitialized &&
            !PackageQueryManager.isShizukuAvailable()
        ) {
            perAppProxyEnabled = false
            scope.launch(Dispatchers.IO) { Settings.perAppProxyEnabled = false }
        }
        notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
    }

    val canUsePerAppProxy = !showModeSelector || useRootMode || isShizukuAvailable

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 16.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 16.dp,
            ),
    ) {
        KnineRowGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.auto_redirect),
                icon = Icons.Outlined.Route,
                checked = autoRedirect,
                onCheckedChange = { checked ->
                    if (checked) {
                        // Needs root: asked first, and said in a toast when it is missing.
                        scope.launch {
                            if (RootClient.checkRootAvailable()) {
                                autoRedirect = true
                                withContext(Dispatchers.IO) { Settings.autoRedirect = true }
                                notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                            } else {
                                Toast.makeText(context, context.getString(R.string.root_access_required), Toast.LENGTH_LONG).show()
                            }
                        }
                    } else {
                        autoRedirect = false
                        scope.launch {
                            withContext(Dispatchers.IO) { Settings.autoRedirect = false }
                            notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(8.dp))
        KnineSectionLabel(text = stringResource(R.string.per_app_proxy))
        KnineRowGroup {
            // How installed apps are read, when Android hides them from ordinary apps.
            if (showModeSelector) {
                SettingsPickRow(
                    title = stringResource(R.string.per_app_proxy_package_query_mode),
                    icon = Icons.Outlined.Tune,
                    options = listOf("Shizuku", "Root"),
                    selected = if (useRootMode) 1 else 0,
                    enabled = !perAppProxyEnabled,
                    onSelect = { pickQueryMode(if (it == 1) Settings.PACKAGE_QUERY_MODE_ROOT else Settings.PACKAGE_QUERY_MODE_SHIZUKU) },
                )
            }

            SettingsSwitchRow(
                title = stringResource(R.string.enabled),
                icon = Icons.Outlined.FilterList,
                checked = perAppProxyEnabled,
                enabled = !isScanning,
                onCheckedChange = { checked ->
                    if (checked && showModeSelector) {
                        if (useRootMode) showRootDialog = true else showShizukuDialog = true
                    } else {
                        perAppProxyEnabled = checked
                        scope.launch {
                            withContext(Dispatchers.IO) { Settings.perAppProxyEnabled = checked }
                            if (!checked || !managedModeEnabled) notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                        }
                        if (checked && managedModeEnabled) scanAndSaveManagedList(shouldNotify = true)
                    }
                },
            )

            AnimatedVisibility(perAppProxyEnabled && canUsePerAppProxy, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                Column {
                    // In managed mode it edits the apps kept out of management.
                    KnineNavRow(
                        title = stringResource(if (managedModeEnabled) R.string.kone_settings_managed_exceptions else R.string.per_app_proxy_manage),
                        icon = Icons.Outlined.AppShortcut,
                        onClick = {
                            navController.navigate(if (managedModeEnabled) "settings/profile_override/managed" else "settings/profile_override/manage")
                        },
                    )
                    SettingsSwitchRow(
                        title = stringResource(R.string.kone_settings_exclude_china_apps),
                        icon = Icons.Outlined.SmartToy,
                        checked = managedModeEnabled,
                        busy = isScanning,
                        onCheckedChange = { checked ->
                            managedModeEnabled = checked
                            if (checked) {
                                scanAndSaveManagedList(shouldNotify = true, enableManaged = true)
                            } else {
                                scope.launch {
                                    withContext(Dispatchers.IO) { Settings.perAppProxyManagedMode = false }
                                    applyManagedChange(UiEvent.ApplyServiceChange.Mode.Reload)
                                }
                            }
                        },
                    )
                }
            }
        }

        // Shizuku dialog
        if (showShizukuDialog) {
            AlertDialog(
                onDismissRequest = { showShizukuDialog = false },
                title = {
                    Text(stringResource(R.string.per_app_proxy))
                },
                text = {
                    Text(stringResource(R.string.per_app_proxy_shizuku_required))
                },
                confirmButton = {
                    when {
                        isShizukuAvailable -> {
                            TextButton(
                                onClick = {
                                    showShizukuDialog = false
                                    perAppProxyEnabled = true
                                    scope.launch(Dispatchers.IO) {
                                        Settings.perAppProxyEnabled = true
                                        if (!managedModeEnabled) {
                                            withContext(Dispatchers.Main) {
                                                notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                                            }
                                        }
                                    }
                                    if (managedModeEnabled) {
                                        scanAndSaveManagedList(shouldNotify = true)
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.ok))
                            }
                        }
                        isShizukuBinderReady -> {
                            TextButton(
                                onClick = {
                                    PackageQueryManager.requestShizukuPermission()
                                },
                            ) {
                                Text(stringResource(R.string.request_shizuku))
                            }
                        }
                        isShizukuInstalled -> {
                            TextButton(
                                onClick = {
                                    showShizukuDialog = false
                                    val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                    if (intent != null) {
                                        context.startActivity(intent)
                                    }
                                },
                            ) {
                                Text(stringResource(R.string.start_shizuku))
                            }
                        }
                        else -> {
                            TextButton(
                                onClick = {
                                    showShizukuDialog = false
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/"))
                                    context.startActivity(intent)
                                },
                            ) {
                                Text(stringResource(R.string.get_shizuku))
                            }
                        }
                    }
                },
                dismissButton = {
                    if (!isShizukuAvailable) {
                        TextButton(
                            onClick = { showShizukuDialog = false },
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                },
            )
        }

        // ROOT dialog
        if (showRootDialog) {
            AlertDialog(
                onDismissRequest = { showRootDialog = false },
                title = {
                    Text(stringResource(R.string.per_app_proxy))
                },
                text = {
                    Text(stringResource(R.string.per_app_proxy_root_required))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                val hasRoot = PackageQueryManager.checkRootAvailable()
                                if (hasRoot) {
                                    showRootDialog = false
                                    perAppProxyEnabled = true
                                    withContext(Dispatchers.IO) {
                                        Settings.perAppProxyEnabled = true
                                    }
                                    if (managedModeEnabled) {
                                        scanAndSaveManagedList(shouldNotify = true)
                                    } else {
                                        notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                                    }
                                } else {
                                    showRootDialog = false
                                    Toast.makeText(
                                        context,
                                        R.string.root_access_denied,
                                        Toast.LENGTH_LONG,
                                    ).show()
                                }
                            }
                        },
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showRootDialog = false },
                    ) {
                        Text(stringResource(R.string.cancel))
                    }
                },
            )
        }

    }
}
