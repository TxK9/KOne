package io.nekohasekai.sfa.compose.theme

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import org.json.JSONArray
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/** The whole city's veil behind a see-through popup, where the page's own lines are hidden (CityCanvas). */
internal const val CITY_WALLPAPER_VEIL_ALPHA = 0.66f

/** The veil every page shares: the city shows nearly as painted, with shade only where text sits. */
internal const val CITY_WALLPAPER_LIVELY_VEIL = 0.1f

internal data class CityWallpaperChoice(val id: String, val city: String, val country: String, val title: String, val zone: String = "UTC")

internal object CityWallpapers {
    private var cached: List<CityWallpaperChoice>? = null
    fun choices(context: Context): List<CityWallpaperChoice> = cached ?: runCatching {
        val items = JSONArray(context.assets.open("wallpaper/catalog.json").bufferedReader().use { it.readText() })
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            CityWallpaperChoice(item.getString("id"), item.getString("cityLabel"), item.getString("countryLabel"), item.getString("title"), item.optString("cityZone", "UTC"))
        }
    }.getOrDefault(emptyList()).also { cached = it }

    /** The city on screen now. The cities take turns by themselves; there is nothing to set. */
    var selected by mutableStateOf(Settings.knineCityWallpaper)
        private set

    /** True while the dashboard shows: the city comes up bright there and steps back behind reading pages. */
    var lively by mutableStateOf(false)

    /** True while KOne is connected: the city answers the connection with a sweep of light. */
    var connected by mutableStateOf(false)

    private var shownThisRun = false

    /**
     * Moves on to the next city each time KOne opens afresh, and after a break of half an hour,
     * so a return opens on another skyline: a US city, then one from elsewhere.
     */
    fun refresh(context: Context, now: Long = System.currentTimeMillis()) {
        val due = !shownThisRun || now - Settings.knineCityWallpaperChangedAt !in 0 until TURN_MS
        shownThisRun = true
        if (!due) return
        val known = choices(context).map { it.id }.toSet()
        val playlist = CityShowcase.order().filter { it in known }.ifEmpty { known.toList() }
        if (playlist.isEmpty()) return
        val next = playlist[(playlist.indexOf(selected) + 1).mod(playlist.size)]
        Settings.knineCityWallpaper = next
        Settings.knineCityWallpaperChangedAt = now
        selected = next
    }

    private const val TURN_MS = 30 * 60_000L
}

/** The order the cities take turns in, kept apart from settings so it can be checked on its own. */
internal object CityShowcase {
    // Busy skylines whose artwork has life of its own, alternating home and abroad.
    private val US = listOf(
        "original", "chicago-river", "las-vegas-strip", "san-francisco-bay", "los-angeles-griffith", "miami-biscayne-bay",
        "east-river", "seattle-elliott-bay", "washington-mall", "boston-harbour", "new-orleans-mississippi", "austin-lady-bird-lake",
        "san-diego-bay", "harbor", "philadelphia-schuylkill", "houston-buffalo-bayou", "dallas-trinity", "atlanta-midtown",
        "denver-civic-center", "honolulu-waikiki", "portland-willamette", "detroit-river", "minneapolis-mississippi",
        "pittsburgh-confluence", "st-louis-mississippi", "baltimore-inner-harbour", "salt-lake-capitol", "san-antonio-riverwalk",
        "savannah-river", "charleston-harbour",
    )
    private val WORLD = listOf(
        "tokyo-bay", "london-thames", "hong-kong-harbour", "paris-seine", "dubai-downtown", "singapore-marina", "sydney-harbour",
        "shanghai-huangpu", "seoul-han", "istanbul-bosphorus", "toronto-lake", "rio-bay", "mexico-city-reforma", "dubai-marina",
        "osaka-river", "bangkok-river", "kuala-lumpur-skyline", "berlin-spree", "amsterdam-canals", "barcelona-coast",
        "vancouver-harbour", "madrid-plaza", "italy-milan-duomo", "italy-rome-forum", "mumbai-harbour", "cairo-nile",
        "cape-town-waterfront", "melbourne-yarra", "montreal-old-port", "germany-frankfurt-main", "yokohama-minato-mirai",
        "shenzhen-bay", "ho-chi-minh-saigon", "jakarta-waterfront", "johannesburg-skyline", "abu-dhabi-corniche",
        "singapore-orchard", "netherlands-rotterdam-erasmus", "lisbon-tagus", "chongqing-rivers",
    )

    fun order(): List<String> = buildList {
        for (i in 0 until maxOf(US.size, WORLD.size)) {
            US.getOrNull(i)?.let(::add)
            WORLD.getOrNull(i)?.let(::add)
        }
    }
}

/** Only bundled wallpaper files can be read. The decorative view has no touch or native bridge. */
internal class WallpaperAssetClient(private val context: Context, private val ready: (WebView) -> Unit = {}) : WebViewClient() {
    override fun onPageFinished(view: WebView, url: String?) { ready(view) }
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
    @Deprecated("Legacy WebView navigation callback")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?) = true

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse = resource(request.url)

    internal fun resource(uri: Uri): WebResourceResponse {
        val path = uri.path.orEmpty()
        val valid = uri.scheme == "https" && uri.host == "appassets.androidplatform.net" && uri.port == -1 &&
            uri.userInfo == null && path.startsWith("/wallpaper/") &&
            path.removePrefix("/wallpaper/").matches(Regex("[a-zA-Z0-9_./-]+")) &&
            path.split('/').none { it == ".." || it == "." }
        if (!valid) return denied()
        return try {
            val mime = when (path.substringAfterLast('.')) {
                "html" -> "text/html"
                "js" -> "application/javascript"
                "json" -> "application/json"
                "css" -> "text/css"
                "webp" -> "image/webp"
                else -> return denied()
            }
            WebResourceResponse(mime, if (mime.startsWith("image/")) null else "UTF-8", context.assets.open(path.removePrefix("/")))
        } catch (_: Exception) { denied() }
    }

    private fun denied() = WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun CityWallpaper(modifier: Modifier = Modifier, blur: () -> Float = { 0f }) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var systemMotion by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val scale = currentCoroutineContext()[MotionDurationScale]
        snapshotFlow { (scale?.scaleFactor ?: 1f) > 0f }.collectLatest { systemMotion = it }
    }
    DisposableEffect(lifecycle) {
        CityWallpapers.refresh(context)
        val observer = LifecycleEventObserver { _, event ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (event == Lifecycle.Event.ON_RESUME) CityWallpapers.refresh(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // The city's own clock and weather; motion follows the system's animation setting alone.
    val url = Uri.Builder().scheme("https").authority("appassets.androidplatform.net").path("/wallpaper/index.html")
        .appendQueryParameter("scene", CityWallpapers.selected)
        .appendQueryParameter("motion", if (systemMotion) "on" else "off").build().toString()
    val web = remember(context) {
        runCatching {
        object : WebView(context) {
            override fun onTouchEvent(event: MotionEvent?) = false
        }.apply {
            setBackgroundColor(android.graphics.Color.rgb(24, 32, 32))
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            settings.apply {
                javaScriptEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                domStorageEnabled = false
                databaseEnabled = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                blockNetworkLoads = true
                setGeolocationEnabled(false)
                mediaPlaybackRequiresUserGesture = true
            }
            webViewClient = WallpaperAssetClient(context) { page ->
                page.evaluateJavascript("window.KOneWallpaper && window.KOneWallpaper.active($resumed)", null)
                page.evaluateJavascript("window.KOneWallpaper && window.KOneWallpaper.connected && window.KOneWallpaper.connected(${CityWallpapers.connected})", null)
            }
        }
        }.getOrNull()
    }
    if (web == null) {
        // A disabled or unavailable system WebView must not prevent the VPN from opening.
        val bitmap by produceState<android.graphics.Bitmap?>(null, CityWallpapers.selected) {
            val id = CityWallpapers.selected.takeIf { it.matches(Regex("[a-z0-9-]+")) } ?: "original"
            value = withContext(Dispatchers.IO) {
                runCatching { context.assets.open("wallpaper/images/$id-day.webp").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            }
        }
        bitmap?.let { Image(it.asImageBitmap(), null, modifier, contentScale = ContentScale.Crop) }
        return
    }
    LaunchedEffect(web, url) { web.loadUrl(url) }
    LaunchedEffect(web, CityWallpapers.connected) {
        web.evaluateJavascript("window.KOneWallpaper && window.KOneWallpaper.connected && window.KOneWallpaper.connected(${CityWallpapers.connected})", null)
    }
    LaunchedEffect(web, resumed) {
        if (resumed) web.onResume()
        web.evaluateJavascript("window.KOneWallpaper && window.KOneWallpaper.active($resumed)", null)
        if (!resumed) web.onPause()
    }
    // How far day has come in the scene, so the shade over it knows how bright it is (CityLight).
    LaunchedEffect(web, url, resumed) {
        if (!resumed) return@LaunchedEffect
        val scene = CityWallpapers.selected
        while (true) {
            web.evaluateJavascript(CityLight.QUERY) { CityLight.read(scene, it) }
            delay(if (CityLight.shown?.scene == scene) 60_000L else 2_000L)
        }
    }
    // The page's depth of field (rememberCityBlur), in whole pixels.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        LaunchedEffect(web) {
            snapshotFlow { blur().roundToInt() }.collect { radius ->
                web.setRenderEffect(if (radius > 0) RenderEffect.createBlurEffect(radius.toFloat(), radius.toFloat(), Shader.TileMode.CLAMP) else null)
            }
        }
    }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.onPause(); web.removeAllViews(); web.destroy() } }
    AndroidView(factory = { web }, modifier = modifier)
}
