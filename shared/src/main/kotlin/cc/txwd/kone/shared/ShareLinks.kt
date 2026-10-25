package cc.txwd.kone.shared

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.util.Base64
import java.util.UUID

/** A refusal in fixed words: it never repeats the link, so it is safe to show or log. */
class ShareLinkException(message: String) : IllegalArgumentException(message)

/** One node from a share link: its sing-box outbound, tagged [ShareLinks.NODE_TAG], and the link's name. */
class SharedNode(val outbound: JSONObject, val name: String?)

/**
 * Share links as other clients copy them: vless, vmess, trojan, ss, hysteria2 (or hy2), tuic
 * and anytls. Each becomes one sing-box outbound. What sing-box cannot carry is refused with a
 * reason, in words that never include the link.
 */
object ShareLinks {
    const val NODE_TAG = "node"

    private const val BAD_FORMAT = "Invalid link format"
    private const val NO_SERVER = "The link has no server address."
    private const val BAD_PORT = "Invalid link port"
    private const val BAD_ID = "Invalid user ID in the link"
    private const val NO_PASSWORD = "The link has no password."
    private const val DUPLICATE = "The link contains duplicate parameters."
    private const val TRANSPORT = "The link uses a transport unsupported by sing-box."

    private val schemes = setOf("vless", "vmess", "trojan", "ss", "hysteria2", "hy2", "tuic", "anytls")
    private val plugins = mapOf("obfs-local" to "obfs-local", "simple-obfs" to "obfs-local", "v2ray-plugin" to "v2ray-plugin")

    /** The first share link in [text]; see [findAll]. */
    fun find(text: String): String? = findAll(text).firstOrNull()

    /**
     * Every share link in [text], one per line, each once. Text holding none may be a base64
     * subscription block, which is opened and read the same way.
     */
    fun findAll(text: String): List<String> {
        val direct = links(text)
        if (direct.isNotEmpty()) return direct
        val block = text.filterNot { it.isWhitespace() }
        if (block.length < 16) return emptyList()
        return runCatching { links(base64(block)) }.getOrDefault(emptyList())
    }

    private fun links(text: String): List<String> = text.lineSequence().map { it.trim() }.filter(::isShareLink).distinct().toList()

    fun isShareLink(text: String): Boolean {
        val scheme = text.trim().substringBefore("://", "")
        return scheme.isNotEmpty() && scheme.lowercase() in schemes
    }

    fun parse(link: String): SharedNode {
        val text = link.trim()
        return when (text.substringBefore("://", "").lowercase()) {
            "vless" -> vless(text)
            "vmess" -> vmess(text)
            "trojan" -> trojan(text)
            "ss" -> shadowsocks(text)
            "hysteria2", "hy2" -> hysteria2(text)
            "tuic" -> tuic(text)
            "anytls" -> anytls(text)
            else -> fail("Unrecognized link")
        }
    }

    private fun vless(link: String): SharedNode {
        val parts = split(link)
        val host = server(parts)
        val security = parts.query["security"]?.trim()?.lowercase().orEmpty()
        // VLESS carries no encryption of its own.
        if (security !in setOf("tls", "reality", "xtls")) fail("VLESS links require TLS or Reality encryption.")
        val node = node("vless", host, port(parts.port, 443)).put("uuid", uuid(parts.userInfo))
        parts.query["flow"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it != "xtls-rprx-vision") fail("Unsupported VLESS flow")
            node.put("flow", it)
        }
        node.put("tls", tls(parts.query, host, security, fingerprintByDefault = true))
        transport(parts.query)?.let { node.put("transport", it) }
        return SharedNode(node, parts.name)
    }

    /** The v2rayN form: base64 of a JSON object. */
    private fun vmess(link: String): SharedNode {
        val body = link.substringAfter("://").substringBefore('#')
        val json = try {
            JSONObject(base64(body))
        } catch (_: Exception) {
            fail("Invalid VMess link format")
        }
        fun field(key: String) = json.opt(key)?.toString()?.trim().orEmpty()
        val host = field("add").ifEmpty { fail(NO_SERVER) }
        val node = node("vmess", host, port(field("port").ifEmpty { null }, null))
            .put("uuid", uuid(field("id")))
            .put("security", field("scy").ifEmpty { "auto" })
        field("aid").toIntOrNull()?.takeIf { it > 0 }?.let { node.put("alter_id", it) }
        val network = field("net")
        val query = mapOf(
            "type" to network,
            "headerType" to field("type"),
            "host" to field("host"),
            "path" to field("path"),
            // v2rayN keeps a gRPC service name in "path".
            "serviceName" to if (network == "grpc") field("path") else "",
            "sni" to field("sni"),
            "alpn" to field("alpn"),
            "fp" to field("fp"),
            "allowInsecure" to field("allowInsecure"),
        )
        when (val security = field("tls").lowercase()) {
            "", "none" -> Unit
            "tls" -> node.put("tls", tls(query, host, security, fingerprintByDefault = false))
            else -> fail("The link uses encryption unsupported by sing-box.")
        }
        transport(query)?.let { node.put("transport", it) }
        return SharedNode(node, tidy(field("ps")) ?: nameOf(link.substringAfter('#', "")))
    }

    private fun trojan(link: String): SharedNode {
        val parts = split(link)
        val host = server(parts)
        val security = parts.query["security"]?.trim()?.lowercase()?.ifEmpty { null } ?: "tls"
        if (security !in setOf("tls", "reality", "xtls")) fail("Trojan links require TLS encryption.")
        val node = node("trojan", host, port(parts.port, 443)).put("password", secret(parts.userInfo))
        node.put("tls", tls(parts.query, host, security, fingerprintByDefault = true))
        transport(parts.query)?.let { node.put("transport", it) }
        return SharedNode(node, parts.name)
    }

    /** SIP002 (`method:password` in base64 or written out) and the older all-base64 form. */
    private fun shadowsocks(link: String): SharedNode {
        val parts = split(link)
        val method: String
        val password: String
        val host: String
        val port: Int
        if (parts.userInfo != null) {
            val info = decode(parts.userInfo)
            val plain = if (':' in info) info else base64(info)
            method = plain.substringBefore(':')
            password = plain.substringAfter(':', "")
            host = server(parts)
            port = port(parts.port, null)
        } else {
            val whole = base64(link.substringAfter("://").substringBefore('#').substringBefore('?').trimEnd('/'))
            val at = whole.lastIndexOf('@').takeIf { it > 0 } ?: fail(BAD_FORMAT)
            val credentials = whole.substring(0, at)
            val address = whole.substring(at + 1)
            method = credentials.substringBefore(':')
            password = credentials.substringAfter(':', "")
            host = address.substringBeforeLast(':').removeSurrounding("[", "]").trim().ifEmpty { fail(NO_SERVER) }
            port = port(address.substringAfterLast(':', "").ifEmpty { null }, null)
        }
        if (method.isBlank()) fail(BAD_FORMAT)
        if (password.isEmpty() && !method.equals("none", ignoreCase = true)) fail(NO_PASSWORD)
        val node = node("shadowsocks", host, port).put("method", method.trim().lowercase()).put("password", password)
        parts.query["plugin"]?.trim()?.takeIf { it.isNotEmpty() }?.let { plugin ->
            node.put("plugin", plugins[plugin.substringBefore(';').trim()] ?: fail("Supported Shadowsocks plugins: obfs-local and v2ray-plugin."))
            plugin.substringAfter(';', "").takeIf { it.isNotBlank() }?.let { node.put("plugin_opts", it) }
        }
        return SharedNode(node, parts.name)
    }

    private fun hysteria2(link: String): SharedNode {
        val parts = split(link)
        val host = server(parts)
        // "443,20000-30000": the first number is the port, the rest are ranges it may hop across.
        val ports = parts.port.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val first = ports.firstOrNull()?.takeIf { '-' !in it }
        val node = node("hysteria2", host, port(first, 443)).put("password", secret(parts.userInfo))
        val hops = (if (first != null) ports.drop(1) else ports) +
            parts.query["mport"].orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (hops.isNotEmpty()) {
            // sing-box takes either one port or a list of ranges, never both: the port joins the list.
            node.remove("server_port")
            node.put("server_ports", JSONArray(listOfNotNull(first).map(::portRange) + hops.map(::portRange)))
        }
        node.put("tls", quicTls(parts.query, host))
        parts.query["obfs"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "none" }?.let { kind ->
            if (kind != "salamander") fail("Hysteria2 supports only salamander obfuscation.")
            val password = parts.query["obfs-password"]?.takeIf { it.isNotEmpty() } ?: fail("Hysteria2 obfuscation has no password.")
            node.put("obfs", JSONObject().put("type", "salamander").put("password", password))
        }
        parts.query["upmbps"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { node.put("up_mbps", it) }
        parts.query["downmbps"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }?.let { node.put("down_mbps", it) }
        return SharedNode(node, parts.name)
    }

    private fun tuic(link: String): SharedNode {
        val parts = split(link)
        val host = server(parts)
        val info = parts.userInfo?.let(::decode) ?: fail(BAD_ID)
        val password = info.substringAfter(':', "").ifEmpty { fail(NO_PASSWORD) }
        val node = node("tuic", host, port(parts.port, 443)).put("uuid", uuid(info.substringBefore(':'))).put("password", password)
        (parts.query["congestion_control"] ?: parts.query["congestion"])?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            ?.let { node.put("congestion_control", it) }
        parts.query["udp_relay_mode"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { node.put("udp_relay_mode", it) }
        node.put("tls", quicTls(parts.query, host))
        return SharedNode(node, parts.name)
    }

    private fun anytls(link: String): SharedNode {
        val parts = split(link)
        val host = server(parts)
        val node = node("anytls", host, port(parts.port, 443)).put("password", secret(parts.userInfo))
        node.put("tls", tls(parts.query, host, "tls", fingerprintByDefault = false))
        return SharedNode(node, parts.name)
    }

    private fun node(type: String, host: String, port: Int): JSONObject =
        JSONObject().put("type", type).put("tag", NODE_TAG).put("server", host).put("server_port", port)

    /** TLS over TCP. Reality needs uTLS; [fingerprintByDefault] uses Chrome's when the link names none. */
    private fun tls(query: Map<String, String>, host: String, security: String, fingerprintByDefault: Boolean): JSONObject {
        val tls = JSONObject().put("enabled", true).put("server_name", serverName(query, host))
        if (query.flag("allowInsecure", "insecure", "allow_insecure")) tls.put("insecure", true)
        alpn(query)?.let { tls.put("alpn", it) }
        val fingerprint = query["fp"]?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "none" }
        if (fingerprint != null && fingerprint !in FINGERPRINTS) fail("Unsupported TLS fingerprint")
        if (security == "reality") {
            val written = query["pbk"]?.trim()?.takeIf { it.isNotEmpty() } ?: fail("The Reality link has no public key.")
            val key = realityKey(written) ?: fail("Invalid Reality public key format")
            val shortId = query["sid"]?.trim().orEmpty()
            if (shortId.length > 16 || shortId.length % 2 != 0 || !shortId.all { it in "0123456789abcdefABCDEF" }) fail("Invalid Reality short ID format")
            tls.put("reality", JSONObject().put("enabled", true).put("public_key", key).put("short_id", shortId))
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint ?: "chrome"))
        } else if (fingerprint != null || fingerprintByDefault) {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", fingerprint ?: "chrome"))
        }
        return tls
    }

    /**
     * A Reality public key as sing-box reads it (URL-safe base64 without padding), from either
     * alphabet, padded or not; null unless it holds the 32 bytes of a key.
     */
    internal fun realityKey(text: String): String? {
        val key = text.trim().replace('+', '-').replace('/', '_').trimEnd('=')
        val bytes = try {
            Base64.getUrlDecoder().decode(key)
        } catch (_: Exception) {
            return null
        }
        return key.takeIf { bytes.size == 32 }
    }

    /** TLS inside QUIC (Hysteria2, TUIC): no uTLS. */
    private fun quicTls(query: Map<String, String>, host: String): JSONObject {
        val tls = JSONObject().put("enabled", true).put("server_name", serverName(query, host))
        if (query.flag("insecure", "allowInsecure", "allow_insecure")) tls.put("insecure", true)
        alpn(query)?.let { tls.put("alpn", it) }
        return tls
    }

    private fun serverName(query: Map<String, String>, host: String): String =
        query["sni"]?.trim()?.takeIf { it.isNotEmpty() } ?: query["peer"]?.trim()?.takeIf { it.isNotEmpty() } ?: host

    private fun alpn(query: Map<String, String>): JSONArray? =
        query["alpn"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }?.let { JSONArray(it) }

    private fun transport(query: Map<String, String>): JSONObject? {
        val host = query["host"]?.trim().orEmpty()
        val path = query["path"].orEmpty()
        return when (query["type"]?.trim()?.lowercase().orEmpty()) {
            "", "tcp", "raw" -> {
                if (query["headerType"]?.trim()?.lowercase().orEmpty() !in setOf("", "none")) fail("sing-box does not support this TCP camouflage.")
                null
            }
            "ws", "websocket" -> websocket(host, path, query["ed"])
            "grpc" -> JSONObject().put("type", "grpc").put("service_name", query["serviceName"]?.ifEmpty { null } ?: path.removePrefix("/"))
            "http", "h2" -> JSONObject().put("type", "http").apply {
                host.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { put("host", JSONArray(it)) }
                if (path.isNotEmpty()) put("path", path)
            }
            "httpupgrade" -> JSONObject().put("type", "httpupgrade").apply {
                if (host.isNotEmpty()) put("host", host)
                if (path.isNotEmpty()) put("path", path)
            }
            else -> fail(TRANSPORT)
        }
    }

    /** v2rayN keeps WebSocket early data in the path as "?ed=2048"; sing-box wants fields. */
    private fun websocket(host: String, rawPath: String, ed: String?): JSONObject {
        val ws = JSONObject().put("type", "ws")
        var path = rawPath
        var early = ed?.trim()?.toIntOrNull()
        val marker = path.indexOf("?ed=")
        if (marker >= 0) {
            early = path.substring(marker + 4).substringBefore('&').toIntOrNull() ?: early
            path = path.substring(0, marker)
        }
        if (path.isNotEmpty()) ws.put("path", path)
        if (host.isNotEmpty()) ws.put("headers", JSONObject().put("Host", host))
        if (early != null && early > 0) {
            ws.put("max_early_data", early)
            ws.put("early_data_header_name", "Sec-WebSocket-Protocol")
        }
        return ws
    }

    private class Parts(val userInfo: String?, val host: String, val port: String?, val query: Map<String, String>, val name: String?)

    /** scheme://userinfo@host:port/path?query#name, read by hand: URI rejects much of what clients write. */
    private fun split(link: String): Parts {
        var rest = link.substringAfter("://")
        val name = nameOf(rest.substringAfter('#', ""))
        rest = rest.substringBefore('#')
        val query = query(rest.substringAfter('?', ""))
        rest = rest.substringBefore('?')
        val at = rest.lastIndexOf('@')
        val userInfo = if (at >= 0) rest.substring(0, at) else null
        val address = (if (at >= 0) rest.substring(at + 1) else rest).substringBefore('/')
        val host: String
        val port: String?
        if (address.startsWith("[")) {
            val end = address.indexOf(']').takeIf { it > 0 } ?: fail(BAD_FORMAT)
            host = address.substring(1, end)
            port = address.substring(end + 1).removePrefix(":").ifEmpty { null }
        } else {
            host = address.substringBefore(':')
            port = address.substringAfter(':', "").ifEmpty { null }
        }
        return Parts(userInfo, decode(host).trim(), port, query, name)
    }

    private fun query(text: String): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        text.split('&').filter { it.isNotBlank() }.forEach { pair ->
            val key = decode(pair.substringBefore('='))
            if (map.containsKey(key)) fail(DUPLICATE)
            map[key] = decode(pair.substringAfter('=', ""))
        }
        return map
    }

    private fun server(parts: Parts): String = parts.host.ifEmpty { fail(NO_SERVER) }

    private fun port(text: String?, default: Int?): Int {
        val value = if (text == null) default ?: fail(BAD_PORT) else text.trim().toIntOrNull() ?: fail(BAD_PORT)
        if (value !in 1..65535) fail(BAD_PORT)
        return value
    }

    /** "20000-30000" or a single port, written the way sing-box takes port ranges. */
    private fun portRange(text: String): String {
        val bounds = text.replace(':', '-').split('-').map { it.trim().toIntOrNull() ?: fail(BAD_PORT) }
        if (bounds.size !in 1..2 || bounds.any { it !in 1..65535 }) fail(BAD_PORT)
        return "${bounds.first()}:${bounds.last()}"
    }

    private fun uuid(text: String?): String = try {
        UUID.fromString(decode(text.orEmpty()).trim()).toString()
    } catch (_: Exception) {
        fail(BAD_ID)
    }

    private fun secret(userInfo: String?): String = userInfo?.let(::decode)?.takeIf { it.isNotEmpty() } ?: fail(NO_PASSWORD)

    /** Percent-decoding that keeps "+" as it is: passwords and keys use it literally. */
    private fun decode(text: String): String = try {
        URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        fail(BAD_FORMAT)
    }

    private fun base64(text: String): String {
        val clean = text.trim().replace('-', '+').replace('_', '/').filterNot { it.isWhitespace() || it == '=' }
        return try {
            String(Base64.getDecoder().decode(clean + "=".repeat((4 - clean.length % 4) % 4)), Charsets.UTF_8)
        } catch (_: Exception) {
            fail(BAD_FORMAT)
        }
    }

    private fun Map<String, String>.flag(vararg keys: String): Boolean = keys.any { this[it]?.trim()?.lowercase() in setOf("1", "true") }

    /** The `#name` of a link, decoded and tidied; null when it has none. */
    internal fun nameOf(fragment: String): String? {
        if (fragment.isEmpty()) return null
        val decoded = try {
            URLDecoder.decode(fragment.replace("+", "%2B"), "UTF-8")
        } catch (_: Exception) {
            fragment
        }
        return tidy(decoded)
    }

    /** The uTLS fingerprints sing-box knows; it refuses a configuration naming any other. */
    internal val FINGERPRINTS = setOf("chrome", "chrome_psk", "chrome_psk_shuffle", "chrome_padding_psk_shuffle", "chrome_pq", "chrome_pq_psk",
        "firefox", "edge", "safari", "360", "qq", "ios", "android", "random", "randomized")

    private fun tidy(name: String): String? = name.replace(Regex("[\\t\\r\\n]+"), " ").filterNot { it.isISOControl() }.trim().take(64)
        .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }.takeIf { it.isNotEmpty() }

    private fun fail(message: String): Nothing = throw ShareLinkException(message)
}
