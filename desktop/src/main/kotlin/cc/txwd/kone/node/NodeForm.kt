package cc.txwd.kone.node

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.UUID

/**
 * A node written by hand: the fields of a form, as text, for every kind KOne carries. [build]
 * turns them into the sing-box outbound (or says, in fixed words, what is missing), and [read]
 * turns an outbound back into fields, so a node can be edited and saved again unchanged.
 */
data class NodeDraft(
    val type: String = "vless",
    val name: String = "",
    val server: String = "",
    val port: String = "443",
    /** VLESS, VMess and TUIC. */
    val uuid: String = "",
    /** Trojan, Shadowsocks, Hysteria2, TUIC, AnyTLS, and SOCKS or HTTP with a user. */
    val password: String = "",
    val username: String = "",
    /** Shadowsocks: the cipher. VMess: its security ("auto"). */
    val method: String = "",
    val flow: String = "",
    val alterId: String = "",
    /** "none", "tls" or "reality". */
    val security: String = "tls",
    val sni: String = "",
    val insecure: Boolean = false,
    val fingerprint: String = "",
    val alpn: String = "",
    val publicKey: String = "",
    val shortId: String = "",
    /** "tcp", "ws", "grpc", "http" or "httpupgrade". */
    val transport: String = "tcp",
    val host: String = "",
    val path: String = "",
    val serviceName: String = "",
    /** Shadowsocks plugin (obfs-local or v2ray-plugin) and its options. */
    val plugin: String = "",
    val pluginOptions: String = "",
    /** Hysteria2: other ports it may hop across ("20000-30000"), salamander password, and speeds in Mbps. */
    val hops: String = "",
    val obfsPassword: String = "",
    val up: String = "",
    val down: String = "",
    /** TUIC. */
    val congestion: String = "",
    val udpRelay: String = "",
)

object NodeForm {
    /** The kinds a form can write, as the page names them. */
    val TYPES = listOf(
        "vless" to "VLESS", "vmess" to "VMess", "trojan" to "Trojan", "shadowsocks" to "Shadowsocks",
        "hysteria2" to "Hysteria2", "tuic" to "TUIC", "anytls" to "AnyTLS", "socks" to "SOCKS", "http" to "HTTP",
    )

    /** The fields each kind uses, so the form shows only those. */
    fun fields(type: String): Set<String> = when (type) {
        "vless" -> setOf("uuid", "flow", "security", "transport")
        "vmess" -> setOf("uuid", "method", "alterId", "security", "transport")
        "trojan" -> setOf("password", "security", "transport")
        "shadowsocks" -> setOf("method", "password", "plugin")
        "hysteria2" -> setOf("password", "tls", "hops", "obfs", "speed")
        "tuic" -> setOf("uuid", "password", "tls", "congestion")
        "anytls" -> setOf("password", "tls", "fingerprint")
        "socks" -> setOf("username", "password")
        "http" -> setOf("username", "password", "security")
        else -> emptySet()
    }

    /** The encryption choices a kind offers. */
    fun securities(type: String): List<String> = when (type) {
        "vless", "trojan" -> listOf("tls", "reality")
        "vmess", "http" -> listOf("none", "tls")
        else -> emptyList()
    }

    val TRANSPORTS = listOf("tcp", "ws", "grpc", "http", "httpupgrade")
    val CIPHERS = listOf(
        "aes-128-gcm", "aes-256-gcm", "chacha20-ietf-poly1305", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm", "2022-blake3-chacha20-poly1305", "none",
    )

    /** The outbound for [draft], tagged with its name; or the first thing wrong, in fixed words that never repeat a secret. */
    fun build(draft: NodeDraft): Result<JsonObject> = runCatching {
        val type = draft.type
        if (TYPES.none { it.first == type }) fail("Choose a kind of node")
        val server = draft.server.trim().removePrefix("[").removeSuffix("]").ifEmpty { fail("Enter the server") }
        if (server.any { it.isWhitespace() || it in "/?#@" }) fail("The server is not an address or a name")
        val fields = fields(type)
        val out = linkedMapOf<String, JsonElement>("type" to JsonPrimitive(type), "tag" to JsonPrimitive(draft.name.trim().ifEmpty { server }.take(64)))
        out["server"] = JsonPrimitive(server)
        val hops = if ("hops" in fields) draft.hops.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map(::range) else emptyList()
        val port = draft.port.trim().takeIf { it.isNotEmpty() }?.let { text -> text.toIntOrNull()?.takeIf { it in 1..65535 } ?: fail("The port is not 1 to 65535") }
        if (hops.isEmpty()) out["server_port"] = JsonPrimitive(port ?: fail("Enter the port")) else {
            out["server_ports"] = JsonArray(listOfNotNull(port?.let { JsonPrimitive("$it:$it") }) + hops.map(::JsonPrimitive))
        }
        if ("uuid" in fields) out["uuid"] = JsonPrimitive(runCatching { UUID.fromString(draft.uuid.trim()).toString() }.getOrNull() ?: fail("The UUID is not valid"))
        if ("username" in fields) draft.username.takeIf { it.isNotEmpty() }?.let { out["username"] = JsonPrimitive(it) }
        when (type) {
            "shadowsocks" -> {
                val method = draft.method.trim().lowercase().ifEmpty { fail("Choose a cipher") }
                out["method"] = JsonPrimitive(method)
                if (draft.password.isEmpty() && method != "none") fail("Enter the password")
                out["password"] = JsonPrimitive(draft.password)
                draft.plugin.trim().takeIf { it.isNotEmpty() }?.let { plugin ->
                    if (plugin !in setOf("obfs-local", "v2ray-plugin")) fail("Supported Shadowsocks plugins: obfs-local and v2ray-plugin.")
                    out["plugin"] = JsonPrimitive(plugin)
                    draft.pluginOptions.trim().takeIf { it.isNotEmpty() }?.let { out["plugin_opts"] = JsonPrimitive(it) }
                }
            }
            "vmess" -> {
                out["security"] = JsonPrimitive(draft.method.trim().lowercase().ifEmpty { "auto" })
                draft.alterId.trim().takeIf { it.isNotEmpty() }?.let { text -> text.toIntOrNull()?.takeIf { it in 0..65535 } ?: fail("The alter ID is not a number") }
                    ?.takeIf { it > 0 }?.let { out["alter_id"] = JsonPrimitive(it) }
            }
            "socks", "http" -> draft.password.takeIf { it.isNotEmpty() }?.let { out["password"] = JsonPrimitive(it) }
            else -> if ("password" in fields) out["password"] = JsonPrimitive(draft.password.ifEmpty { fail("Enter the password") })
        }
        if ("flow" in fields) draft.flow.trim().takeIf { it.isNotEmpty() }?.let { out["flow"] = JsonPrimitive(it) }
        if (type == "tuic") {
            draft.congestion.trim().lowercase().takeIf { it.isNotEmpty() }?.let { out["congestion_control"] = JsonPrimitive(it) }
            draft.udpRelay.trim().lowercase().takeIf { it.isNotEmpty() }?.let { out["udp_relay_mode"] = JsonPrimitive(it) }
        }
        if (type == "hysteria2") {
            draft.obfsPassword.takeIf { it.isNotEmpty() }?.let { out["obfs"] = JsonObject(mapOf("type" to JsonPrimitive("salamander"), "password" to JsonPrimitive(it))) }
            mbps(draft.up, "upload")?.let { out["up_mbps"] = JsonPrimitive(it) }
            mbps(draft.down, "download")?.let { out["down_mbps"] = JsonPrimitive(it) }
        }
        tls(draft, server, type, fields)?.let { out["tls"] = it }
        if ("transport" in fields) transport(draft)?.let { out["transport"] = it }
        JsonObject(out)
    }

    private fun tls(draft: NodeDraft, server: String, type: String, fields: Set<String>): JsonObject? {
        val offered = securities(type)
        val security = when {
            "tls" in fields -> "tls"
            offered.isEmpty() -> return null
            draft.security in offered -> draft.security
            else -> offered.first()
        }
        if (security == "none") return null
        val tls = linkedMapOf<String, JsonElement>("enabled" to JsonPrimitive(true), "server_name" to JsonPrimitive(draft.sni.trim().ifEmpty { server }))
        if (draft.insecure) tls["insecure"] = JsonPrimitive(true)
        draft.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { tls["alpn"] = JsonArray(it.map(::JsonPrimitive)) }
        // QUIC kinds (Hysteria2, TUIC) carry no uTLS.
        val quic = type == "hysteria2" || type == "tuic"
        val fingerprint = draft.fingerprint.trim().lowercase().takeIf { it.isNotEmpty() && !quic }
        if (security == "reality") {
            val key = draft.publicKey.trim().ifEmpty { fail("Enter the Reality public key") }
            val bytes = runCatching { java.util.Base64.getUrlDecoder().decode(key) }.getOrNull()
            if (bytes == null || bytes.size != 32) fail("Reality public key format is not valid")
            val shortId = draft.shortId.trim()
            if (shortId.length > 16 || shortId.length % 2 != 0 || !shortId.all { it in "0123456789abcdefABCDEF" }) fail("Invalid Reality short ID format")
            tls["reality"] = JsonObject(mapOf("enabled" to JsonPrimitive(true), "public_key" to JsonPrimitive(key), "short_id" to JsonPrimitive(shortId)))
            tls["utls"] = JsonObject(mapOf("enabled" to JsonPrimitive(true), "fingerprint" to JsonPrimitive(fingerprint ?: "chrome")))
        } else if (fingerprint != null) {
            tls["utls"] = JsonObject(mapOf("enabled" to JsonPrimitive(true), "fingerprint" to JsonPrimitive(fingerprint)))
        }
        return JsonObject(tls)
    }

    private fun transport(draft: NodeDraft): JsonObject? {
        val host = draft.host.trim()
        val path = draft.path.trim()
        return when (draft.transport) {
            "", "tcp" -> null
            "ws" -> JsonObject(buildMap {
                put("type", JsonPrimitive("ws"))
                if (path.isNotEmpty()) put("path", JsonPrimitive(path))
                if (host.isNotEmpty()) put("headers", JsonObject(mapOf("Host" to JsonPrimitive(host))))
            })
            "grpc" -> JsonObject(mapOf("type" to JsonPrimitive("grpc"), "service_name" to JsonPrimitive(draft.serviceName.trim())))
            "http" -> JsonObject(buildMap {
                put("type", JsonPrimitive("http"))
                host.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }?.let { put("host", JsonArray(it.map(::JsonPrimitive))) }
                if (path.isNotEmpty()) put("path", JsonPrimitive(path))
            })
            "httpupgrade" -> JsonObject(buildMap {
                put("type", JsonPrimitive("httpupgrade"))
                if (host.isNotEmpty()) put("host", JsonPrimitive(host))
                if (path.isNotEmpty()) put("path", JsonPrimitive(path))
            })
            else -> fail("The transport is not one sing-box supports")
        }
    }

    /**
     * The fields of [outbound], for editing; null for a node the form cannot show whole (a chain,
     * or a field the form has no place for), so it is never saved back with something lost.
     */
    fun read(outbound: JsonObject, name: String): NodeDraft? {
        val type = outbound.text("type") ?: return null
        if (TYPES.none { it.first == type } || outbound.text("detour") != null) return null
        val tls = outbound["tls"] as? JsonObject
        val reality = tls?.get("reality") as? JsonObject
        val transport = outbound["transport"] as? JsonObject
        val ports = (outbound["server_ports"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val single = ports.firstOrNull()?.split(':')?.takeIf { it.size == 2 && it[0] == it[1] }?.get(0)
        val draft = NodeDraft(
            type = type,
            name = name,
            server = outbound.text("server").orEmpty(),
            port = (outbound["server_port"] as? JsonPrimitive)?.intOrNull?.toString() ?: single.orEmpty(),
            uuid = outbound.text("uuid").orEmpty(),
            password = outbound.text("password").orEmpty(),
            username = outbound.text("username").orEmpty(),
            method = if (type == "vmess") outbound.text("security").orEmpty() else outbound.text("method").orEmpty(),
            flow = outbound.text("flow").orEmpty(),
            alterId = (outbound["alter_id"] as? JsonPrimitive)?.intOrNull?.toString().orEmpty(),
            security = when {
                reality?.bool("enabled") == true -> "reality"
                tls?.bool("enabled") == true -> "tls"
                else -> "none"
            },
            sni = tls?.text("server_name")?.takeIf { it != outbound.text("server") }.orEmpty(),
            insecure = tls?.bool("insecure") == true,
            fingerprint = (tls?.get("utls") as? JsonObject)?.text("fingerprint")?.takeUnless { reality?.bool("enabled") == true && it == "chrome" }.orEmpty(),
            alpn = (tls?.get("alpn") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(",").orEmpty(),
            publicKey = reality?.text("public_key").orEmpty(),
            shortId = reality?.text("short_id").orEmpty(),
            transport = transport?.text("type") ?: "tcp",
            host = when (transport?.text("type")) {
                "ws" -> (transport["headers"] as? JsonObject)?.text("Host").orEmpty()
                "http" -> (transport["host"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.joinToString(",").orEmpty()
                "httpupgrade" -> transport.text("host").orEmpty()
                else -> ""
            },
            path = transport?.text("path").orEmpty(),
            serviceName = transport?.text("service_name").orEmpty(),
            plugin = outbound.text("plugin").orEmpty(),
            pluginOptions = outbound.text("plugin_opts").orEmpty(),
            hops = (if (single != null) ports.drop(1) else ports).joinToString(",") { it.replace(':', '-') },
            obfsPassword = (outbound["obfs"] as? JsonObject)?.text("password").orEmpty(),
            up = (outbound["up_mbps"] as? JsonPrimitive)?.intOrNull?.toString().orEmpty(),
            down = (outbound["down_mbps"] as? JsonPrimitive)?.intOrNull?.toString().orEmpty(),
            congestion = outbound.text("congestion_control").orEmpty(),
            udpRelay = outbound.text("udp_relay_mode").orEmpty(),
        )
        // Saved again, the node must come out as it went in, or the form would lose something.
        val again = build(draft).getOrNull() ?: return null
        return draft.takeIf { JsonObject(again - "tag") == JsonObject(outbound - "tag") }
    }

    /** "20000-30000" or "443" as sing-box writes a port range. */
    private fun range(text: String): String {
        val bounds = text.replace(':', '-').split('-').map { it.trim().toIntOrNull() ?: fail("The ports to hop across are not valid") }
        if (bounds.size !in 1..2 || bounds.any { it !in 1..65535 } || bounds.first() > bounds.last()) fail("The ports to hop across are not valid")
        return "${bounds.first()}:${bounds.last()}"
    }

    private fun mbps(text: String, what: String): Int? {
        val value = text.trim().takeIf { it.isNotEmpty() } ?: return null
        return value.toIntOrNull()?.takeIf { it > 0 } ?: fail("The $what speed is not a whole number of Mbps")
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()

    private fun fail(message: String): Nothing = throw ShareLinkException(message)
}
