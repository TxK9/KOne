package cc.txwd.kone.app

import cc.txwd.kone.node.SubscriptionContent
import cc.txwd.kone.node.UserInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import javax.net.ssl.SSLContext

/**
 * A subscription the person added: where its nodes come from, how often they are fetched again,
 * what the provider last said about use and expiry, and the nodes from the last fetch that
 * sing-box accepted. [error] is why the latest fetch failed, while the last good nodes stay.
 */
data class Subscription(
    val id: String,
    val name: String,
    val url: String,
    /** Hours between automatic updates; 0 updates only when asked. */
    val hours: Int = DEFAULT_HOURS,
    /** When the nodes were last fetched and kept, in milliseconds; 0 never. */
    val updated: Long = 0,
    /** When a fetch was last tried, successful or not. */
    val tried: Long = 0,
    val info: UserInfo? = null,
    val nodes: List<JsonObject> = emptyList(),
    val error: String? = null,
    /** Words that leave out the nodes named with them ([NodeFilter]). */
    val leaveOut: String = "",
) {
    /** The nodes KOne uses: the last good fetch, less notices and what the person left out. */
    val kept: NodeFilter.Kept get() = NodeFilter.apply(nodes, leaveOut)

    /** Whether an automatic update is due at [now]: its interval since the last good fetch, or an hour after a failed try. */
    fun due(now: Long): Boolean {
        if (hours <= 0) return false
        val stale = updated == 0L || now - updated >= hours * HOUR
        val rested = tried == 0L || now - tried >= minOf(hours * HOUR, HOUR)
        return stale && rested
    }

    /**
     * What is worth saying about the provider's terms at [now], or null: ended, ending within three
     * days, or nine tenths of its data used. Said by KOne before the nodes simply stop working.
     */
    fun warning(now: Long): String? {
        val terms = info ?: return null
        terms.expire?.let { expire ->
            val left = expire * 1000 - now
            if (left <= 0) return "$name has ended"
            if (left < 3 * DAY) {
                val days = (left / DAY).toInt()
                return "$name ends ${if (days == 0) "within a day" else "in ${days + 1} days"}"
            }
        }
        if (terms.total > 0 && terms.used * 10 >= terms.total * 9) {
            return if (terms.used >= terms.total) "$name has used all its data" else "$name has used ${terms.used * 100 / terms.total}% of its data"
        }
        return null
    }

    companion object {
        const val DEFAULT_HOURS = 12
        const val HOUR = 3_600_000L
        const val DAY = 24 * HOUR
    }
}

/**
 * The person's subscriptions, sealed with the vault (DPAPI on Windows) in subscriptions.bin in
 * the App's folder, as their links usually carry a secret token.
 */
class Subscriptions(private val file: File, private val vault: Vault) {
    @Volatile
    var all: List<Subscription> = load()
        private set

    /** A new subscription for [url]; null when one with the same link is here already or there are [MOST]. */
    @Synchronized
    fun add(url: String, name: String): Subscription? {
        if (all.size >= MOST || all.any { it.url == url }) return null
        val subscription = Subscription(UUID.randomUUID().toString(), name, url)
        save(all + subscription)
        return subscription
    }

    @Synchronized
    fun change(id: String, change: (Subscription) -> Subscription): Subscription? {
        var changed: Subscription? = null
        val next = all.map { if (it.id == id) change(it).also { done -> changed = done } else it }
        if (changed != null) save(next)
        return changed
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val next = all.filterNot { it.id == id }
        if (next.size == all.size) return false
        save(next)
        return true
    }

    private fun load(): List<Subscription> {
        if (!file.isFile) return emptyList()
        val plain = runCatching { vault.open(file.readBytes()) }.getOrNull() ?: return emptyList()
        val list = runCatching { Json.parseToJsonElement(plain.decodeToString()) as JsonArray }.getOrNull() ?: return emptyList()
        return list.mapNotNull { (it as? JsonObject)?.let(::read) }
    }

    private fun save(subscriptions: List<Subscription>) {
        all = subscriptions
        runCatching {
            file.parentFile?.mkdirs()
            file.writeBytes(vault.seal(JsonArray(subscriptions.map(::write)).toString().toByteArray()))
        }
    }

    private fun write(subscription: Subscription) = buildJsonObject {
        put("id", subscription.id)
        put("name", subscription.name)
        put("url", subscription.url)
        put("hours", subscription.hours)
        put("updated", subscription.updated)
        put("tried", subscription.tried)
        subscription.error?.let { put("error", it) }
        if (subscription.leaveOut.isNotEmpty()) put("leaveOut", subscription.leaveOut)
        subscription.info?.let { info ->
            put("info", buildJsonObject {
                put("upload", info.upload); put("download", info.download); put("total", info.total)
                info.expire?.let { put("expire", it) }
            })
        }
        put("nodes", JsonArray(subscription.nodes))
    }

    private fun read(json: JsonObject): Subscription? {
        fun text(key: String, from: JsonObject = json) = (from[key] as? JsonPrimitive)?.contentOrNull
        fun number(key: String, from: JsonObject = json) = (from[key] as? JsonPrimitive)?.longOrNull
        val info = (json["info"] as? JsonObject)?.let { UserInfo(number("upload", it) ?: 0, number("download", it) ?: 0, number("total", it) ?: 0, number("expire", it)) }
        return Subscription(
            id = text("id") ?: return null,
            name = text("name") ?: "Subscription",
            url = text("url") ?: return null,
            hours = number("hours")?.toInt() ?: Subscription.DEFAULT_HOURS,
            updated = number("updated") ?: 0,
            tried = number("tried") ?: 0,
            info = info,
            nodes = (json["nodes"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty(),
            error = text("error"),
            leaveOut = text("leaveOut").orEmpty(),
        )
    }

    companion object {
        const val MOST = 20
    }
}

/** Why a fetch failed, in words fit to show. */
class FetchException(message: String) : IOException(message)

/** A subscription's body as fetched, with what its provider says in the headers. */
class Fetched(val body: String, val info: UserInfo?, val name: String, val hours: Int?)

/**
 * Fetches subscriptions over HTTPS only (a redirect to plain HTTP is refused), 8 MB at most.
 * The user agent asks panels for a mihomo profile, which carries every kind of node KOne reads;
 * share links and sing-box configurations are read too.
 */
object SubscriptionFetch {
    private const val LIMIT = 8L * 1024 * 1024

    private val agent = "KOne/${Places.version} clash.meta mihomo"

    /**
     * The body of [url], directly or, when that fails and [viaPort] is given, through KOne's own
     * local proxy. [tls] stands in for the system's trust in tests.
     */
    fun fetch(url: String, viaPort: Int? = null, tls: SSLContext? = null): Fetched {
        if (!SubscriptionContent.acceptable(url)) throw FetchException("Use an https:// subscription link")
        fun client() = HttpClient.newBuilder().apply { if (tls != null) sslContext(tls) }
        return try {
            fetch(url, client())
        } catch (direct: IOException) {
            if (viaPort == null || direct is FetchException) throw direct
            fetch(url, client().proxy(ProxySelector.of(InetSocketAddress("127.0.0.1", viaPort))))
        }
    }

    /** Why [failure] stopped a fetch, in words fit to show; never the link, which may carry a token. */
    fun reason(failure: Throwable): String = when (failure) {
        is FetchException -> failure.message ?: "The subscription could not be fetched"
        is java.net.http.HttpTimeoutException -> "The provider did not answer in time"
        is javax.net.ssl.SSLException -> "The provider's certificate is not trusted"
        is java.net.ConnectException, is java.net.UnknownHostException, is java.nio.channels.UnresolvedAddressException -> "The provider cannot be reached"
        else -> "The subscription could not be fetched"
    }

    private fun fetch(url: String, builder: HttpClient.Builder): Fetched {
        val client = builder.followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(10)).build()
        val request = HttpRequest.newBuilder(URI(url.trim())).timeout(Duration.ofSeconds(30)).header("User-Agent", agent).GET().build()
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (interrupted: InterruptedException) {
            Thread.currentThread().interrupt()
            throw FetchException("Interrupted")
        }
        response.body().use { stream ->
            if (response.statusCode() !in 200..299) throw FetchException("The provider answered ${response.statusCode()}")
            if (response.uri().scheme != "https") throw FetchException("The subscription moved off https")
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > LIMIT) throw FetchException("The subscription is too large")
            }
            val headers = response.headers()
            return Fetched(
                body = out.toByteArray().decodeToString(),
                info = UserInfo.parse(headers.firstValue("subscription-userinfo").orElse(null)),
                name = SubscriptionContent.name(url, headers.firstValue("content-disposition").orElse(null)),
                hours = headers.firstValue("profile-update-interval").orElse(null)?.trim()?.toIntOrNull()?.takeIf { it in 1..720 },
            )
        }
    }
}
