package io.nekohasekai.sfa.managed

import java.net.URI
import java.net.URLDecoder

/**
 * The address of a KOne console, as the customer interface needs it: HTTPS, or plain HTTP only
 * to this phone itself (127.0.0.1 through `adb reverse` while testing). No user, query or
 * fragment; a path is allowed for a console behind a prefix. See docs/managed-service.md.
 */
class ManagedEndpoint private constructor(val base: String, val host: String) {
    fun url(path: String): String = base + path

    override fun toString(): String = base
    override fun equals(other: Any?): Boolean = other is ManagedEndpoint && other.base == base
    override fun hashCode(): Int = base.hashCode()

    companion object {
        private val LOOPBACK = setOf("127.0.0.1", "localhost", "::1", "[::1]")

        /** The address typed or carried by a link, or null for one the App will not talk to. */
        fun parse(text: String): ManagedEndpoint? {
            val trimmed = text.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.length > 512 || trimmed.any { it.isWhitespace() }) return null
            val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
            val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
            val scheme = uri.scheme?.lowercase() ?: return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val loopback = host in LOOPBACK
            if (scheme != "https" && !(scheme == "http" && loopback)) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = (uri.rawPath ?: "").trimEnd('/')
            val shownHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
            return ManagedEndpoint("$scheme://$shownHost$port$path", host)
        }
    }
}

/**
 * A claim handed over as one link or QR code: `kone://claim?server=<address>&code=<invite code>`.
 * The console shows the invite code once; carrying the address with it spares typing both.
 */
class ManagedClaimLink(val endpoint: ManagedEndpoint, val code: String) {
    companion object {
        private const val PREFIX = "kone://claim?"

        fun find(text: String): ManagedClaimLink? = text.lineSequence().map { it.trim() }
            .firstOrNull { it.startsWith(PREFIX, ignoreCase = true) }?.let(::parse)

        fun parse(link: String): ManagedClaimLink? {
            if (!link.startsWith(PREFIX, ignoreCase = true) || link.length > 1024) return null
            val fields = link.substring(PREFIX.length).split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=', "")
                if (key.isEmpty()) null else key to runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrNull()
            }.toMap()
            val endpoint = fields["server"]?.let { ManagedEndpoint.parse(it) } ?: return null
            val code = fields["code"]?.trim()?.takeIf(::isInviteCode) ?: return null
            return ManagedClaimLink(endpoint, code)
        }

        /** The console's invite codes are URL-safe base64; anything else is a typo or a paste of more. */
        fun isInviteCode(code: String): Boolean = code.length in 8..128 && code.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    }
}

