package io.nekohasekai.sfa.compose.screen.settings

import android.app.LocaleConfig
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.VpnLock
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.component.KnineRowGroup
import io.nekohasekai.sfa.compose.screen.console.ConsoleActivity
import io.nekohasekai.sfa.compose.theme.CityWallpapers
import io.nekohasekai.sfa.compose.theme.KnineAppearance
import io.nekohasekai.sfa.compose.theme.KnineSwitcherStyle
import io.nekohasekai.sfa.compose.theme.KnineThemeMode
import io.nekohasekai.sfa.compose.theme.WallpaperCatalog
import io.nekohasekai.sfa.compose.theme.WallpaperMode
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.connection.SystemVpnProtection
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.ktx.clipboardText
import io.nekohasekai.sfa.update.KoneUpdates
import io.nekohasekai.sfa.update.OwnerUpdateAccess
import io.nekohasekai.sfa.update.updateLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Locale
import android.provider.Settings as AndroidSettings
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.KnineBadge as Badge

/**
 * Settings → App: how KOne looks, what Android may do with it, and the version. Three cards set
 * apart by space alone: the look, the system, and the copy itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)

    // What Android says now, read again whenever KOne comes back to the front, as both change
    // in Android's own settings. Android only reports Block connections while KOne is connected.
    var systemVpnProtected by remember { mutableStateOf(SystemVpnProtection.enabled()) }
    var notificationEnabled by remember { mutableStateOf(true) }
    var ownerNeedsSignIn by remember { mutableStateOf(ownerNeedsSignIn(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, serviceStatus) {
        fun refresh() {
            systemVpnProtected = SystemVpnProtection.enabled()
            notificationEnabled = serviceNotificationEnabled(context)
            ownerNeedsSignIn = ownerNeedsSignIn(context)
        }
        refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The version as people know it; build numbers belong to the install, not the page.
    val appName = stringResource(R.string.app_name)
    val versionLine = remember(appName) { "$appName ${BuildConfig.VERSION_NAME}" }
    val koneUpdate by KoneUpdates.state.collectAsState()
    var checkingKoneUpdate by remember { mutableStateOf(false) }
    // The last manual check's outcome, said on the row instead of in a passing toast.
    var koneCheckOutcome by remember { mutableStateOf<Boolean?>(null) }
    fun checkKoneUpdate() {
        if (ownerNeedsSignIn(context)) {
            // Signing in happens on the console's page; the row reads it again on return.
            context.startActivity(Intent(context, ConsoleActivity::class.java))
            return
        }
        if (KoneUpdates.state.value.release != null) {
            KoneUpdates.show()
            return
        }
        if (checkingKoneUpdate) return
        checkingKoneUpdate = true
        scope.launch {
            try {
                val checked = KoneUpdates.check(context.applicationContext)
                KoneUpdates.show()
                koneCheckOutcome = checked
            } finally {
                checkingKoneUpdate = false
            }
        }
    }

    var dynamicNotification by remember { mutableStateOf(Settings.dynamicNotification) }

    val availableLocales = remember { getSupportedLocales(context) }
    var currentLocaleTag by remember {
        val appLocales = AppCompatDelegate.getApplicationLocales()
        mutableStateOf(if (appLocales.isEmpty) "" else appLocales.toLanguageTags())
    }

    // Measured off the main thread; null until then, so the row shows no figure rather than 0 B.
    var cacheSize by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        cacheSize = withContext(Dispatchers.IO) { calculateDirSize(context.cacheDir) }
    }

    // Picking My photos again changes the photo.
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

    val scaffoldPadding = LocalScaffoldPadding.current

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
        // The look.
        KnineRowGroup {
            // The built-in photos when this copy has them, a photo of the user's own, the living
            // cities (which take turns by themselves: the row says which one is up), or none.
            val cityName = stringResource(R.string.city_wallpaper_city)
            val modes = remember { WallpaperMode.entries.filter { it != WallpaperMode.Builtin || WallpaperCatalog.hasBundledPhotos } }
            val currentMode = WallpaperCatalog.mode
            val currentCity = if (currentMode == WallpaperMode.City) {
                remember(CityWallpapers.selected) {
                    CityWallpapers.choices(context).firstOrNull { it.id == CityWallpapers.selected }?.city
                }
            } else {
                null
            }
            SettingsPickRow(
                title = stringResource(R.string.kone_app_wallpaper),
                icon = Icons.Outlined.Wallpaper,
                options = modes.map { if (it == WallpaperMode.City) cityName else it.label },
                selected = modes.indexOf(currentMode),
                value = if (currentMode == WallpaperMode.City) listOfNotNull(cityName, currentCity).joinToString(" · ") else currentMode.label,
                onSelect = { index ->
                    val mode = modes[index]
                    val pickNew = mode == WallpaperMode.Photo &&
                        (currentMode == WallpaperMode.Photo || !WallpaperCatalog.photoFile.isFile)
                    if (pickNew) {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    } else {
                        WallpaperCatalog.setMode(mode)
                    }
                },
            )
            // Light or dark: as the phone is, or KOne's own.
            SettingsPickRow(
                title = stringResource(R.string.kone_app_appearance),
                icon = Icons.Outlined.DarkMode,
                options = KnineThemeMode.entries.map { it.label },
                selected = KnineThemeMode.entries.indexOf(KnineAppearance.theme),
                onSelect = { KnineAppearance.theme = KnineThemeMode.entries[it] },
            )
            // How the categories lay out when the current one is tapped again.
            SettingsPickRow(
                title = stringResource(R.string.kone_app_category_navigation),
                icon = Icons.Outlined.Category,
                options = KnineSwitcherStyle.entries.map { it.label },
                selected = KnineSwitcherStyle.entries.indexOf(KnineAppearance.switcher),
                onSelect = { KnineAppearance.switcher = KnineSwitcherStyle.entries[it] },
            )
            val systemDefault = stringResource(R.string.system_default)
            val localeTags = remember(availableLocales) { listOf("") + availableLocales.map { it.toLanguageTag() } }
            val localeNames = remember(availableLocales, systemDefault) { listOf(systemDefault) + availableLocales.map(::localeName) }
            SettingsPickRow(
                title = stringResource(R.string.language),
                icon = Icons.Outlined.Language,
                options = localeNames,
                selected = localeTags.indexOf(currentLocaleTag),
                value = if (currentLocaleTag.isEmpty()) systemDefault else localeName(Locale.forLanguageTag(currentLocaleTag)),
                onSelect = { index ->
                    val tag = localeTags[index]
                    if (tag != currentLocaleTag) {
                        currentLocaleTag = tag
                        AppCompatDelegate.setApplicationLocales(
                            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
                        )
                    }
                },
            )
        }

        Spacer(Modifier.height(14.dp))
        // What Android does with KOne. Android owns both of the first two; each row says where
        // it stands and opens the place to change it.
        KnineRowGroup {
            SettingsLinkRow(
                title = stringResource(R.string.kone_app_block_without_vpn),
                icon = Icons.Outlined.VpnLock,
                supportingContent = {
                    AnimatedContent(
                        targetState = systemVpnProtected,
                        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) },
                        label = "blockState",
                    ) { state ->
                        Text(
                            stringResource(
                                when (state) {
                                    true -> R.string.kone_app_block_without_vpn_on
                                    false -> R.string.kone_app_block_without_vpn_off
                                    null -> R.string.kone_app_block_without_vpn_unknown
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                onClick = {
                    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_VPN_SETTINGS)) }
                        .onFailure { Toast.makeText(context, context.getString(R.string.kone_app_vpn_settings_missing), Toast.LENGTH_SHORT).show() }
                },
            )
            SettingsLinkRow(
                title = stringResource(R.string.kone_settings_notifications),
                icon = Icons.Outlined.Notifications,
                supporting = stringResource(if (notificationEnabled) R.string.kone_app_block_without_vpn_on else R.string.kone_app_block_without_vpn_off),
                onClick = { runCatching { context.startActivity(serviceNotificationSettings(context)) } },
            )
            SettingsSwitchRow(
                title = stringResource(R.string.kone_settings_speed_in_notification),
                icon = Icons.Outlined.Speed,
                checked = dynamicNotification,
                onCheckedChange = { checked ->
                    dynamicNotification = checked
                    scope.launch {
                        withContext(Dispatchers.IO) { Settings.dynamicNotification = checked }
                        notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Restart)
                    }
                },
            )
        }

        Spacer(Modifier.height(14.dp))
        // The copy itself.
        KnineRowGroup {
            VersionRow(
                versionLine = versionLine,
                // The update row carries the dot when it is shown; otherwise the version does.
                hasUpdate = !KoneUpdates.enabled && koneUpdate.release != null,
            )

            if (KoneUpdates.enabled) {
                val release = koneUpdate.release
                val status = when {
                    checkingKoneUpdate -> stringResource(R.string.kone_update_checking)
                    release != null -> {
                        val line = updateLine(koneUpdate, release)
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

            // One row: the size, and Clear beside it while there is something to clear.
            val size = cacheSize
            ListItem(
                headlineContent = { Text(stringResource(R.string.kone_app_cache), style = MaterialTheme.typography.bodyLarge) },
                supportingContent = size?.let {
                    { Text(Formatter.formatFileSize(context, it), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")) }
                },
                leadingContent = {
                    Icon(
                        imageVector = Icons.Outlined.DeleteSweep,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                trailingContent = {
                    if (size != null && size > 0L) {
                        TextButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() } }
                                cacheSize = 0L
                            }
                        }) { Text(stringResource(R.string.kone_app_cache_clear)) }
                    }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

/** The version; a tap or a long press copies it, which is all there is to do with it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VersionRow(versionLine: String, hasUpdate: Boolean) {
    val context = LocalContext.current
    val copy = {
        clipboardText = versionLine
        Toast.makeText(context, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }
    ListItem(
        headlineContent = { Text(stringResource(R.string.app_version_title), style = MaterialTheme.typography.bodyLarge) },
        supportingContent = { Text(versionLine, style = MaterialTheme.typography.bodyMedium) },
        leadingContent = {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = if (hasUpdate) {
            { Badge(containerColor = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        modifier = Modifier.combinedClickable(
            onClickLabel = stringResource(R.string.per_app_proxy_action_copy),
            onClick = copy,
            onLongClick = copy,
        ),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** The owner's copy updates from the console's feed, which needs signing in there first. */
private fun ownerNeedsSignIn(context: Context): Boolean =
    BuildConfig.KNINE_OWNER && OwnerUpdateAccess.credential(context) == null

/** Whether the connection's notification shows: its channel on Android 8 and later, the app's notifications before. */
private fun serviceNotificationEnabled(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    // The channel may not exist yet before the first connection; creating it again changes nothing.
    Application.notification.createNotificationChannel(
        NotificationChannel(SERVICE_CHANNEL, "Service Notifications", NotificationManager.IMPORTANCE_LOW),
    )
    return Application.notification.getNotificationChannel(SERVICE_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
}

/** Where Android lets the connection's notification be turned off: its channel, or the app's page before Android 8. */
private fun serviceNotificationSettings(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(AndroidSettings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
            putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
            putExtra(AndroidSettings.EXTRA_CHANNEL_ID, SERVICE_CHANNEL)
        }
    } else {
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
    }

private const val SERVICE_CHANNEL = "service"

private fun localeName(locale: Locale): String =
    locale.getDisplayName(Locale.ENGLISH).replaceFirstChar { it.uppercase(Locale.ENGLISH) }

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
