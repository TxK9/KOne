package io.nekohasekai.sfa.compose.screen.connections

import androidx.compose.runtime.Immutable
import io.nekohasekai.sfa.compose.model.Connection

/** Who opened a group of connections: an installed app, a named process, the system, or nobody known. */
enum class AppKind { Package, Process, System, Unknown }

/** One host an app is talking to, over one route, with its connections folded together. */
@Immutable
data class HostTraffic(
    val key: String,
    val host: String,
    val outbound: String,
    val outboundType: String,
    val rule: String,
    val count: Int,
    val download: Long,
    val upload: Long,
    val total: Long,
    val ids: List<String>,
) {
    val speed: Long get() = download + upload
}

/** One app's live connections. KOne's own carry no hosts, so its control traffic never lists its servers. */
@Immutable
data class AppTraffic(
    val key: String,
    val kind: AppKind,
    val name: String,
    val count: Int,
    val download: Long,
    val upload: Long,
    val total: Long,
    val hosts: List<HostTraffic>,
    val ids: List<String>,
) {
    val speed: Long get() = download + upload
}

internal object AppTrafficGrouping {
    /** Speeds within a factor of four share a tier, so a second's jitter does not reshuffle the list. */
    fun speedTier(bytesPerSecond: Long): Int {
        if (bytesPerSecond < 1024) return 0
        var tier = 1
        var rest = bytesPerSecond / 1024
        while (rest >= 4) {
            rest /= 4
            tier++
        }
        return tier
    }

    private val appOrder = compareByDescending<AppTraffic> { speedTier(it.speed) }.thenByDescending { it.total }.thenBy { it.key }
    private val hostOrder = compareByDescending<HostTraffic> { speedTier(it.speed) }.thenByDescending { it.total }.thenBy { it.key }

    /** Active connections, grouped by app and then by host and route, busiest first at both levels. */
    fun group(connections: List<Connection>, ownPackage: String?): List<AppTraffic> {
        val byApp = LinkedHashMap<String, MutableList<Connection>>()
        for (connection in connections) {
            if (!connection.isActive) continue
            byApp.getOrPut(appKey(connection)) { mutableListOf() }.add(connection)
        }
        return byApp.map { (key, members) ->
            val (kind, name) = identity(key)
            val own = kind == AppKind.Package && name == ownPackage
            AppTraffic(
                key = key,
                kind = kind,
                name = name,
                count = members.size,
                download = members.sumOf { it.download },
                upload = members.sumOf { it.upload },
                total = members.sumOf { it.downloadTotal + it.uploadTotal },
                hosts = if (own) emptyList() else hosts(members),
                ids = members.map { it.id },
            )
        }.sortedWith(appOrder)
    }

    private fun hosts(members: List<Connection>): List<HostTraffic> {
        val byHost = LinkedHashMap<String, MutableList<Connection>>()
        for (connection in members) {
            val key = "${hostOf(connection)}\u0000${connection.outbound}\u0000${connection.rule}"
            byHost.getOrPut(key) { mutableListOf() }.add(connection)
        }
        return byHost.map { (key, same) ->
            val first = same.first()
            HostTraffic(
                key = key,
                host = hostOf(first),
                outbound = first.outbound,
                outboundType = first.outboundType,
                rule = first.rule,
                count = same.size,
                download = same.sumOf { it.download },
                upload = same.sumOf { it.upload },
                total = same.sumOf { it.downloadTotal + it.uploadTotal },
                ids = same.map { it.id },
            )
        }.sortedWith(hostOrder)
    }

    fun appKey(connection: Connection): String {
        val process = connection.processInfo ?: return UNKNOWN
        // Android gives apps uids from 10000 in each user; below that the system is speaking.
        if (process.userId >= 0 && process.userId % 100000 < 10000) return SYSTEM
        process.packageNames.firstOrNull { it.isNotBlank() }?.let { return "package:$it" }
        val name = process.processPath.substringAfterLast('/')
        return if (name.isNotBlank()) "process:$name" else UNKNOWN
    }

    private fun identity(key: String): Pair<AppKind, String> = when {
        key.startsWith("package:") -> AppKind.Package to key.removePrefix("package:")
        key.startsWith("process:") -> AppKind.Process to key.removePrefix("process:")
        key == SYSTEM -> AppKind.System to ""
        else -> AppKind.Unknown to ""
    }

    /** The name the app asked for, or the address without its port when it asked for none. */
    fun hostOf(connection: Connection): String {
        if (connection.domain.isNotBlank()) return connection.domain
        val destination = connection.destination
        return when {
            destination.startsWith("[") -> destination.substringAfter('[').substringBefore(']')
            destination.count { it == ':' } == 1 -> destination.substringBefore(':')
            else -> destination
        }
    }

    private const val SYSTEM = "system"
    private const val UNKNOWN = "unknown"
}

/** Where a connection went, without the outbound's own tag when that could be a server address. */
internal enum class RouteKind { Direct, Blocked, Home, Node }

/** What sent a connection there. Only rule-set names and a few fixed values are kept; literal hosts and addresses never are. */
internal enum class RuleKind { Default, RuleSet, Private, Domain, Address, Port, App, Protocol, Network, Mode, WiFi, Other }

@Immutable
internal data class RouteRule(val kind: RuleKind, val detail: String? = null)

internal object RouteLabels {
    fun kind(outbound: String, outboundType: String, homeTag: String): RouteKind = when {
        outboundType == "direct" -> RouteKind.Direct
        outboundType == "block" || outboundType == "reject" -> RouteKind.Blocked
        outbound == homeTag || outboundType == "tailscale" -> RouteKind.Home
        else -> RouteKind.Node
    }

    /** True for tags that read like a host, an address or an address with a port, which must not be shown. */
    fun looksLikeAddress(tag: String): Boolean {
        val text = tag.trim()
        if (text.isEmpty()) return false
        if (IPV4.containsMatchIn(text)) return true
        if (text.count { it == ':' } >= 2 && IPV6.matches(text.removePrefix("[").substringBefore(']'))) return true
        return HOST.matches(text)
    }

    fun rule(rule: String): RouteRule {
        if (rule.isBlank()) return RouteRule(RuleKind.Default)
        val items = ITEM.findAll(rule).map { it.groupValues[1] to it.groupValues[2] }.toList()
        items.firstOrNull { it.first == "rule_set" }?.let { item ->
            val names = values(item.second).filterNot(::looksLikeAddress)
            return if (names.isEmpty()) RouteRule(RuleKind.Other) else RouteRule(RuleKind.RuleSet, names.joinToString(", "))
        }
        for ((key, value) in items) {
            val kind = KINDS[key] ?: continue
            val detail = when (kind) {
                RuleKind.Protocol, RuleKind.Network, RuleKind.Mode -> values(value).filter { SAFE_VALUE.matches(it) }.joinToString(", ").ifEmpty { null }
                else -> null
            }
            return RouteRule(kind, detail)
        }
        return RouteRule(RuleKind.Other)
    }

    private fun values(value: String): List<String> = value.removePrefix("[").removeSuffix("]").split(' ').filter { it.isNotBlank() }

    private val ITEM = Regex("""([a-z_]+)(?:=(\[[^\]]*]|[^\s()]+))?""")
    private val SAFE_VALUE = Regex("""[A-Za-z][A-Za-z0-9_-]{0,23}""")
    private val IPV4 = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b""")
    private val IPV6 = Regex("""[0-9A-Fa-f:.]+(%\w+)?(:\d+)?""")
    private val HOST = Regex("""(?i)[a-z0-9-]+(\.[a-z0-9-]+)+\.?(:\d+)?""")

    private val KINDS = buildMap {
        listOf("ip_is_private", "source_ip_is_private").forEach { put(it, RuleKind.Private) }
        listOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "geosite").forEach { put(it, RuleKind.Domain) }
        listOf("ip_cidr", "source_ip_cidr", "geoip", "source_geoip", "ip_accept_any").forEach { put(it, RuleKind.Address) }
        listOf("port", "port_range", "source_port", "source_port_range").forEach { put(it, RuleKind.Port) }
        listOf("package_name", "package_name_regex", "process_name", "process_path", "process_path_regex", "user", "user_id").forEach { put(it, RuleKind.App) }
        put("protocol", RuleKind.Protocol)
        put("network", RuleKind.Network)
        put("clash_mode", RuleKind.Mode)
        listOf("wifi_ssid", "wifi_bssid").forEach { put(it, RuleKind.WiFi) }
    }
}
