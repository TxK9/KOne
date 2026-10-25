package io.nekohasekai.sfa.compose.screen.router

import android.annotation.SuppressLint
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Bundle
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
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
            Theme(plainKnine = true) {
                var address by remember { mutableStateOf(store.getString("address", "").orEmpty()) }
                var fingerprint by remember { mutableStateOf(store.getString("pin", "").orEmpty()) }
                BackHandler {
                    when {
                        editing -> finish()
                        web?.canGoBack() == true -> web?.goBack()
                        else -> finish()
                    }
                }
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.kone_router_back)) }
                        Text(stringResource(R.string.kone_router), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                        if (!editing) {
                            IconButton(onClick = { problem = null; web?.reload() }) { Icon(Icons.Outlined.Refresh, stringResource(R.string.kone_router_refresh)) }
                            IconButton(onClick = { editing = true }) { Icon(Icons.Outlined.Settings, stringResource(R.string.kone_router_address)) }
                        }
                    }
                    if (editing) {
                        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            OutlinedTextField(address, { address = it; problem = null }, Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.kone_router_address)) }, singleLine = true,
                                placeholder = { Text("https://router.example") })
                            OutlinedTextField(fingerprint, { fingerprint = it; problem = null }, Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.kone_router_pin)) })
                            Text(stringResource(R.string.kone_router_pin_help), style = MaterialTheme.typography.bodySmall)
                            problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
                            Button(onClick = {
                                val home = RouterAddress.parse(address)
                                val pin = fingerprint.takeIf { it.isNotBlank() }?.let(RouterAddress::pin)
                                if (home == null || fingerprint.isNotBlank() && pin == null) {
                                    problem = R.string.kone_router_invalid
                                } else {
                                    store.edit().putString("address", home.toString()).putString("pin", pin.orEmpty()).apply()
                                    problem = null
                                    editing = false
                                }
                            }) { Text(stringResource(R.string.kone_router_open)) }
                        }
                    } else {
                        val home = RouterAddress.parse(store.getString("address", "").orEmpty())!!
                        val pin = store.getString("pin", "").orEmpty()
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                        problem?.let { Text(stringResource(it), Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error) }
                        AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { context ->
                            WebView(context).also { view ->
                                web = view
                                prepare(view, home, pin)
                                view.loadUrl(home.toString())
                            }
                        }, onRelease = { view ->
                            if (web === view) web = null
                            view.stopLoading(); view.destroy()
                        })
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
            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) { loading = true }
            override fun onPageFinished(view: WebView, url: String?) { loading = false }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) { loading = false; problem = R.string.kone_router_unreachable }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame && errorResponse.statusCode >= 400) { loading = false; problem = R.string.kone_router_unreachable }
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
        view.setDownloadListener { _, _, _, _, _ -> problem = R.string.kone_router_download }
    }

    override fun onPause() { web?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); web?.onResume() }
}
