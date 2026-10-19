package cc.txwd.kone.app

import cc.txwd.kone.core.UserRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * How the person set KOne up, as a file they keep: the mode and way, the apps, sites and rules,
 * DNS and IPv6, failover, their subscriptions and imported nodes. Never the key or the device's
 * binding: those stay sealed on this computer. Read back, every value goes through the same
 * checks as when it was first typed in.
 */
data class Backup(
    val mode: String? = null,
    val way: String? = null,
    val bypass: List<String> = emptyList(),
    val direct: List<String> = emptyList(),
    val proxied: List<String> = emptyList(),
    val rules: List<UserRule> = emptyList(),
    val blockAds: Boolean? = null,
    val remoteDns: String? = null,
    val directDns: String? = null,
    val ipv6: Boolean? = null,
    val fakeIp: Boolean? = null,
    val failover: Boolean? = null,
    val hosts: List<cc.txwd.kone.shared.Host> = emptyList(),
    val unmatchedDirect: Boolean? = null,
    val nameServers: List<cc.txwd.kone.shared.NameServer> = emptyList(),
    val region: String? = null,
    /** Each subscription's name, link, update interval and the words that leave its nodes out. */
    val subscriptions: List<Entry> = emptyList(),
    val imported: List<JsonObject> = emptyList(),
) {
    /** A subscription as a backup keeps it: never its nodes, which are fetched again. */
    data class Entry(val name: String, val url: String, val hours: Int, val leaveOut: String = "")

    fun write(): String = buildJsonObject {
        put("kone", "backup")
        put("version", 1)
        mode?.let { put("mode", it) }
        way?.let { put("way", it) }
        putJsonArray("bypass") { bypass.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("direct") { direct.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("proxied") { proxied.forEach { add(JsonPrimitive(it)) } }
        putJsonArray("rules") { rules.forEach { add(it.toJson()) } }
        putJsonArray("hosts") { hosts.forEach { add(it.toJson()) } }
        putJsonArray("nameServers") { nameServers.forEach { add(it.toJson()) } }
        region?.let { put("region", it) }
        unmatchedDirect?.let { put("unmatchedDirect", it) }
        blockAds?.let { put("blockAds", it) }
        remoteDns?.let { put("remoteDns", it) }
        directDns?.let { put("directDns", it) }
        ipv6?.let { put("ipv6", it) }
        fakeIp?.let { put("fakeIp", it) }
        failover?.let { put("failover", it) }
        putJsonArray("subscriptions") {
            subscriptions.forEach { entry ->
                add(buildJsonObject {
                    put("name", entry.name); put("url", entry.url); put("hours", entry.hours)
                    if (entry.leaveOut.isNotEmpty()) put("leaveOut", entry.leaveOut)
                })
            }
        }
        put("imported", JsonArray(imported))
    }.toString()

    companion object {
        /** A backup KOne wrote, or null for anything else. */
        fun read(text: String): Backup? {
            val json = runCatching { Json.parseToJsonElement(text.trim().removePrefix("﻿")) as JsonObject }.getOrNull() ?: return null
            if (json.text("kone") != "backup" || (json["version"] as? JsonPrimitive)?.intOrNull != 1) return null
            fun strings(key: String) = (json[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }.orEmpty()
            fun flag(key: String) = (json[key] as? JsonPrimitive)?.booleanOrNull
            return Backup(
                mode = json.text("mode"),
                way = json.text("way"),
                bypass = strings("bypass"),
                direct = strings("direct"),
                proxied = strings("proxied"),
                rules = (json["rules"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(UserRule::fromJson) }.orEmpty(),
                blockAds = flag("blockAds"),
                remoteDns = json.text("remoteDns"),
                directDns = json.text("directDns"),
                ipv6 = flag("ipv6"),
                fakeIp = flag("fakeIp"),
                failover = flag("failover"),
                unmatchedDirect = flag("unmatchedDirect"),
                hosts = (json["hosts"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(cc.txwd.kone.shared.Host::fromJson) }.orEmpty(),
                region = json.text("region")?.let(cc.txwd.kone.shared.Region::clean),
                nameServers = (json["nameServers"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(cc.txwd.kone.shared.NameServer::fromJson) }.orEmpty(),
                subscriptions = (json["subscriptions"] as? JsonArray)?.mapNotNull { item ->
                    val entry = item as? JsonObject ?: return@mapNotNull null
                    val url = entry.text("url") ?: return@mapNotNull null
                    Entry(entry.text("name") ?: "Subscription", url, (entry["hours"] as? JsonPrimitive)?.intOrNull ?: Subscription.DEFAULT_HOURS, entry.text("leaveOut").orEmpty())
                }.orEmpty(),
                imported = (json["imported"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty(),
            )
        }

        private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    }
}
