package cc.txwd.kone.node

import cc.txwd.kone.shared.ShareLinks
import cc.txwd.kone.core.KoneConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URI
import java.net.URLDecoder

/** What a provider says about a subscription in its `subscription-userinfo` header: bytes used and allowed, and when it ends. */
data class UserInfo(val upload: Long, val download: Long, val total: Long, val expire: Long?) {
    val used get() = upload + download

    companion object {
        /** `upload=455727941; download=6174315083; total=1073741824000; expire=1671815872`; null when it says nothing usable. */
        fun parse(header: String?): UserInfo? {
            if (header.isNullOrBlank()) return null
            val values = header.split(';', ',').mapNotNull { part ->
                val key = part.substringBefore('=', "").trim().lowercase()
                val number = part.substringAfter('=', "").trim().toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }?.toLong()
                if (key.isEmpty() || number == null) null else key to number
            }.toMap()
            if (values.keys.none { it in setOf("upload", "download", "total", "expire") }) return null
            return UserInfo(values["upload"] ?: 0, values["download"] ?: 0, values["total"] ?: 0, values["expire"]?.takeIf { it > 0 })
        }
    }
}

/**
 * A subscription's body as providers send it: share links one per line, the same as one base64
 * block, or a sing-box configuration (what panels send a sing-box user agent). Each node comes
 * out as a sing-box outbound tagged with its name, unique within the subscription; a node that
 * leans on another (`detour`) keeps it, and one whose detour is missing is left out. Nothing
 * that names a file is kept, as the service would refuse it.
 */
object SubscriptionContent {
    /** The nodes read, how many entries were refused, and the first refusal's reason (never the link). */
    class Read(val nodes: List<JsonObject>, val refused: Int, val refusal: String?)

    /** More than this many nodes are left out: no provider needs them and every one costs a check. */
    const val MOST = 500

    fun read(body: String): Read {
        val text = body.trim().removePrefix("﻿")
        if (text.startsWith("{")) return singBox(text)
        if (ClashProxies.looksLikeClash(text)) return ClashProxies.read(text)
        val links = ShareLinks.findAll(text)
        if (links.isEmpty()) return Read(emptyList(), 0, "No nodes found in the subscription")
        var refused = 0
        var refusal: String? = null
        val named = links.mapNotNull { link ->
            runCatching { ShareLinks.parse(link) }.onFailure { refused++; refusal = refusal ?: it.message }.getOrNull()
        }.map { node -> (Json.parseToJsonElement(node.outbound.toString()) as JsonObject) to node.name }
        return Read(tagged(named), refused, refusal)
    }

    private fun singBox(text: String): Read {
        val json = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull()
            ?: return Read(emptyList(), 0, "The subscription is not valid JSON")
        val all = (json["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val proxies = all.filter { it.text("type") in KoneConfig.PROXY_TYPES }
        val kept = proxies.filterNot(::namesAFile)
        var refused = proxies.size - kept.size
        // A node whose chain leaves the kept nodes cannot be carried.
        val byTag = kept.associateBy { it.text("tag").orEmpty() }
        fun whole(outbound: JsonObject, depth: Int = 0): Boolean {
            val next = outbound.text("detour")?.takeIf { it.isNotBlank() } ?: return true
            if (depth > 8) return false
            return whole(byTag[next] ?: return false, depth + 1)
        }
        val carried = kept.filter(::whole)
        refused += kept.size - carried.size
        val named = carried.map { JsonObject(it - "tag") to it.text("tag") }
        // Renamed tags are followed through each chain.
        val renamed = tagged(named)
        // A detour names the first node with that tag, as it was written.
        val names = HashMap<String, String>()
        carried.zip(renamed).forEach { (before, after) -> names.putIfAbsent(before.text("tag").orEmpty(), after.text("tag").orEmpty()) }
        val nodes = renamed.map { node ->
            val detour = node.text("detour") ?: return@map node
            JsonObject(node + ("detour" to JsonPrimitive(names[detour] ?: detour)))
        }
        return Read(nodes, refused, if (refused > 0) "Some nodes in the subscription cannot be used" else null)
    }

    /** Each outbound tagged with its name, or Node and a number, unique within the list; [MOST] at most. */
    internal fun tagged(named: List<Pair<JsonObject, String?>>): List<JsonObject> {
        val used = HashSet<String>()
        return named.take(MOST).mapIndexed { index, (outbound, name) ->
            val base = name?.trim()?.takeIf { it.isNotEmpty() }?.take(64) ?: "Node ${index + 1}"
            var tag = base
            var number = 2
            while (!used.add(tag)) tag = "$base ${number++}"
            JsonObject(outbound + ("tag" to JsonPrimitive(tag)))
        }
    }

    /**
     * A name for a subscription: the file name its provider gives (Content-Disposition), else the
     * name in the link's #fragment, else its host.
     */
    fun name(url: String, disposition: String?): String {
        val fromHeader = disposition?.let { header ->
            Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(header)?.groupValues?.get(1)?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }
                ?: Regex("""filename\s*=\s*"?([^";]+)"?""").find(header)?.groupValues?.get(1)
        }?.trim()?.removeSuffix(".yaml")?.removeSuffix(".yml")?.removeSuffix(".json")?.removeSuffix(".txt")?.takeIf { it.isNotBlank() }
        val fragment = url.substringAfter('#', "").takeIf { it.isNotBlank() }?.let { runCatching { URLDecoder.decode(it, Charsets.UTF_8) }.getOrNull() }
        val host = runCatching { URI(url).host }.getOrNull()
        return (fromHeader ?: fragment ?: host ?: "Subscription").take(32)
    }

    /** Whether [url] is one KOne fetches: https, with a host, no user name or password in it. */
    fun acceptable(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }

    private fun namesAFile(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) -> key.lowercase().endsWith("_path") || namesAFile(value) }
        is JsonArray -> element.any(::namesAFile)
        else -> false
    }

    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
