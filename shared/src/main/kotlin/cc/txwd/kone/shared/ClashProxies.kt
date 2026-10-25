package cc.txwd.kone.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.util.UUID

/**
 * The `proxies` of a Clash (mihomo) profile, which most providers send by default, each turned
 * into the sing-box outbound that reaches the same server the same way. The profile's groups,
 * rules and DNS are KOne's own business and are ignored. A proxy sing-box cannot carry, or one
 * that is incomplete, is left out with a reason in fixed words.
 */
object ClashProxies {
    /** Whether [text] reads as a Clash profile rather than share links. */
    fun looksLikeClash(text: String): Boolean = Regex("""(?m)^proxies\s*:""").containsMatchIn(text)

    /** A profile's proxies read: each outbound with its name, how many were left out, and the first reason. */
    class Read(val named: List<Pair<JsonObject, String?>>, val refused: Int, val refusal: String?)

    /** The proxies of the Clash profile in [text], each as a sing-box outbound without a tag. */
    fun read(text: String): Read {
        val root = load(text) ?: return Read(emptyList(), 0, "The Clash profile cannot be read")
        val proxies = (root["proxies"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
        var refused = 0
        var refusal: String? = null
        val named = proxies.mapNotNull { proxy ->
            runCatching { convert(proxy) to proxy.text("name") }.onFailure { refused++; refusal = refusal ?: it.message }.getOrNull()
        }
        if (named.isEmpty() && refusal == null) refusal = "No nodes found in the profile"
        return Read(named, refused, refusal)
    }

    /**
     * [text] as a YAML document's top level, read safely (plain maps, lists and scalars only, few
     * aliases, 8 MB at most); null when it is not a YAML map.
     */
    internal fun load(text: String): Map<*, *>? {
        val options = LoaderOptions().apply {
            maxAliasesForCollections = 50
            codePointLimit = 8 * 1024 * 1024
            isAllowDuplicateKeys = true
        }
        return runCatching { Yaml(SafeConstructor(options)).load<Any?>(text) as? Map<*, *> }.getOrNull()
    }

    /** One Clash proxy as a sing-box outbound (its tag is set by the caller). */
    internal fun convert(proxy: Map<*, *>): JsonObject {
        val type = proxy.text("type")?.lowercase() ?: fail("A proxy has no type")
        val server = proxy.text("server")?.takeIf { it.isNotBlank() } ?: fail("A proxy has no server")
        val port = proxy.port("port")
        val base = mutableMapOf<String, JsonElement>("server" to JsonPrimitive(server))
        when (type) {
            "ss" -> {
                base["type"] = JsonPrimitive("shadowsocks")
                base["method"] = JsonPrimitive(proxy.text("cipher")?.lowercase() ?: fail("A Shadowsocks proxy has no cipher"))
                base["password"] = JsonPrimitive(proxy.text("password") ?: fail("A Shadowsocks proxy has no password"))
                base["server_port"] = JsonPrimitive(port ?: fail("A proxy has no port"))
                shadowsocksPlugin(proxy)?.let { (plugin, opts) ->
                    base["plugin"] = JsonPrimitive(plugin)
                    if (opts.isNotEmpty()) base["plugin_opts"] = JsonPrimitive(opts)
                }
                if (proxy.flag("udp-over-tcp")) base["udp_over_tcp"] = JsonPrimitive(true)
            }
            "vmess" -> {
                base["type"] = JsonPrimitive("vmess")
                base["server_port"] = JsonPrimitive(port ?: fail("A proxy has no port"))
                base["uuid"] = JsonPrimitive(uuid(proxy.text("uuid")))
                base["security"] = JsonPrimitive(proxy.text("cipher")?.lowercase() ?: "auto")
                proxy.int("alterId")?.takeIf { it > 0 }?.let { base["alter_id"] = JsonPrimitive(it) }
                if (proxy.flag("tls")) base["tls"] = tls(proxy, server)
                transport(proxy)?.let { base["transport"] = it }
            }
            "vless" -> {
                base["type"] = JsonPrimitive("vless")
                base["server_port"] = JsonPrimitive(port ?: fail("A proxy has no port"))
                base["uuid"] = JsonPrimitive(uuid(proxy.text("uuid")))
                proxy.text("flow")?.takeIf { it.isNotBlank() }?.let {
                    // As mihomo and sing-box both hold: Vision or nothing.
                    if (it != "xtls-rprx-vision") fail("Unsupported VLESS flow")
                    base["flow"] = JsonPrimitive(it)
                }
                // VLESS carries no encryption of its own.
                if (!proxy.flag("tls") && proxy["reality-opts"] == null) fail("VLESS proxies require TLS or Reality encryption.")
                base["tls"] = tls(proxy, server)
                transport(proxy)?.let { base["transport"] = it }
            }
            "trojan" -> {
                base["type"] = JsonPrimitive("trojan")
                base["server_port"] = JsonPrimitive(port ?: fail("A proxy has no port"))
                base["password"] = JsonPrimitive(proxy.text("password") ?: fail("A Trojan proxy has no password"))
                base["tls"] = tls(proxy, server)
                transport(proxy)?.let { base["transport"] = it }
            }
            "hysteria2", "hy2" -> {
                base["type"] = JsonPrimitive("hysteria2")
                base["password"] = JsonPrimitive(proxy.text("password") ?: proxy.text("auth") ?: fail("A Hysteria2 proxy has no password"))
                val hops = proxy.text("ports")?.split(',', '/')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                if (hops.isNotEmpty()) {
                    base["server_ports"] = buildJsonArray { hops.forEach { add(JsonPrimitive(range(it))) } }
                } else {
                    base["server_port"] = JsonPrimitive(port ?: 443)
                }
                proxy.text("obfs")?.lowercase()?.takeIf { it.isNotEmpty() && it != "none" }?.let { kind ->
                    if (kind != "salamander") fail("Hysteria2 supports only salamander obfuscation.")
                    val password = proxy.text("obfs-password") ?: fail("Hysteria2 obfuscation has no password.")
                    base["obfs"] = buildJsonObject { put("type", "salamander"); put("password", password) }
                }
                mbps(proxy.text("up"))?.let { base["up_mbps"] = JsonPrimitive(it) }
                mbps(proxy.text("down"))?.let { base["down_mbps"] = JsonPrimitive(it) }
                base["tls"] = quicTls(proxy, server)
            }
            "tuic" -> {
                base["type"] = JsonPrimitive("tuic")
                base["server_port"] = JsonPrimitive(port ?: fail("A proxy has no port"))
                base["uuid"] = JsonPrimitive(uuid(proxy.text("uuid")))
                base["password"] = JsonPrimitive(proxy.text("password") ?: fail("A TUIC proxy has no password"))
                proxy.text("congestion-controller")?.lowercase()?.let { base["congestion_control"] = JsonPrimitive(it) }
                proxy.text("udp-relay-mode")?.lowercase()?.let { base["udp_relay_mode"] = JsonPrimitive(it) }
                if (proxy.flag("reduce-rtt")) base["zero_rtt_handshake"] = JsonPrimitive(true)
                base["tls"] = quicTls(proxy, server)
            }
            "anytls" -> {
                base["type"] = JsonPrimitive("anytls")
                base["server_port"] = JsonPrimitive(port ?: fail("A proxy has no port"))
                base["password"] = JsonPrimitive(proxy.text("password") ?: fail("An AnyTLS proxy has no password"))
                base["tls"] = tls(proxy, server)
            }
            "socks5" -> {
                if (proxy.flag("tls")) fail("sing-box does not support SOCKS over TLS.")
                base["type"] = JsonPrimitive("socks")
                base["server_port"] = JsonPrimitive(port ?: fail("A proxy has no port"))
                proxy.text("username")?.let { base["username"] = JsonPrimitive(it) }
                proxy.text("password")?.let { base["password"] = JsonPrimitive(it) }
            }
            "http" -> {
                base["type"] = JsonPrimitive("http")
                base["server_port"] = JsonPrimitive(port ?: fail("A proxy has no port"))
                proxy.text("username")?.let { base["username"] = JsonPrimitive(it) }
                proxy.text("password")?.let { base["password"] = JsonPrimitive(it) }
                if (proxy.flag("tls")) base["tls"] = tls(proxy, server)
            }
            else -> fail("sing-box does not support this kind of proxy.")
        }
        return JsonObject(base)
    }

    /** obfs and v2ray-plugin as SIP003 options, the way sing-box takes them; anything else is refused. */
    private fun shadowsocksPlugin(proxy: Map<*, *>): Pair<String, String>? {
        val plugin = proxy.text("plugin")?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val opts = proxy["plugin-opts"] as? Map<*, *> ?: emptyMap<Any, Any>()
        return when (plugin) {
            "obfs", "obfs-local", "simple-obfs" -> "obfs-local" to listOfNotNull(
                opts.text("mode")?.let { "obfs=$it" },
                opts.text("host")?.let { "obfs-host=$it" },
            ).joinToString(";")
            "v2ray-plugin" -> {
                if (opts.text("mode")?.lowercase().let { it != null && it != "websocket" }) fail("Shadowsocks v2ray-plugin supports only websocket mode.")
                "v2ray-plugin" to listOfNotNull(
                    "tls".takeIf { opts.flag("tls") },
                    opts.text("host")?.let { "host=$it" },
                    opts.text("path")?.let { "path=$it" },
                    "mux=0".takeIf { opts.containsKey("mux") && !opts.flag("mux") },
                ).joinToString(";")
            }
            else -> fail("Supported Shadowsocks plugins: obfs-local and v2ray-plugin.")
        }
    }

    /**
     * TLS over TCP, with Reality and uTLS when the proxy asks, as mihomo does: a fingerprint only
     * when one is named, except that Reality needs one (Chrome's when none is named). A
     * fingerprint sing-box does not know is left out, as mihomo leaves it out for plain TLS; Reality
     * cannot do without one, so there it is refused. A Reality key is read in either base64
     * alphabet, as a share link's is.
     */
    private fun tls(proxy: Map<*, *>, server: String): JsonObject = buildJsonObject {
        put("enabled", true)
        put("server_name", proxy.text("servername") ?: proxy.text("sni") ?: server)
        if (proxy.flag("skip-cert-verify")) put("insecure", true)
        alpn(proxy)?.let { put("alpn", it) }
        val named = proxy.text("client-fingerprint")?.lowercase()?.takeIf { it.isNotBlank() && it != "none" }
        val fingerprint = named?.takeIf { it in ShareLinks.FINGERPRINTS }
        val reality = proxy["reality-opts"] as? Map<*, *>
        if (reality != null) {
            if (named != null && fingerprint == null) fail("Unsupported TLS fingerprint")
            val written = reality.text("public-key")?.takeIf { it.isNotBlank() } ?: fail("The Reality proxy has no public key.")
            val key = ShareLinks.realityKey(written) ?: fail("Invalid Reality public key format")
            val shortId = reality.text("short-id").orEmpty()
            if (shortId.length > 16 || shortId.length % 2 != 0 || !shortId.all { it in "0123456789abcdefABCDEF" }) fail("Invalid Reality short ID format")
            put("reality", buildJsonObject { put("enabled", true); put("public_key", key); put("short_id", shortId) })
            put("utls", buildJsonObject { put("enabled", true); put("fingerprint", fingerprint ?: "chrome") })
        } else if (fingerprint != null) {
            put("utls", buildJsonObject { put("enabled", true); put("fingerprint", fingerprint) })
        }
    }

    /** TLS inside QUIC (Hysteria2, TUIC): no uTLS. */
    private fun quicTls(proxy: Map<*, *>, server: String): JsonObject = buildJsonObject {
        put("enabled", true)
        put("server_name", proxy.text("sni") ?: proxy.text("servername") ?: server)
        if (proxy.flag("skip-cert-verify")) put("insecure", true)
        alpn(proxy)?.let { put("alpn", it) }
    }

    private fun alpn(proxy: Map<*, *>): JsonArray? {
        val names = when (val value = proxy["alpn"]) {
            is List<*> -> value.mapNotNull { it?.toString()?.trim() }
            is String -> value.split(',').map { it.trim() }
            else -> emptyList()
        }.filter { it.isNotEmpty() }
        return names.takeIf { it.isNotEmpty() }?.let { list -> buildJsonArray { list.forEach { add(JsonPrimitive(it)) } } }
    }

    private fun transport(proxy: Map<*, *>): JsonObject? = when (proxy.text("network")?.lowercase().orEmpty()) {
        "", "tcp" -> null
        "ws" -> {
            val opts = proxy["ws-opts"] as? Map<*, *> ?: emptyMap<Any, Any>()
            val host = (opts["headers"] as? Map<*, *>)?.text("Host") ?: (opts["headers"] as? Map<*, *>)?.text("host")
            buildJsonObject {
                put("type", if (opts.flag("v2ray-http-upgrade")) "httpupgrade" else "ws")
                opts.text("path")?.takeIf { it.isNotEmpty() }?.let { put("path", it) }
                if (opts.flag("v2ray-http-upgrade")) {
                    host?.let { put("host", it) }
                } else {
                    host?.let { put("headers", buildJsonObject { put("Host", it) }) }
                    opts.int("max-early-data")?.takeIf { it > 0 }?.let { early ->
                        put("max_early_data", early)
                        put("early_data_header_name", opts.text("early-data-header-name") ?: "Sec-WebSocket-Protocol")
                    }
                }
            }
        }
        "grpc" -> buildJsonObject {
            put("type", "grpc")
            ((proxy["grpc-opts"] as? Map<*, *>)?.text("grpc-service-name"))?.let { put("service_name", it) }
        }
        "h2" -> {
            val opts = proxy["h2-opts"] as? Map<*, *> ?: emptyMap<Any, Any>()
            buildJsonObject {
                put("type", "http")
                strings(opts["host"]).takeIf { it.isNotEmpty() }?.let { hosts -> put("host", buildJsonArray { hosts.forEach { add(JsonPrimitive(it)) } }) }
                opts.text("path")?.let { put("path", it) }
            }
        }
        "http" -> {
            val opts = proxy["http-opts"] as? Map<*, *> ?: emptyMap<Any, Any>()
            buildJsonObject {
                put("type", "http")
                opts.text("method")?.let { put("method", it) }
                strings(opts["path"]).firstOrNull()?.let { put("path", it) }
                val hosts = strings((opts["headers"] as? Map<*, *>)?.get("Host"))
                if (hosts.isNotEmpty()) put("host", buildJsonArray { hosts.forEach { add(JsonPrimitive(it)) } })
            }
        }
        else -> fail("The proxy uses a transport unsupported by sing-box.")
    }

    private fun strings(value: Any?): List<String> = when (value) {
        is List<*> -> value.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
        is String -> listOf(value.trim()).filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun uuid(text: String?): String = runCatching { UUID.fromString(text?.trim()).toString() }.getOrNull() ?: fail("Invalid user ID in the proxy")

    /** "443-8443" or "443" as sing-box writes a port range. */
    private fun range(text: String): String {
        val bounds = text.replace(':', '-').split('-').map { it.trim().toIntOrNull() ?: fail("Invalid proxy port") }
        if (bounds.size !in 1..2 || bounds.any { it !in 1..65535 }) fail("Invalid proxy port")
        return "${bounds.first()}:${bounds.last()}"
    }

    /** "30 Mbps", "30", "100mbps" as whole megabits; null for anything else. */
    private fun mbps(text: String?): Int? = text?.trim()?.lowercase()?.let { Regex("""^(\d+)\s*(mbps)?$""").find(it)?.groupValues?.get(1)?.toIntOrNull() }?.takeIf { it > 0 }

    private fun Map<*, *>.text(key: String): String? = when (val value = this[key]) {
        is String -> value.trim().takeIf { it.isNotEmpty() }
        is Number, is Boolean -> value.toString()
        else -> null
    }

    private fun Map<*, *>.int(key: String): Int? = when (val value = this[key]) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    private fun Map<*, *>.port(key: String): Int? = int(key)?.also { if (it !in 1..65535) fail("Invalid proxy port") }

    private fun Map<*, *>.flag(key: String): Boolean = when (val value = this[key]) {
        is Boolean -> value
        is String -> value.trim().lowercase() in setOf("true", "1", "yes")
        is Number -> value.toInt() != 0
        else -> false
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)
}
