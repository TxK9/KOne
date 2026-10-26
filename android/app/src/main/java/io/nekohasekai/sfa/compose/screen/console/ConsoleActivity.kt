package io.nekohasekai.sfa.compose.screen.console

import io.nekohasekai.sfa.R
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.core.graphics.ColorUtils
import org.json.JSONObject
import org.json.JSONTokener
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.update.OwnerUpdateAccess
import io.nekohasekai.sfa.update.KoneUpdates
import io.nekohasekai.sfa.compose.theme.Theme
import io.nekohasekai.sfa.compose.theme.KninePalette
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.WeatherSeal
import java.net.URI

/**
 * Embedded management console reached through the private Tailscale connection.
 * The console origin is provided through `kone.admin` in local build settings and is restricted
 * by [ConsoleAddress]. Builds without a console origin close this activity immediately.
 */
class ConsoleActivity : AppCompatActivity() {
    private var web: WebView? = null
    private var unreachable by mutableStateOf(false)
    private var loading by mutableStateOf(true)
    private var pagePaper by mutableStateOf<Int?>(null)
    private var pageInk by mutableStateOf<Int?>(null)
    private var waitingFile: ValueCallback<Array<Uri>>? = null
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        waitingFile?.onReceiveValue(uri?.let { arrayOf(it) })
        waitingFile = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val home = ConsoleAddress.parse(BuildConfig.KONE_ADMIN)
        if (home == null || !BuildConfig.KNINE_OWNER) {
            finish()
            return
        }
        enableEdgeToEdge()
        setContent {
            val appDark = isSystemInDarkTheme()
            val dark = pagePaper?.let { ColorUtils.calculateLuminance(it) < .5 } ?: appDark
            Theme(darkTheme = dark, plainKnine = true) {
                val ink = androidx.compose.ui.graphics.Color(pageInk ?: if (dark) KninePalette.LIGHT_INK else KninePalette.DARK_INK)
                val paper = androidx.compose.ui.graphics.Color(pagePaper ?: if (dark) 0xFF181A1F.toInt() else 0xFFF0F2F5.toInt())
                MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(
                    surface = paper, background = paper, onSurface = ink, onBackground = ink,
                    onSurfaceVariant = ink.copy(alpha = .65f), primary = ink, onPrimary = paper,
                )) {
                    BackHandler {
                        val view = web
                        if (!unreachable && view != null && view.canGoBack()) view.goBack() else finish()
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .systemBarsPadding()
                            .imePadding(),
                    ) {
                        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = {
                                val view = web
                                if (!unreachable && view != null && view.canGoBack()) view.goBack() else finish()
                            }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.content_description_back), tint = ink) }
                            // One line between the two buttons: the serif title steps down rather than wrapping.
                            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
                                val title = stringResource(R.string.kone_tools_console)
                                val measurer = androidx.compose.ui.text.rememberTextMeasurer()
                                val room = constraints.maxWidth
                                val size = androidx.compose.runtime.remember(title, room) {
                                    var sp = 22f
                                    while (sp > 15f && measurer.measure(title, androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Serif, fontSize = sp.sp), maxLines = 1, softWrap = false).size.width > room) sp -= 0.5f
                                    sp
                                }
                                Text(title, fontFamily = FontFamily.Serif, fontSize = size.sp, color = ink, maxLines = 1, softWrap = false)
                            }
                            IconButton(onClick = { retry(home) }, enabled = !loading) {
                                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.kone_console_refresh), tint = ink)
                            }
                        }
                        HorizontalDivider(color = ink.copy(alpha = .10f))
                        Box(Modifier.weight(1f)) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { context ->
                                WebView(context).also { view ->
                                    prepare(view, home)
                                    web = view
                                    if (savedInstanceState == null || view.restoreState(savedInstanceState) == null) {
                                        view.loadUrl(home.toString())
                                    }
                                }
                            },
                        )
                        if (unreachable) {
                            Unreachable(onRetry = { retry(home) })
                        } else if (loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp), color = ink, trackColor = paper)
                        }
                        }
                }
                }
            }
        }
    }
    /** Refresh stays on the page in view (the console routes by #/…); only with none of it loaded does it start from home. */
    private fun retry(home: URI) {
        if (loading) return
        val view = web ?: return
        unreachable = false
        loading = true
        val current = view.url
        if (current != null && ConsoleAddress.sameOrigin(current, home)) view.reload() else view.loadUrl(home.toString())
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun prepare(view: WebView, home: URI) {
        view.settings.apply {
            javaScriptEnabled = true // the console is built of ES modules
            domStorageEnabled = true // it keeps its Appearance choice there
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        // The console has its own dark design; the WebView must not darken it a second time.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(view.settings, false)
        }
        view.setBackgroundColor(Color.TRANSPARENT)
        CookieManager.getInstance().setAcceptCookie(true)
        // This bridge accepts only two colours, only from the console's main frame.
        // It exposes no credentials, settings, files or native actions to the page.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val origin = "https://${home.host}:${if (home.port == -1) 443 else home.port}"
            WebViewCompat.addWebMessageListener(view, "koneAppearance", setOf(origin)) { _, message, _, mainFrame, _ ->
                if (mainFrame) message.data?.let(::readAppearance)
            }
            WebViewCompat.addWebMessageListener(view, "koneOwnerUpdates", setOf(origin)) { _, message, source, mainFrame, reply ->
                if (mainFrame && ConsoleAddress.sameOrigin(source.toString(), home)) {
                    runCatching {
                        val payload = message.data ?: return@runCatching
                        if (payload.length > 2048) return@runCatching
                        if (JSONObject(payload).optString("action") == "check") {
                            KoneUpdates.start(this)
                            Toast.makeText(this, getString(R.string.kone_console_check_updates), Toast.LENGTH_SHORT).show()
                        } else {
                            OwnerUpdateAccess.accept(this, payload)
                            KoneUpdates.start(this)
                            reply.postMessage("ready")
                        }
                    }
                }
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                loading = true
                unreachable = false
            }

            override fun onPageFinished(view: WebView, url: String?) {
                loading = false
                if (!unreachable && ConsoleAddress.sameOrigin(url.orEmpty(), home)) {
                    if (OwnerUpdateAccess.credential(this@ConsoleActivity) == null && BuildConfig.KONE_UPDATE_FEED.isNotBlank()) {
                        val device = JSONObject.quote(OwnerUpdateAccess.deviceId(this@ConsoleActivity))
                        val name = JSONObject.quote((android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL).take(80))
                        view.evaluateJavascript("""
                            (() => {
                              if (!window.koneOwnerUpdates || window.koneOwnerEnrollment) return;
                              window.koneOwnerEnrollment = true;
                              let busy = false, done = false;
                              async function enroll() {
                                if (busy || done) return; busy = true;
                                try {
                                  const status = await fetch('/api/auth/status', {cache:'no-store'}).then(r=>r.json());
                                  if (!status.authenticated) return;
                                  const response = await fetch('/api/owner-update/enroll', {method:'POST', headers:{'Content-Type':'application/json','X-CSRF-Token':status.csrfToken},body:JSON.stringify({deviceId:$device,name:$name})});
                                  if (!response.ok) return;
                                  const value = await response.json();
                                  window.koneOwnerUpdates.onmessage = e => { if(e.data === 'ready') { done = true; observer.disconnect(); } };
                                  window.koneOwnerUpdates.postMessage(JSON.stringify(value));
                                } finally { busy = false; }
                              }
                              const observer = new MutationObserver(() => { enroll().catch(()=>{}); });
                              observer.observe(document.getElementById('kone'), {childList:true});
                              enroll().catch(()=>{});
                            })()
                        """.trimIndent(), null)
                    }
                    view.evaluateJavascript("""
                        (function() {
                          function colours() {
                            var css = getComputedStyle(document.documentElement);
                            return JSON.stringify({paper:css.getPropertyValue('--paper').trim(),ink:css.getPropertyValue('--ink').trim()});
                          }
                          function send() { if (window.koneAppearance) window.koneAppearance.postMessage(colours()); }
                          if (window.koneAppearance) {
                            if (window.koneAppearanceObserver) window.koneAppearanceObserver.disconnect();
                            window.koneAppearanceObserver = new MutationObserver(send);
                            window.koneAppearanceObserver.observe(document.documentElement,{attributes:true,attributeFilter:['data-theme']});
                            send();
                          }
                          return colours();
                        })()
                    """.trimIndent()) { result ->
                        runCatching { (JSONTokener(result).nextValue() as? String)?.let(::readAppearance) }
                    }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (ConsoleAddress.sameOrigin(request.url.toString(), home)) return false
                // A link away from the console opens outside; nothing else is loaded in here.
                if (request.isForMainFrame && request.url.scheme in setOf("http", "https")) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    } catch (_: ActivityNotFoundException) {
                    }
                }
                return true
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { unreachable = true; loading = false }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                // The private entry answers 502 while the console itself is down.
                if (request.isForMainFrame && response.statusCode >= 500) { unreachable = true; loading = false }
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
                unreachable = true
                loading = false
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                waitingFile?.onReceiveValue(null)
                waitingFile = callback
                pickFile.launch("*/*")
                return true
            }
        }
        // The console saves files from inside the page, which a WebView cannot hand on.
        view.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, getString(R.string.kone_console_no_downloads), Toast.LENGTH_SHORT).show()
        }
    }

    private fun readAppearance(payload: String) {
        runCatching {
            val values = JSONObject(payload)
            val paper = Color.parseColor(values.getString("paper"))
            val ink = Color.parseColor(values.getString("ink"))
            runOnUiThread { pagePaper = paper; pageInk = ink }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        web?.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        waitingFile?.onReceiveValue(null)
        waitingFile = null
        web?.apply {
            stopLoading()
            destroy()
        }
        web = null
        super.onDestroy()
    }
}

@Composable
private fun Unreachable(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WeatherSeal(NodeWeather.Cut, size = 48.dp)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.kone_console_unreachable), fontFamily = FontFamily.Serif, fontSize = 26.sp, color = MaterialTheme.colorScheme.onSurface)
        Text(
            stringResource(R.string.kone_console_unreachable_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, bottom = 24.dp),
        )
        Button(onClick = onRetry, shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
            Text(stringResource(R.string.kone_console_retry), Modifier.padding(start = 8.dp))
        }
    }
}
