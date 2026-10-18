package cc.txwd.kone.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The running core's clash_api on loopback (see [KoneConfig]): whether it answers, the traffic
 * each second, a node's delay, and which node the selector uses.
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
