package io.nekohasekai.sfa.preview

import android.app.LocaleConfig
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.core.view.WindowCompat
import android.webkit.WebView
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import androidx.compose.ui.viewinterop.AndroidView
import io.nekohasekai.sfa.compose.theme.WallpaperAssetClient
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import io.nekohasekai.sfa.compose.screen.settings.SettingsPickRow
import io.nekohasekai.sfa.compose.screen.tools.TestRow
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.theme.Theme
import java.io.File
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.account.AccountClock
import io.nekohasekai.sfa.compose.screen.account.ManagedAccountContent
import io.nekohasekai.sfa.compose.screen.dashboard.CollapsedPanel
import io.nekohasekai.sfa.compose.screen.dashboard.DashboardUiState
import io.nekohasekai.sfa.compose.screen.dashboard.KnineHost
import io.nekohasekai.sfa.compose.component.KnineConnectAction
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.managed.ManagedPolicy
import io.nekohasekai.sfa.managed.ManagedSnapshot
import io.nekohasekai.sfa.managed.ManagedStatus
import java.time.ZoneId
import java.util.Locale
import org.xmlpull.v1.XmlPullParser

/** Debug source only. The preview manifest has no production Application, VPN or Internet permission. */
class VisualPreviewActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) {
        val locale = base.getSharedPreferences("visual", Context.MODE_PRIVATE).getString("locale", null)
        super.attachBaseContext(if (locale == null) base else base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)) }))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val locale = intent.getStringExtra("locale") ?: "en"
        val dark = intent.getBooleanExtra("dark", false)
        val context = createConfigurationContext(Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(locale)); uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO })
        resources.updateConfiguration(context.resources.configuration, resources.displayMetrics)
        val clear = android.graphics.Color.TRANSPARENT
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(clear) else SystemBarStyle.light(clear, clear),
            navigationBarStyle = if (dark) SystemBarStyle.dark(0xFF181A1F.toInt()) else SystemBarStyle.light(0xFFF0F2F5.toInt(), 0xFF181A1F.toInt()),
        )
        // The light AppTheme's legacy flag can survive API 35 edge-to-edge setup.
        window.decorView.post {
            val old = window.decorView.systemUiVisibility
            window.decorView.systemUiVisibility = if (dark) old and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else old or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !dark
        }
        val fontScale = intent.getFloatExtra("fontScale", 1.15f)
        val page = intent.getStringExtra("page") ?: "account"
        val state = intent.getStringExtra("state") ?: "synced"
        setContent { VisualPreviewScene(dark, fontScale, page, state) }
    }
}

@Composable
internal fun VisualPreviewScene(dark: Boolean, fontScale: Float = 1.15f, initialPage: String = "account", state: String = "synced") {
    val density = LocalDensity.current
    val context = LocalContext.current
    val city = initialPage == "city"
    val path = if (city) remember(context, dark) {
        File(context.cacheDir, "visual-city-${if (dark) "night" else "day"}.webp").apply {
            context.assets.open("wallpaper/images/original-${if (dark) "night" else "day"}.webp").use { input -> outputStream().use { input.copyTo(it) } }
        }.absolutePath
    } else null
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
        if (initialPage == "motion") PreviewMotion(dark, reduced = state == "reduced")
        else if (city) Theme(darkTheme = dark, dynamicColor = false, portraitBackdrop = true, backdropRes = R.drawable.kone_ink_mountains, customBackdropPath = path) {
            PreviewScaffold(initialPage, state, city = true)
        } else Theme(darkTheme = dark, dynamicColor = false, plainKnine = true) {
            PreviewScaffold(initialPage, state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewScaffold(initialPage: String, state: String, city: Boolean = false) {
    var page by remember { mutableStateOf(initialPage) }
    val background = if (city) Color.Transparent else MaterialTheme.colorScheme.surface
    Scaffold(
        containerColor = background,
        topBar = { TopAppBar(title = { Text(when (page) {
            "account" -> stringResource(R.string.kone_account)
            "settings" -> stringResource(R.string.title_settings)
            "tools" -> stringResource(R.string.kone_tools_network_tests)
            else -> "KOne"
        }) }, colors = TopAppBarDefaults.topAppBarColors(containerColor = background)) },
        bottomBar = {
            NavigationBar(containerColor = background) {
                NavigationBarItem(page == "dashboard" || city, { page = "dashboard" }, icon = { Icon(Icons.Outlined.Dashboard, "Dashboard") }, label = { Text(stringResource(R.string.title_dashboard)) })
                NavigationBarItem(page == "account", { page = "account" }, icon = { Icon(Icons.Outlined.AccountCircle, "Account") }, label = { Text(stringResource(R.string.kone_account)) })
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(background)) {
            when (page) {
                "account" -> PreviewAccount(state)
                "settings" -> PreviewSettings()
                "tools" -> PreviewTools()
                else -> PreviewDashboard(state)
            }
        }
    }
}

@Composable
private fun PreviewSettings() {
    var appearance by remember { mutableStateOf(0) }
    var language by remember { mutableStateOf(0) }
    val context = LocalContext.current
    val locales = remember(context) { previewLocales(context) }
    val names = listOf(stringResource(R.string.system_default)) + locales.map { it.getDisplayName(it).replaceFirstChar { char -> char.uppercase(it) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        SettingsPickRow(stringResource(R.string.kone_app_appearance),
            listOf(stringResource(R.string.system_default), stringResource(R.string.kone_appearance_light), stringResource(R.string.kone_appearance_dark)), appearance, { appearance = it })
        SettingsPickRow(stringResource(R.string.language),
            names, language, { language = it })
    }
}

@Composable
private fun PreviewTools() {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        TestRow(stringResource(R.string.connection_destination), NodeWeather.Clear,
            "2001:db8:1234:5678:9abc:def0:1234:5678", "example.com · 443", open, { open = !open })
        TestRow(stringResource(R.string.kone_detail_latency), NodeWeather.Mist,
            "1234.56 ms · Example Tokyo route", null, false, {})
    }
}

@Composable
private fun PreviewAccount(state: String) {
    val now = 1_793_491_200_000L
    var status by remember { mutableStateOf(when (state) {
        "expired" -> ManagedStatus.Refused
        "failed" -> ManagedStatus.Failed
        else -> ManagedStatus.Synced
    }) }
    var unlinkArmed by remember { mutableStateOf(false) }
    val snapshot = ManagedSnapshot(
        endpoint = "https://console.example.com",
        deviceName = if (state == "external") "Example Phone · Tokyo" else stringResource(R.string.visual_example_device),
        bound = true,
        profileId = 7L,
        configName = if (state == "external") "Tokyo Example" else stringResource(R.string.visual_example_route),
        groupName = if (state == "external") "Standard" else stringResource(R.string.visual_example_plan),
        policy = ManagedPolicy(quotaBytes = 100_000_000_000L, deviceLimit = 3, expiresAt = ((now + if (state == "expired") -86_400_000L else 30L * 86_400_000L) / 1000L).toString(), period = "2026-11"),
        syncedAt = now - 240_000L,
        status = status,
        detail = null,
        diagnostics = false,
    )
    ManagedAccountContent(snapshot, AccountClock(now, ZoneId.of("UTC")), syncing = false,
        unlinking = false, unlinkFailed = false, onSync = { status = ManagedStatus.Synced },
        onFeedback = {}, onUnlink = { unlinkArmed = !unlinkArmed }, unlinkArmed = unlinkArmed)
}

/** Passive production dashboard components; full dashboard service orchestration is deliberately outside this scene. */
@Composable
private fun PreviewDashboard(state: String) {
    val status = if (state == "connected") Status.Started else Status.Stopped
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Bottom) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CollapsedPanel(DashboardUiState(), status, KnineHost(), onSwitch = {}, modifier = Modifier.weight(1f))
            KnineConnectAction(status, onStart = {}, onStop = {})
        }
    }
}

/** The production bundled HTML/runtime and asset interceptor, without settings or production stores. */
@Composable
private fun PreviewMotion(dark: Boolean, reduced: Boolean) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(android.graphics.Color.rgb(24, 32, 32))
                settings.apply {
                    javaScriptEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    databaseEnabled = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    blockNetworkLoads = true
                    setGeolocationEnabled(false)
                    mediaPlaybackRequiresUserGesture = true
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                        Log.i("KOneVisual", "console ${message.messageLevel()}: ${message.message()}")
                        return true
                    }
                }
                webViewClient = WallpaperAssetClient(context) { view ->
                    listOf(3000L, 15000L, 30000L).forEach { delay ->
                        view.postDelayed({
                            view.evaluateJavascript("""(() => { const s = window.KOneWallpaper && window.KOneWallpaper.state; if (!s) return null; const rects = [...document.querySelectorAll('canvas')].map(c => { const r = c.getBoundingClientRect(); return {width:c.width,height:c.height,x:r.x,y:r.y,w:r.width,h:r.height}; }); return JSON.stringify({ready:s.ready,error:s.error,scene:s.scene,running:s.running,time:s.time,layers:s.layers.map(x => ({source:x.source,running:x.running,frames:x.frames,time:x.time,reduced:x.reducedMotion,visible:x.visible})),canvasGeometry:rects,finiteGeometry:rects.every(r => Object.values(r).every(Number.isFinite))}); })()""") { state ->
                                Log.i("KOneVisual", "motion-state $state")
                            }
                        }, delay)
                    }
                }
                loadUrl("https://appassets.androidplatform.net/wallpaper/index.html?scene=original&motion=${if (reduced) "off" else "on"}&time=${if (dark) "night" else "day"}")
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}

private fun previewLocales(context: Context): List<Locale> {
    val configured = runCatching { LocaleConfig(context).supportedLocales }.getOrNull()
    if (configured != null && !configured.isEmpty) return (0 until configured.size()).map { configured[it] }
    val id = context.resources.getIdentifier("_generated_res_locale_config", "xml", context.packageName)
    if (id == 0) return emptyList()
    val result = mutableListOf<Locale>()
    context.resources.getXml(id).use { parser ->
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "locale") {
                parser.getAttributeValue("http://schemas.android.com/apk/res/android", "name")?.let { result += Locale.forLanguageTag(it) }
            }
        }
    }
    return result
}
