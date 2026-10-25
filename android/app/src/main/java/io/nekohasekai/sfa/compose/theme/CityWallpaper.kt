package io.nekohasekai.sfa.compose.theme

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow
import org.json.JSONArray
import java.io.ByteArrayInputStream

internal const val CITY_WALLPAPER_VEIL_ALPHA = 0.66f

internal data class CityWallpaperChoice(val id: String, val city: String, val country: String, val title: String)

internal object CityWallpapers {
    private var cached: List<CityWallpaperChoice>? = null
    fun choices(context: Context): List<CityWallpaperChoice> = cached ?: runCatching {
        val items = JSONArray(context.assets.open("wallpaper/catalog.json").bufferedReader().use { it.readText() })
        (0 until items.length()).map { index ->
            val item = items.getJSONObject(index)
            CityWallpaperChoice(item.getString("id"), item.getString("cityLabel"), item.getString("countryLabel"), item.getString("title"))
        }
    }.getOrDefault(emptyList()).also { cached = it }

    var selected by mutableStateOf(Settings.knineCityWallpaper)
        private set
    var time by mutableStateOf(Settings.knineCityWallpaperTime)
        private set
    var weather by mutableStateOf(Settings.knineCityWallpaperWeather)
        private set
    var motion by mutableStateOf(Settings.knineCityWallpaperMotion)
        private set

    fun select(id: String) { require(id.matches(Regex("[a-z0-9-]+"))); Settings.knineCityWallpaper = id; selected = id }
    fun chooseTime(value: String) { require(value in setOf("city", "day", "night")); Settings.knineCityWallpaperTime = value; time = value }
    fun chooseWeather(value: String) { require(value in setOf("story", "clear", "cloudy", "shower", "mist", "snow")); Settings.knineCityWallpaperWeather = value; weather = value }
    fun chooseMotion(value: Boolean) { Settings.knineCityWallpaperMotion = value; motion = value }
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
internal fun CityWallpaper(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var systemMotion by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val scale = currentCoroutineContext()[MotionDurationScale]
        snapshotFlow { (scale?.scaleFactor ?: 1f) > 0f }.collectLatest { systemMotion = it }
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val url = Uri.Builder().scheme("https").authority("appassets.androidplatform.net").path("/wallpaper/index.html")
        .appendQueryParameter("scene", CityWallpapers.selected).appendQueryParameter("time", CityWallpapers.time)
        .appendQueryParameter("weather", CityWallpapers.weather)
        .appendQueryParameter("motion", if (CityWallpapers.motion && systemMotion) "on" else "off").build().toString()
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
    LaunchedEffect(web, resumed) {
        if (resumed) web.onResume()
        web.evaluateJavascript("window.KOneWallpaper && window.KOneWallpaper.active($resumed)", null)
        if (!resumed) web.onPause()
    }
    DisposableEffect(web) { onDispose { web.stopLoading(); web.onPause(); web.removeAllViews(); web.destroy() } }
    AndroidView(factory = { web }, modifier = modifier)
}
