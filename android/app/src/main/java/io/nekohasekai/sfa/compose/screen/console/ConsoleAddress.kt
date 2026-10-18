package io.nekohasekai.sfa.compose.screen.console

import java.net.URI

/** The private edition console address, and what counts as staying on it. */
object ConsoleAddress {
    /** [text] when it is an https address with a host and no user name in it; null otherwise. */
    fun parse(text: String): URI? {
        val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() || uri.rawUserInfo != null) return null
        return uri
    }

    /** Whether [url] is on the console itself: https, the same host and the same port. */
    fun sameOrigin(url: String, home: URI): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.host.equals(home.host, ignoreCase = true) && port(uri) == port(home)
    }

    private fun port(uri: URI) = if (uri.port == -1) 443 else uri.port
}
