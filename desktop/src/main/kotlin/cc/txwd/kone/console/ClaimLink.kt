package cc.txwd.kone.console

import java.net.URI
import java.net.URLDecoder

/**
 * The address of a KOne console. HTTPS only: the key and the device credential travel to it.
 * No user, query or fragment; a path is allowed for a console behind a prefix.
 */
class ConsoleEndpoint private constructor(val base: String) {
    fun url(path: String): String = base + path

    override fun toString(): String = base
    override fun equals(other: Any?): Boolean = other is ConsoleEndpoint && other.base == base
    override fun hashCode(): Int = base.hashCode()

    companion object {
        /** The address typed or carried by a link, or null for one the App will not talk to. */
        fun parse(text: String): ConsoleEndpoint? {
            val trimmed = text.trim().trimEnd('/')
            if (trimmed.isEmpty() || trimmed.length > 512 || trimmed.any { it.isWhitespace() }) return null
            val uri = runCatching { URI(if ("://" in trimmed) trimmed else "https://$trimmed") }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() != "https") return null
            val host = uri.host?.lowercase() ?: return null
            if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
            val port = if (uri.port == -1) "" else ":${uri.port}"
            val path = (uri.rawPath ?: "").trimEnd('/')
            val shownHost = if (':' in host && !host.startsWith("[")) "[$host]" else host
            return ConsoleEndpoint("https://$shownHost$port$path")
        }
    }
}

/**
 * A key as the console hands it over: `kone://claim?server=<address>&code=<invite code>` from a
 * link or a QR code, or the invite code alone when this build knows its console.
 */
class ClaimLink(val endpoint: ConsoleEndpoint, val code: String) {
    companion object {
        private const val PREFIX = "kone://claim?"

        /**
         * What was pasted or typed: a link anywhere in it, or a bare code with [builtIn] as the
         * console. Null when it is neither, or the link's server is not HTTPS.
         */
        fun read(text: String, builtIn: ConsoleEndpoint?): ClaimLink? {
            text.lineSequence().map { it.trim() }.firstOrNull { it.startsWith(PREFIX, ignoreCase = true) }?.let { return parse(it) }
            val code = text.trim()
            return if (builtIn != null && isInviteCode(code)) ClaimLink(builtIn, code) else null
        }

        fun parse(link: String): ClaimLink? {
            if (!link.startsWith(PREFIX, ignoreCase = true) || link.length > 1024) return null
            val fields = link.substring(PREFIX.length).split('&').mapNotNull { pair ->
                val key = pair.substringBefore('=', "")
                if (key.isEmpty()) null else key to runCatching { URLDecoder.decode(pair.substringAfter('='), "UTF-8") }.getOrNull()
            }.toMap()
            val endpoint = fields["server"]?.let { ConsoleEndpoint.parse(it) } ?: return null
            val code = fields["code"]?.trim()?.takeIf(::isInviteCode) ?: return null
            return ClaimLink(endpoint, code)
        }

        /** The console's invite codes are URL-safe base64; anything else is a typo or a paste of more. */
        fun isInviteCode(code: String): Boolean =
            code.length in 8..128 && code.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }
    }
}
