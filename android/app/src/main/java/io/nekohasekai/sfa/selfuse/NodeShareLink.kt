package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.ShareLinkException
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Base64

/** Portable node URIs, never the app's routing, Tailscale or management configuration. */
object NodeShareLink {
    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    fun nodes(config: String): List<JSONObject> {
        val values = JSONObject(config).optJSONArray("outbounds") ?: return emptyList()
        return (0 until values.length()).mapNotNull { values.optJSONObject(it) }
            .filter { it.optString("type") in setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "anytls") }
    }
    fun encode(node: JSONObject, name: String): String {
        fun fail(): Nothing = throw ShareLinkException("This node has parameters unsupported by QR export.")
        val type = node.getString("type")
        val server = node.getString("server")
        val port = node.getInt("server_port")
        require(port in 1..65535)
        val host = if (':' in server) "[$server]" else server
        if (node.has("plugin") || node.has("tls_fragment") || node.has("server_ports") || node.has("hop_interval")) fail()
        val tls = node.optJSONObject("tls")
        if (tls != null && listOf("certificate", "certificate_path", "ech", "fragment", "record_fragment").any { tls.has(it) }) fail()
        val transport = node.optJSONObject("transport")
        val net = transport?.optString("type") ?: "tcp"
        if (net !in setOf("tcp", "ws", "grpc", "httpupgrade")) fail()
        val query = linkedMapOf<String, String>()
        fun put(key: String, value: String?) { if (!value.isNullOrBlank()) query[key] = value }
        if (tls?.optBoolean("enabled") == true) {
            put("security", if (tls.optJSONObject("reality")?.optBoolean("enabled") == true) "reality" else "tls")
            put("sni", tls.optString("server_name"))
            if (tls.optBoolean("insecure")) put("allowInsecure", "1")
            val alpn = tls.optJSONArray("alpn")
            if (alpn != null) put("alpn", (0 until alpn.length()).joinToString(",") { alpn.getString(it) })
            put("fp", tls.optJSONObject("utls")?.optString("fingerprint"))
            tls.optJSONObject("reality")?.let { put("pbk", it.optString("public_key")); put("sid", it.optString("short_id")) }
        } else put("security", "none")
        if (transport != null) {
            put("type", net)
            put("path", transport.optString("path"))
            val headers = transport.optJSONObject("headers")
            if (headers != null && headers.keys().asSequence().any { !it.equals("host", ignoreCase = true) }) fail()
            put("host", transport.optString("host").ifBlank { headers?.optString("Host").orEmpty() })
            put("serviceName", transport.optString("service_name"))
            if (transport.optInt("max_early_data") > 0) { put("ed", transport.optInt("max_early_data").toString()); put("eh", transport.optString("early_data_header_name")) }
        }
        val credentials = when (type) {
            "vless", "vmess" -> node.getString("uuid")
            "trojan", "hysteria2", "anytls" -> node.getString("password")
            "tuic" -> node.getString("uuid") + ":" + node.getString("password")
            "shadowsocks" -> Base64.getUrlEncoder().withoutPadding().encodeToString((node.getString("method") + ":" + node.getString("password")).toByteArray())
            else -> fail()
        }
        when (type) {
            "vless" -> { put("encryption", "none"); put("flow", node.optString("flow")) }
            "hysteria2" -> {
                query.remove("security"); query.remove("allowInsecure")
                if (tls?.optBoolean("insecure") == true) put("insecure", "1")
                node.optJSONObject("obfs")?.let { put("obfs", it.optString("type")); put("obfs-password", it.optString("password")) }
            }
            "tuic" -> { put("congestion_control", node.optString("congestion_control")); put("udp_relay_mode", node.optString("udp_relay_mode")) }
            "shadowsocks" -> { if (transport != null || tls?.optBoolean("enabled") == true) fail(); query.clear() }
            "vmess" -> {
                if (tls?.optJSONObject("reality")?.optBoolean("enabled") == true || node.optBoolean("global_padding") || node.optBoolean("authenticated_length") || (transport?.optInt("max_early_data",0) ?: 0) > 0) fail()
                val value = JSONObject().put("v", "2").put("ps", name).put("add", server).put("port", port.toString())
                    .put("id", credentials).put("aid", node.optInt("alter_id", 0).toString()).put("scy", node.optString("security", "auto"))
                    .put("net", net).put("type", "none").put("host", query["host"].orEmpty())
                    .put("path", if (net == "grpc") query["serviceName"].orEmpty() else query["path"].orEmpty())
                    .put("tls", if (tls?.optBoolean("enabled") == true) "tls" else "")
                    .put("sni", query["sni"].orEmpty()).put("alpn", query["alpn"].orEmpty()).put("fp", query["fp"].orEmpty())
                    .put("allowInsecure", if (tls?.optBoolean("insecure") == true) "1" else "0")
                return "vmess://" + Base64.getEncoder().encodeToString(value.toString().toByteArray())
            }
        }
        val scheme = if (type == "shadowsocks") "ss" else type
        return "$scheme://${enc(credentials)}@$host:$port" +
            (if (query.isEmpty()) "" else "?" + query.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }) + "#" + enc(name)
    }
}
