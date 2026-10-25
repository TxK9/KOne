package io.nekohasekai.sfa.compose.screen.router

import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** The owner chooses an HTTPS router; its native LuCI authentication remains in charge. */
internal object RouterAddress {
    fun parse(text: String): URI? = runCatching {
        val uri = URI(text.trim())
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank())
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
        require(uri.port == -1 || uri.port in 1..65535)
        require(uri.path.isNullOrEmpty() || uri.path == "/" || uri.path.startsWith("/cgi-bin/luci"))
        URI("https", null, uri.host.lowercase(), uri.port, "/cgi-bin/luci/admin/services/passwall2", null, null)
    }.getOrNull()

    fun sameOrigin(text: String, home: URI): Boolean = runCatching {
        val uri = URI(text)
        uri.scheme.equals("https", true) && uri.rawUserInfo == null && uri.host.equals(home.host, true) && port(uri) == port(home)
    }.getOrDefault(false)

    fun pin(text: String): String? = text.trim().replace(":", "").lowercase()
        .takeIf { it.matches(Regex("[a-f0-9]{64}")) }

    fun matches(certificate: X509Certificate, expected: String): Boolean = runCatching {
        certificate.checkValidity()
        val normalized = pin(expected) ?: return false
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
            .joinToString("") { "%02x".format(it) }
        MessageDigest.isEqual(digest.toByteArray(), normalized.toByteArray())
    }.getOrDefault(false)

    private fun port(uri: URI) = if (uri.port == -1) 443 else uri.port
}
