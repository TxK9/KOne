package cc.txwd.kone.node

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.net.URLEncoder
import java.util.Base64

/**
 * A node written back as the share link other clients read, the reverse of [ShareLinks]: what
 * comes out reads back into the same node. A node no link can carry (one going through another,
 * or of a kind links do not have) gives null.
 */
object ShareLinkWriter {
    fun write(outbound: JsonObject, name: String): String? {
        if (outbound.text("detour") != null) return null
        val host = outbound.text("server") ?: return null
        val ports = (outbound["server_ports"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        val port = outbound.int("server_port") ?: ports.firstOrNull()?.substringBefore(':')?.toIntOrNull() ?: return null
        val address = "${if (':' in host) "[$host]" else host}:$port"
        val tls = outbound["tls"] as? JsonObject
        val fragment = "#" + encode(name)
        return when (outbound.text("type")) {
            "vless" -> {
                val query = linkedMapOf("encryption" to "none")
                outbound.text("flow")?.let { query["flow"] = it }
                security(tls, query) ?: return null
                transport(outbound["transport"] as? JsonObject, query) ?: return null
                "vless://${encode(outbound.text("uuid") ?: return null)}@$address${queryText(query)}$fragment"
            }
            "trojan" -> {
                val query = linkedMapOf<String, String>()
                security(tls, query) ?: return null
                transport(outbound["transport"] as? JsonObject, query) ?: return null
                "trojan://${encode(outbound.text("password") ?: return null)}@$address${queryText(query)}$fragment"
            }
            "vmess" -> vmess(outbound, host, port, name, tls)
            "shadowsocks" -> {
                val method = outbound.text("method") ?: return null
                val password = outbound.text("password") ?: ""
                val user = Base64.getUrlEncoder().withoutPadding().encodeToString("$method:$password".toByteArray())
                val plugin = outbound.text("plugin")?.let { plugin ->
                    "?plugin=" + encode(listOfNotNull(plugin, outbound.text("plugin_opts")).joinToString(";"))
                }.orEmpty()
                "ss://$user@$address${if (plugin.isEmpty()) "" else "/$plugin"}$fragment"
            }
            "hysteria2" -> {
                val query = linkedMapOf<String, String>()
                quic(tls, query)
                val hops = ports.map { it.replace(':', '-') }.let { if (outbound["server_port"] == null) it.drop(1) else it }
                if (hops.isNotEmpty()) query["mport"] = hops.joinToString(",")
                (outbound["obfs"] as? JsonObject)?.let { obfs ->
                    query["obfs"] = obfs.text("type") ?: return null
                    query["obfs-password"] = obfs.text("password") ?: return null
                }
                outbound.int("up_mbps")?.let { query["upmbps"] = it.toString() }
                outbound.int("down_mbps")?.let { query["downmbps"] = it.toString() }
                "hysteria2://${encode(outbound.text("password") ?: return null)}@$address${queryText(query)}$fragment"
            }
            "tuic" -> {
                val query = linkedMapOf<String, String>()
                quic(tls, query)
                outbound.text("congestion_control")?.let { query["congestion_control"] = it }
                outbound.text("udp_relay_mode")?.let { query["udp_relay_mode"] = it }
                val user = encode(outbound.text("uuid") ?: return null) + ":" + encode(outbound.text("password") ?: return null)
                "tuic://$user@$address${queryText(query)}$fragment"
            }
            "anytls" -> {
                val query = linkedMapOf<String, String>()
                tls?.text("server_name")?.let { query["sni"] = it }
                if (tls?.flag("insecure") == true) query["insecure"] = "1"
                "anytls://${encode(outbound.text("password") ?: return null)}@$address${queryText(query)}$fragment"
            }
            else -> null
        }
    }

    /** TLS or Reality as the link's query; null when the node has no encryption a link can name. */
    private fun security(tls: JsonObject?, query: MutableMap<String, String>): Unit? {
        if (tls == null || tls.flag("enabled") != true) return null
        val reality = tls["reality"] as? JsonObject
        query["security"] = if (reality?.flag("enabled") == true) "reality" else "tls"
        tls.text("server_name")?.let { query["sni"] = it }
        (tls["utls"] as? JsonObject)?.text("fingerprint")?.let { query["fp"] = it }
        if (reality?.flag("enabled") == true) {
            query["pbk"] = reality.text("public_key") ?: return null
            reality.text("short_id")?.takeIf { it.isNotEmpty() }?.let { query["sid"] = it }
        }
        alpn(tls)?.let { query["alpn"] = it }
        if (tls.flag("insecure") == true) query["allowInsecure"] = "1"
        return Unit
    }

    private fun quic(tls: JsonObject?, query: MutableMap<String, String>) {
        tls?.text("server_name")?.let { query["sni"] = it }
        if (tls?.flag("insecure") == true) query["insecure"] = "1"
        tls?.let(::alpn)?.let { query["alpn"] = it }
    }

    private fun alpn(tls: JsonObject): String? =
        (tls["alpn"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }?.joinToString(",")

    /** The transport as the link's query; null for one links do not carry. */
    private fun transport(transport: JsonObject?, query: MutableMap<String, String>): Unit? {
        if (transport == null) {
            query["type"] = "tcp"
            return Unit
        }
        when (transport.text("type")) {
            "ws" -> {
                query["type"] = "ws"
                val early = transport.int("max_early_data")
                val path = transport.text("path") ?: "/"
                query["path"] = if (early != null && early > 0) "$path?ed=$early" else path
                ((transport["headers"] as? JsonObject)?.text("Host"))?.let { query["host"] = it }
            }
            "grpc" -> {
                query["type"] = "grpc"
                transport.text("service_name")?.let { query["serviceName"] = it }
            }
            "http" -> {
                query["type"] = "http"
                (transport["host"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }?.let { query["host"] = it.joinToString(",") }
                transport.text("path")?.let { query["path"] = it }
            }
            "httpupgrade" -> {
                query["type"] = "httpupgrade"
                transport.text("host")?.let { query["host"] = it }
                transport.text("path")?.let { query["path"] = it }
            }
            else -> return null
        }
        return Unit
    }

    /** The v2rayN form: base64 of a JSON object. */
    private fun vmess(outbound: JsonObject, host: String, port: Int, name: String, tls: JsonObject?): String? {
        val transport = outbound["transport"] as? JsonObject
        val network = when (transport?.text("type")) {
            null -> "tcp"
            "ws" -> "ws"
            "grpc" -> "grpc"
            "http" -> "h2"
            "httpupgrade" -> "httpupgrade"
            else -> return null
        }
        val json = buildJsonObject {
            put("v", "2"); put("ps", name); put("add", host); put("port", port.toString())
            put("id", outbound.text("uuid") ?: return null)
            put("aid", (outbound.int("alter_id") ?: 0).toString())
            put("scy", outbound.text("security") ?: "auto")
            put("net", network); put("type", "none")
            when (network) {
                "ws" -> {
                    (transport?.get("headers") as? JsonObject)?.text("Host")?.let { put("host", it) }
                    put("path", transport?.text("path") ?: "/")
                }
                "grpc" -> put("path", transport?.text("service_name") ?: "")
                "h2" -> {
                    (transport?.get("host") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.isNotEmpty() }?.let { put("host", it.joinToString(",")) }
                    transport?.text("path")?.let { put("path", it) }
                }
                "httpupgrade" -> {
                    transport?.text("host")?.let { put("host", it) }
                    transport?.text("path")?.let { put("path", it) }
                }
            }
            if (tls?.flag("enabled") == true) {
                put("tls", "tls")
                tls.text("server_name")?.let { put("sni", it) }
                alpn(tls)?.let { put("alpn", it) }
                (tls["utls"] as? JsonObject)?.text("fingerprint")?.let { put("fp", it) }
                if (tls.flag("insecure") == true) put("allowInsecure", "1")
            } else {
                put("tls", "")
            }
        }
        return "vmess://" + Base64.getEncoder().encodeToString(json.toString().toByteArray())
    }

    private fun queryText(query: Map<String, String>) = if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { (key, value) -> "$key=${encode(value)}" }

    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.flag(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
}
