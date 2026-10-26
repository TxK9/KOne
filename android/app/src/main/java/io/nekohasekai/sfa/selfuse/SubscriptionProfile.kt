package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.NodeFilter
import cc.txwd.kone.shared.SubscriptionContent
import cc.txwd.kone.shared.UserInfo
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.managed.ManagedConfig
import io.nekohasekai.sfa.utils.HTTPClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * A subscription refused in fixed words, safe to show or log: they never repeat the link, which
 * carries the provider's token. [temporary] when trying again later may help.
 */
class SubscriptionException(message: String, val temporary: Boolean = false) : Exception(message)

/**
 * What a remote profile's link sends, made into something KOne runs. A whole sing-box
 * configuration (one with inbounds) is kept as sent, as sing-box for Android always has. What
 * most providers send instead (share links one a line, the same as one base64 block, a Clash
 * profile, or a sing-box node list) is read by the shared core as KOne for Windows reads a
 * subscription, provider notices left out, and becomes KOne's own template around those nodes:
 * the first selected, the rest to choose from, Routing, Home and the Claude block applied as to
 * any other. The remote profile's own interval refreshes it. The provider's terms (data used and
 * allowed, and when it ends), sent beside the body, are kept per profile and shown on it.
 */
object SubscriptionProfile {
    /** Whether [body] is a whole sing-box configuration, which a remote profile keeps as sent. */
    fun isWholeConfig(body: String): Boolean {
        val json = runCatching { JSONObject(body.trim().removePrefix("﻿")) }.getOrNull() ?: return false
        return (json.optJSONArray("inbounds")?.length() ?: 0) > 0
    }

    /**
     * The nodes in [body] in KOne's template, those named with one of [leaveOut]'s words left
     * out; throws [SubscriptionException] saying why when there are none.
     */
    fun build(body: String, home: HomeLink.Home, rulesDirectory: String = "rules", leaveOut: String = ""): String {
        val read = SubscriptionContent.read(body, ManagedConfig.PROXY_TYPES)
        val kept = NodeFilter.apply(read.nodes, leaveOut).nodes
        if (kept.isEmpty()) throw SubscriptionException(if (read.nodes.isNotEmpty()) "Every node is left out by the words set for it" else read.refusal ?: "No nodes found in the subscription")
        val source = JSONObject().put("outbounds", JSONArray(kept.map { JSONObject(it.toString()) }))
        return ManagedConfig.build(source, home, rulesDirectory)
    }

    /** [body] as the remote profile keeps it: as sent when whole, else built here with the phone's Home settings. */
    fun prepare(body: String, leaveOut: String = ""): String =
        if (isWholeConfig(body)) body else build(body, HomeSettings.current(), InitialRuleSets.ensure(Application.application), leaveOut)

    /** What a remote profile's link sent: the configuration KOne keeps, and the provider's terms if it gave them. */
    class Fetched(val content: String, val info: UserInfo?)

    /** The most a link may send: far beyond any provider's list, short of filling the phone. */
    private const val MOST_BYTES = 16 shl 20

    /** Providers move links (http to https, an old domain to a new one); past this many hops it is a loop. */
    private const val MOST_HOPS = 5

    private val WEB = setOf("http", "https")

    /**
     * Fetches [url] as sing-box for Android always has (its user agent, so a provider sends the
     * same), reading the provider's `subscription-userinfo` header too, and prepares the body.
     * Every failure is a [SubscriptionException].
     */
    fun fetch(url: String, leaveOut: String = ""): Fetched {
        val (body, info) = download(url)
        val content = try {
            prepare(body, leaveOut)
        } catch (refusal: SubscriptionException) {
            throw refusal
        } catch (_: Exception) {
            throw SubscriptionException("KOne could not make a profile from this subscription")
        }
        return Fetched(content, info)
    }

    /** [url]'s body and the provider's terms, following its moves but never from https down to http. */
    internal fun download(url: String, userAgent: String = HTTPClient.userAgent): Pair<String, UserInfo?> {
        var at = runCatching { URL(url.trim()) }.getOrNull()?.takeIf { it.protocol in WEB }
            ?: throw SubscriptionException("Only http and https links can be fetched")
        repeat(MOST_HOPS + 1) {
            val connection = try {
                at.openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw failure(e)
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", userAgent)
                val code = connection.responseCode
                if (code in 300..399) {
                    at = nextHop(at, connection.getHeaderField("Location"))
                        ?: throw SubscriptionException("The provider moved the link somewhere KOne will not follow")
                    return@repeat
                }
                refusal(code)?.let { throw it }
                val out = ByteArrayOutputStream()
                connection.inputStream.use { body ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = body.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        if (out.size() > MOST_BYTES) throw SubscriptionException("The subscription is larger than 16 MB")
                    }
                }
                val body = out.toString(Charsets.UTF_8.name())
                if (isWebPage(body)) throw SubscriptionException("The provider sent a web page, not a subscription. Open the link in a browser to check it")
                return body to UserInfo.parse(connection.getHeaderField("subscription-userinfo"))
            } catch (refusal: SubscriptionException) {
                throw refusal
            } catch (e: IOException) {
                throw failure(e)
            } finally {
                connection.disconnect()
            }
        }
        throw SubscriptionException("The provider moved the link too many times")
    }

    /** Where a move to [location] leads from [from], or null when KOne should not follow it. */
    internal fun nextHop(from: URL, location: String?): URL? {
        val next = location?.takeIf { it.isNotBlank() }?.let { runCatching { URL(from, it.trim()) }.getOrNull() } ?: return null
        // Down to http, the link's token would travel in the clear.
        if (next.protocol !in WEB || from.protocol == "https" && next.protocol == "http") return null
        return next
    }

    /** What an answer other than 2xx says, in the provider's terms rather than HTTP's. */
    internal fun refusal(code: Int): SubscriptionException? = when (code) {
        in 200..299 -> null
        401, 403 -> SubscriptionException("The provider refused the link ($code). Check that the subscription is still active")
        404, 410 -> SubscriptionException("The provider no longer has this link ($code)")
        408, 429 -> SubscriptionException("The provider asked KOne to wait ($code). It will try again later", temporary = true)
        in 500..599 -> SubscriptionException("The provider's server failed ($code). It will try again later", temporary = true)
        else -> SubscriptionException("The provider answered $code")
    }

    /** A network failure in fixed words; the exception's own may name the link. */
    internal fun failure(e: IOException): SubscriptionException = when (e) {
        is UnknownHostException -> SubscriptionException("The subscription's server could not be found. Check the connection", temporary = true)
        is SocketTimeoutException -> SubscriptionException("The subscription's server did not answer in time", temporary = true)
        is ConnectException -> SubscriptionException("The subscription's server could not be reached", temporary = true)
        is SSLException -> SubscriptionException("The subscription's server could not prove who it is")
        else -> SubscriptionException("The subscription could not be downloaded", temporary = true)
    }

    /** A login, error or challenge page in place of a subscription. */
    internal fun isWebPage(body: String): Boolean {
        val head = body.trimStart().removePrefix("\ufeff").take(512).lowercase()
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.startsWith("<") && "<body" in head
    }

    /**
     * Keeps [info] as [profileId]'s provider terms; null forgets them (the provider stopped
     * sending). A warning about them for [name] is said once as a notification.
     */
    fun keep(profileId: Long, info: UserInfo?, name: String? = null) {
        // Most refreshes bring the same terms; they are written only when they change.
        if (terms(profileId) != info) Settings.knineSubscriptionTerms = SubscriptionTerms.with(Settings.knineSubscriptionTerms, profileId, info)
        val warning = name?.let { info?.warning(it, System.currentTimeMillis()) } ?: return
        runCatching { SubscriptionNotifier.warn(Application.application, profileId, warning) }
    }

    /** [profileId]'s provider terms, if its link sent them. */
    fun terms(profileId: Long): UserInfo? = SubscriptionTerms.read(Settings.knineSubscriptionTerms, profileId)

    fun summary(name: String, info: UserInfo): Pair<String, Boolean>? = SubscriptionTerms.summary(name, info)

    /** The words that leave nodes out of [profileId]'s subscription ("" for none). */
    fun leaveOut(profileId: Long): String = SubscriptionTerms.words(Settings.knineSubscriptionLeaveOut, profileId)

    fun setLeaveOut(profileId: Long, words: String) {
        Settings.knineSubscriptionLeaveOut = SubscriptionTerms.withWords(Settings.knineSubscriptionLeaveOut, profileId, words)
    }
}
