package cc.txwd.kone.app

import cc.txwd.kone.core.Dns
import cc.txwd.kone.core.Host
import cc.txwd.kone.core.Hosts
import cc.txwd.kone.core.NameServer
import cc.txwd.kone.core.NameServers
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.UserRule
import cc.txwd.kone.core.UserRules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import cc.txwd.kone.ui.Appearance
import cc.txwd.kone.ui.Backdrop
import java.io.File
import java.util.Properties

/** The person's choices that are KOne's own to keep, in settings.properties in the App's folder. */
class Preferences(folder: File) {
    private val file = File(folder, "settings.properties")
    private val values = Properties().apply { runCatching { file.inputStream().use(::load) } }

    var appearance: Appearance
        get() = Appearance.entries.firstOrNull { it.name == values.getProperty("appearance") } ?: Appearance.System
        set(value) {
            values.setProperty("appearance", value.name)
            save()
        }

    /** What fills the dashboard behind the nodes (Settings → Background). */
    var backdrop: Backdrop
        get() = Backdrop.entries.firstOrNull { it.name == values.getProperty("backdrop") } ?: Backdrop.Ink
        set(value) {
            values.setProperty("backdrop", value.name)
            save()
        }

    /** Whether the cities' wind, rain and sea are heard (Settings → Ambient sound); off until the person turns it on. */
    var sound: Boolean
        get() = values.getProperty("sound") == "true"
        set(value) {
            values.setProperty("sound", value.toString())
            save()
        }

    /** Whether Ctrl+Alt+K turns the connection on and off (Settings → Keyboard shortcut); off until the person turns it on. */
    var hotkey: Boolean
        get() = values.getProperty("hotkey") == "true"
        set(value) {
            values.setProperty("hotkey", value.toString())
            save()
        }

    /** How KOne connects: Adapter (its own, TUN) or Proxy (Windows' proxy setting); Tools → Connection method. */
    var way: String
        get() = values.getProperty("way")?.takeIf { it == AppModel.WAY_PROXY } ?: AppModel.WAY_ADAPTER
        set(value) {
            values.setProperty("way", value)
            save()
        }

    /** The core's mode: Rule, Global or Direct (Tools → Mode). */
    var mode: String
        get() = values.getProperty("mode")?.takeIf { it in KoneConfig.MODES } ?: "Rule"
        set(value) {
            values.setProperty("mode", value)
            save()
        }

    /** The programs sent straight out, by file name (Tools → Direct apps). */
    var bypass: List<String>
        get() = values.getProperty("bypass")?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        set(value) {
            values.setProperty("bypass", KoneConfig.programs(value).joinToString("|"))
            save()
        }

    /** The sites sent straight out in every mode (Tools → Direct sites). */
    var direct: List<String>
        get() = values.getProperty("direct")?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        set(value) {
            values.setProperty("direct", KoneConfig.sites(value).joinToString("|"))
            save()
        }

    /** The sites through the node in Rule too (Tools → Proxied sites). */
    var proxied: List<String>
        get() = values.getProperty("proxied")?.split('|')?.filter { it.isNotBlank() }.orEmpty()
        set(value) {
            values.setProperty("proxied", KoneConfig.sites(value).joinToString("|"))
            save()
        }

    /** Servers of the person's own for chosen domains (Settings → DNS), kept as they are checked. */
    var nameServers: List<NameServer>
        get() = values.getProperty("nameServers")?.let { text ->
            runCatching { (Json.parseToJsonElement(text) as JsonArray).mapNotNull { (it as? JsonObject)?.let(NameServer::fromJson) } }.getOrNull()
        }.orEmpty()
        set(value) {
            values.setProperty("nameServers", JsonArray(NameServers.flatten(NameServers.clean(value)).map { it.toJson() }).toString())
            save()
        }

    /** Fixed answers for chosen names (Settings → Hosts), kept as they are checked. */
    var hosts: List<Host>
        get() = values.getProperty("hosts")?.let { text ->
            runCatching { (Json.parseToJsonElement(text) as JsonArray).mapNotNull { (it as? JsonObject)?.let(Host::fromJson) } }.getOrNull()
        }.orEmpty()
        set(value) {
            values.setProperty("hosts", JsonArray(Hosts.flatten(Hosts.clean(value)).map { it.toJson() }).toString())
            save()
        }

    /** The person's own routing rules, in order (Rules), kept as they are checked. */
    var rules: List<UserRule>
        get() = values.getProperty("rules")?.let { text ->
            runCatching { (Json.parseToJsonElement(text) as JsonArray).mapNotNull { (it as? JsonObject)?.let(UserRule::fromJson) } }.getOrNull()
        }.orEmpty()
        set(value) {
            values.setProperty("rules", JsonArray(UserRules.clean(value).map { it.toJson() }).toString())
            save()
        }

    /** Whether ads are refused (Rules → Block ads); off until the person turns it on. */
    /** Rule mode sends what nothing matched straight out rather than through the node (Rules). */
    var unmatchedDirect: Boolean
        get() = values.getProperty("unmatchedDirect") == "true"
        set(value) {
            values.setProperty("unmatchedDirect", value.toString())
            save()
        }

    var blockAds: Boolean
        get() = values.getProperty("blockAds") == "true"
        set(value) {
            values.setProperty("blockAds", value.toString())
            save()
        }

    /** The DNS server asked through the node: "" for the default (Cloudflare), a preset's name, or a DNS-over-HTTPS address. */
    var remoteDns: String
        get() = Dns.remoteChoice(values.getProperty("remoteDns").orEmpty())
        set(value) {
            values.setProperty("remoteDns", Dns.remoteChoice(value))
            save()
        }

    /** The DNS server asked straight out: "" for the default (AliDNS), a preset's name, or an address. */
    /**
     * Where the person is ([cc.txwd.kone.shared.Region]): outside China unless they say China,
     * as KOne is made for people outside it. Decides the China split and the default direct DNS.
     */
    var region: String
        get() = cc.txwd.kone.shared.Region.clean(values.getProperty("region") ?: cc.txwd.kone.shared.Region.GLOBAL)
        set(value) {
            values.setProperty("region", cc.txwd.kone.shared.Region.clean(value))
            save()
        }

    var directDns: String
        get() = Dns.directChoice(values.getProperty("directDns").orEmpty())
        set(value) {
            values.setProperty("directDns", Dns.directChoice(value))
            save()
        }

    /** Whether names are answered with IPv6 addresses too (IPv4 first); off until the person turns it on. */
    var ipv6: Boolean
        get() = values.getProperty("ipv6") == "true"
        set(value) {
            values.setProperty("ipv6", value.toString())
            save()
        }

    /** Whether a chosen node that stops answering is stood in for by the best working one (Settings → Fail over); on unless turned off. */
    var failover: Boolean
        get() = values.getProperty("failover") != "false"
        set(value) {
            values.setProperty("failover", value.toString())
            save()
        }

    /** Whether KOne was connected when it last went away without the person ending it (Windows shutting down). */
    var connected: Boolean
        get() = values.getProperty("connected") == "true"
        set(value) {
            if (value) values.setProperty("connected", "true") else values.remove("connected")
            save()
        }

    /** Where the window was last, in the screen's units, and whether it filled the screen. */
    var window: WindowPlace?
        get() = values.getProperty("window")?.split(',')?.let { parts ->
            val numbers = parts.take(4).map { it.trim().toIntOrNull() ?: return@let null }
            if (numbers.size < 4) null else WindowPlace(numbers[0], numbers[1], numbers[2], numbers[3], parts.getOrNull(4)?.trim() == "max")
        }
        set(value) {
            if (value == null) values.remove("window")
            else values.setProperty("window", "${value.x},${value.y},${value.width},${value.height}${if (value.maximized) ",max" else ""}")
            save()
        }

    private fun save() {
        runCatching { file.outputStream().use { values.store(it, null) } }
    }
}

data class WindowPlace(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean)
