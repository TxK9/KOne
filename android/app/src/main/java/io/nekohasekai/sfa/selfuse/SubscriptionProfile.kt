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
import java.net.HttpURLConnection
import java.net.URL

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

    /** The nodes in [body] in KOne's template; throws [IllegalArgumentException] saying why when there are none. */
    fun build(body: String, home: HomeLink.Home, rulesDirectory: String = "rules"): String {
        val read = SubscriptionContent.read(body, ManagedConfig.PROXY_TYPES)
        val kept = NodeFilter.apply(read.nodes, "").nodes
        require(kept.isNotEmpty()) { read.refusal ?: "No nodes found in the subscription" }
        val source = JSONObject().put("outbounds", JSONArray(kept.map { JSONObject(it.toString()) }))
        return ManagedConfig.build(source, home, rulesDirectory)
    }

    /** [body] as the remote profile keeps it: as sent when whole, else built here with the phone's Home settings. */
    fun prepare(body: String): String =
        if (isWholeConfig(body)) body else build(body, HomeSettings.current(), InitialRuleSets.ensure(Application.application))

    /** What a remote profile's link sent: the configuration KOne keeps, and the provider's terms if it gave them. */
    class Fetched(val content: String, val info: UserInfo?)

    /** The most a link may send: far beyond any provider's list, short of filling the phone. */
    private const val MOST_BYTES = 16 shl 20

    /**
     * Fetches [url] as sing-box for Android always has (its user agent, so a provider sends the
     * same), reading the provider's `subscription-userinfo` header too, and prepares the body.
     */
    fun fetch(url: String): Fetched {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", HTTPClient.userAgent)
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("The server answered $code")
            val out = ByteArrayOutputStream()
            connection.inputStream.use { body ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = body.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MOST_BYTES) throw IOException("The profile is too large")
                }
            }
            val info = UserInfo.parse(connection.getHeaderField("subscription-userinfo"))
            return Fetched(prepare(out.toString(Charsets.UTF_8.name())), info)
        } finally {
            connection.disconnect()
        }
    }

    /** Keeps [info] as [profileId]'s provider terms; null forgets them (the provider stopped sending). */
    fun keep(profileId: Long, info: UserInfo?) {
        Settings.knineSubscriptionTerms = SubscriptionTerms.with(Settings.knineSubscriptionTerms, profileId, info)
    }

    /** [profileId]'s provider terms, if its link sent them. */
    fun terms(profileId: Long): UserInfo? = SubscriptionTerms.read(Settings.knineSubscriptionTerms, profileId)

    fun summary(name: String, info: UserInfo): Pair<String, Boolean>? = SubscriptionTerms.summary(name, info)
}
