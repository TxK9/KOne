package io.nekohasekai.sfa.compose.screen.router

import android.annotation.SuppressLint
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
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
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.console.WebToolBar
import io.nekohasekai.sfa.compose.screen.console.WebToolProblem
import io.nekohasekai.sfa.compose.screen.console.WebToolProgress
import io.nekohasekai.sfa.compose.screen.console.WebToolTheme
import io.nekohasekai.sfa.compose.screen.console.webToolInk
import io.nekohasekai.sfa.compose.screen.console.webToolPaper
import io.nekohasekai.sfa.compose.theme.Theme
import java.io.ByteArrayInputStream
import java.net.URI
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Owner-only embedded LuCI. No native bridge, password capture, shell endpoint or proxy engine. */
class RouterActivity : AppCompatActivity() {
    private var web: WebView? = null
    private var loading by mutableStateOf(false)
    private var problem by mutableStateOf<Int?>(null)
    private var editing by mutableStateOf(false)
    private val store by lazy { getSharedPreferences("kone_router", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!BuildConfig.KNINE_OWNER) { finish(); return }
        editing = RouterAddress.parse(store.getString("address", "").orEmpty()) == null
        enableEdgeToEdge()
        setContent {
            val dark = isSystemInDarkTheme()
            // The console's paper and ink, so the owner's two web tools look like one family.
            Theme(darkTheme = dark, plainKnine = true) {
                WebToolTheme(webToolPaper(dark), webToolInk(dark)) {
                    var address by remember { mutableStateOf(store.getString("address", "").orEmpty()) }
                    var fingerprint by remember { mutableStateOf(store.getString("pin", "").orEmpty()) }
                    BackHandler {
                        when {
                            editing -> finish()
                            web?.canGoBack() == true -> web?.goBack()
                            else -> finish()
                        }
                    }
                    fun open() {
                        val home = RouterAddress.parse(address)
                        val pin = fingerprint.takeIf { it.isNotBlank() }?.let(RouterAddress::pin)
                        if (home == null || fingerprint.isNotBlank() && pin == null) {
                            problem = R.string.kone_router_invalid
                        } else {
                            store.edit().putString("address", home.toString()).putString("pin", pin.orEmpty()).apply()
                            problem = null
                            editing = false
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                            .systemBarsPadding()
                            .imePadding(),
                    ) {
                        val ink = MaterialTheme.colorScheme.onSurface
                        WebToolBar(title = stringResource(R.string.kone_router), onBack = { finish() }) {
                            if (!editing) {
                                IconButton(onClick = { problem = null; web?.reload() }) {
                                    Icon(Icons.Outlined.Refresh, stringResource(R.string.kone_router_refresh), tint = ink)
                                }
                                IconButton(onClick = { problem = null; editing = true }) {
                                    Icon(Icons.Outlined.Settings, stringResource(R.string.kone_router_address), tint = ink)
                                }
                            }
                        }
                        if (editing) {
                            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                OutlinedTextField(
                                    address, { address = it; problem = null }, Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.kone_router_address)) },
                                    placeholder = { Text("https://router.example") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                                )
                                OutlinedTextField(
                                    fingerprint, { fingerprint = it; problem = null }, Modifier.fillMaxWidth(),
                                    label = { Text(stringResource(R.string.kone_router_pin)) },
                                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Go),
                                    keyboardActions = KeyboardActions(onGo = { open() }),
                                )
                                problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                                // An outlined pill like the rest of KOne's actions, not a filled block of colour.
                                OutlinedButton(onClick = ::open) { Text(stringResource(R.string.kone_router_open)) }
                            }
                        } else {
                            val home = RouterAddress.parse(store.getString("address", "").orEmpty())!!
                            val pin = store.getString("pin", "").orEmpty()
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                                    WebView(context).also { view ->
                                        web = view
                                        prepare(view, home, pin)
                                        view.loadUrl(home.toString())
                                    }
                                }, onRelease = { view ->
                                    if (web === view) web = null
                                    view.stopLoading(); view.destroy()
                                })
                                // A page that cannot be had gives way to its reason in place, as the console's does.
                                val reason = problem
                                if (reason != null) {
                                    WebToolProblem(
                                        title = stringResource(R.string.kone_router_problem),
                                        hint = stringResource(reason),
                                        onRetry = { problem = null; web?.reload() },
                                        secondary = {
                                            TextButton(onClick = { problem = null; editing = true }, modifier = Modifier.padding(top = 8.dp)) {
                                                Text(stringResource(R.string.kone_router_address), color = ink)
                                            }
                                        },
                                    )
                                } else if (loading) {
                                    WebToolProgress()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun prepare(view: WebView, home: URI, pin: String) {
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, false)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = !RouterAddress.sameOrigin(request.url.toString(), home)
            @Deprecated("Legacy WebView callback")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = !RouterAddress.sameOrigin(url, home)
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (RouterAddress.sameOrigin(request.url.toString(), home)) null
                else WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", emptyMap(), ByteArrayInputStream(byteArrayOf()))
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { loading = true; problem = null }
            override fun onPageFinished(view: WebView, url: String?) { loading = false }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { loading = false; problem = R.string.kone_router_unreachable }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400 && errorResponse.statusCode !in setOf(401, 403)) {
                    loading = false; problem = R.string.kone_router_unreachable
                }
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // A self-signed device may be trusted only by an explicitly entered, host-bound fingerprint.
                // Never trust a newly observed certificate, an expired one, or a different origin.
                val accepted = pin.isNotEmpty() && RouterAddress.sameOrigin(error.url, home) && runCatching {
                    val der = SslCertificate.saveState(error.certificate)?.getByteArray("x509-certificate") ?: return@runCatching false
                    val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                    RouterAddress.matches(cert, pin)
                }.getOrDefault(false)
                if (accepted) handler.proceed() else {
                    handler.cancel(); loading = false; problem = R.string.kone_router_certificate
                }
            }
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) { request.deny() }
        }
        // Downloads are refused with a short note, as the console does, rather than covering the page.
        view.setDownloadListener { _, _, _, _, _ -> Toast.makeText(this, getString(R.string.kone_router_download), Toast.LENGTH_SHORT).show() }
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
}
