package cc.txwd.kone.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** A connection the core carries now: the program behind it, where to, through which node, how much so far. */
data class CoreConnection(val id: String, val program: String?, val target: String, val via: String, val down: Long, val up: Long)

/** All the core carries now, and how much it has carried since it started. */
data class Carried(val down: Long, val up: Long, val connections: List<CoreConnection>)

/**
 * The running core's clash_api on loopback (see [KoneConfig]): whether it answers, the traffic
 * each second, a node's delay, which node the selector uses, the mode, and the connections.
 */
class ClashApi(private val api: CoreApi) {
    private val client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(2)).build()

    private fun request(path: String, timeout: Duration = Duration.ofSeconds(3)) =
        HttpRequest.newBuilder(URI("http://127.0.0.1:${api.port}$path")).header("Authorization", "Bearer ${api.secret}").timeout(timeout)

    fun answers(): Boolean = runCatching {
        client.send(request("/version").GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    }.getOrDefault(false)

    /** The delay through [tag] in milliseconds, or null when it did not answer in time. */
    fun delay(tag: String, timeoutMillis: Int = 5_000, url: String = "https://www.gstatic.com/generate_204"): Int? = runCatching {
        val path = "/proxies/${encode(tag)}/delay?timeout=$timeoutMillis&url=${encode(url)}"
        val response = client.send(request(path, Duration.ofMillis(timeoutMillis + 2_000L)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        ((Json.parseToJsonElement(response.body()) as JsonObject)["delay"] as? JsonPrimitive)?.longOrNull?.toInt()?.takeIf { it > 0 }
    }.getOrNull()

    fun select(tag: String): Boolean = runCatching {
        val body = Json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("name" to JsonPrimitive(tag))))
        client.send(request("/proxies/${KoneConfig.PROXY_TAG}").PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding())
            .statusCode() in 200..299
    }.getOrDefault(false)

    /** The node a group such as Auto is on now. */
    fun now(group: String): String? = runCatching {
        val response = client.send(request("/proxies/${encode(group)}").GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        ((Json.parseToJsonElement(response.body()) as JsonObject)["now"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /** Switches the running core to [mode] (Rule, Global or Direct). */
    fun mode(mode: String): Boolean = runCatching {
        val body = JsonObject(mapOf("mode" to JsonPrimitive(mode))).toString()
        client.send(request("/configs").method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding())
            .statusCode() in 200..299
    }.getOrDefault(false)

    /** What the core carries now, newest first; null when it does not answer. */
    fun connections(): Carried? = runCatching {
        val response = client.send(request("/connections").GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        val json = Json.parseToJsonElement(response.body()) as JsonObject
        fun JsonObject.number(key: String) = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
        fun JsonObject.word(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val list = (json["connections"] as? JsonArray).orEmpty().mapNotNull { item ->
            val connection = item as? JsonObject ?: return@mapNotNull null
            val meta = connection["metadata"] as? JsonObject ?: JsonObject(emptyMap())
            val host = meta.word("host") ?: meta.word("destinationIP") ?: return@mapNotNull null
            val port = meta.word("destinationPort")
            val chain = (connection["chains"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            CoreConnection(
                id = connection.word("id") ?: return@mapNotNull null,
                program = meta.word("processPath")?.substringAfterLast('\\')?.substringAfterLast('/'),
                target = if (port != null) "$host:$port" else host,
                via = chain,
                down = connection.number("download"),
                up = connection.number("upload"),
            ) to (connection.word("start") ?: "")
        }.sortedByDescending { it.second }.map { it.first }
        Carried(json.number("downloadTotal"), json.number("uploadTotal"), list)
    }.getOrNull()

    /**
     * Asks the core's own DNS, with its rules, for [name] of [type] (A or AAAA): the response code
     * and answers as [DnsWire.read] gives them. Null when the core does not answer or the lookup failed.
     */
    fun dnsQuery(name: String, type: String): DnsReply? = runCatching {
        val response = client.send(request("/dns/query?name=${encode(name)}&type=$type", Duration.ofSeconds(12)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) return null
        val json = Json.parseToJsonElement(response.body()) as JsonObject
        val code = (json["Status"] as? JsonPrimitive)?.longOrNull?.toInt() ?: return null
        val answers = (json["Answer"] as? JsonArray).orEmpty().mapNotNull { item ->
            val record = item as? JsonObject ?: return@mapNotNull null
            val data = (record["data"] as? JsonPrimitive)?.contentOrNull?.trim()?.trimEnd('.') ?: return@mapNotNull null
            when ((record["type"] as? JsonPrimitive)?.longOrNull?.toInt()) {
                DnsWire.A -> "A $data"
                DnsWire.AAAA -> "AAAA $data"
                DnsWire.CNAME -> "CNAME $data"
                else -> null
            }
        }
        // Only the type asked for (a fixed answer in the rules may carry every type), and aliases.
        DnsReply(code, answers.filter { it.startsWith("$type ") || it.startsWith("CNAME ") })
    }.getOrNull()

    /** Ends one connection, or every one when [id] is null; the program behind it opens a new one as it needs. */
    fun close(id: String?): Boolean = runCatching {
        val path = if (id == null) "/connections" else "/connections/${encode(id)}"
        client.send(request(path).DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode() in 200..299
    }.getOrDefault(false)

    /**
     * Calls [onRate] with the bytes per second down and up, once a second, until the stream ends
     * (the core stopped) or [keepGoing] says no. Blocks.
     */
    fun traffic(keepGoing: () -> Boolean, onRate: (down: Long, up: Long) -> Unit) {
        runCatching {
            val response = client.send(request("/traffic", Duration.ofDays(1)).GET().build(), HttpResponse.BodyHandlers.ofLines())
            if (response.statusCode() != 200) return
            response.body().use { lines ->
                val iterator = lines.iterator()
                while (keepGoing() && iterator.hasNext()) {
                    val json = runCatching { Json.parseToJsonElement(iterator.next()) as JsonObject }.getOrNull() ?: continue
                    onRate((json["down"] as? JsonPrimitive)?.longOrNull ?: 0, (json["up"] as? JsonPrimitive)?.longOrNull ?: 0)
                }
            }
        }
    }

    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")
}
