package cc.txwd.kone.core

import cc.txwd.kone.shared.Host
import cc.txwd.kone.shared.Hosts
import cc.txwd.kone.shared.NameServer
import cc.txwd.kone.shared.NameServers
import cc.txwd.kone.console.ConsoleException
import cc.txwd.kone.console.ConsoleFailure
import cc.txwd.kone.shared.Countries
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A node of the console's source as the dashboard lists it: its tag in the selector, its name, the country its name says. */
data class SourceNode(val tag: String, val name: String, val country: String?, val type: String? = null)

/** Where the App talks to the running core: clash_api on loopback, with a secret made for this run. */
data class CoreApi(val port: Int, val secret: String)

/**
 * What the console delivers is a sing-box *source*. Like the phone (ManagedConfig and
 * PairedProfile.buildGroup), the App keeps the proxy outbounds, with whatever they reach
 * through `detour`, and puts them into its own template: TUN, DNS, the China rule sets and the
 * modes. The only rules kept from the source are the ones that refuse domain names, the
 * console's Claude block: right after sniff in the routes, and the same names refused in DNS
 * too, so a browser's own resolver cannot get round it. A source without such a rule blocks
 * nothing.
 */
object KoneConfig {
    const val PROXY_TAG = "proxy"

    /** The selector's Auto: whichever node answers fastest, tested every few minutes; there only with two nodes or more. */
    const val AUTO_TAG = "auto"

    /** The outbounds kept as nodes. naive is left out: it needs libcronet.dll, which the App does not carry. */
    val PROXY_TYPES = cc.txwd.kone.shared.SubscriptionContent.PROXY_TYPES

    /** [config] with fake-IP answers for what goes through the node, as the service builds them (desktop/service/fakeip.go). */
    private fun withFakeIp(config: JsonObject, ipv6: Boolean): JsonObject {
        val dns = config["dns"] as JsonObject
        val rules = (dns["rules"] as JsonArray).map { it as JsonObject }
        val servers = (dns["servers"] as JsonArray) + cc.txwd.kone.shared.FakeIp.server(ipv6)
        val faked = JsonObject(dns + mapOf("rules" to JsonArray(cc.txwd.kone.shared.FakeIp.rules(rules, ipv6)), "servers" to JsonArray(servers)))
        return JsonObject(config + ("dns" to faked))
    }

    /** Tags the template uses itself; a source outbound with one of these is renamed. */
    val RESERVED = setOf(PROXY_TAG, AUTO_TAG, "direct", "block", "dns", "local-dns", "proxy-dns", cc.txwd.kone.shared.FakeIp.TAG, "tun-in", "mixed-in")

    class Built(val config: JsonObject, val nodes: List<SourceNode>)

    /** The modes the core can be in: by the rules, everything through the node, or nothing through it. */
    val MODES = listOf("Rule", "Global", "Direct")

    /**
     * The KOne configuration for [source]: [rules] is the folder holding the bundled rule sets,
     * [selected] the node to start on if it is still there, [mode] one of [MODES], [bypass] the
     * programs that go straight out (by file name, such as WeChat.exe), [direct] the sites that go
     * straight out in every mode and [proxied] those through the node in Rule too. With [tun] false the core
     * only carries the nodes, for testing their delay without administrator rights; with [mixed]
     * it also listens on that port of 127.0.0.1 as the system proxy, when TUN cannot be had.
     */
    fun build(
        source: JsonObject, rules: String, api: CoreApi, selected: String? = null, tun: Boolean = true,
        mode: String = "Rule", bypass: List<String> = emptyList(), mixed: Int = 0,
        direct: List<String> = emptyList(), proxied: List<String> = emptyList(),
        routes: List<UserRule> = emptyList(), blockAds: Boolean = false,
        remoteDns: String = "", directDns: String = "", ipv6: Boolean = false,
        hosts: List<Host> = emptyList(),
        /** Servers of the person's own for the names under chosen domains, after the Claude block and the hosts. */
        nameServers: List<NameServer> = emptyList(),
        /** What no rule, list or category matched goes straight out in Rule mode, not through the node. */
        unmatchedDirect: Boolean = false,
        /** Where the person is ([cc.txwd.kone.shared.Region]): outside China there is no China split. */
        region: String = cc.txwd.kone.shared.Region.CHINA,
        /** The names that would be answered through the node get fake addresses ([cc.txwd.kone.shared.FakeIp]). */
        fakeIp: Boolean = false,
    ): Built {
        // An outbound that names a file is never carried: the service, which runs the core as
        // SYSTEM, refuses it the same way (desktop/service/plan.go).
        val all = (source["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.filterNot(::namesAFile) ?: throw invalid()
        val proxies = all.filter { it.text("type") in PROXY_TYPES }
        if (proxies.isEmpty()) throw ConsoleException(ConsoleFailure.ConfigInvalid, "No usable nodes in the configuration")
        val byTag = all.filter { !it.text("tag").isNullOrBlank() }.associateBy { it.text("tag")!! }

        // Whatever a node goes through is kept, however long the chain; a loop or a missing link is refused.
        val support = linkedMapOf<String, JsonObject>()
        fun follow(outbound: JsonObject, depth: Int) {
            val next = outbound.text("detour")?.takeIf { it.isNotBlank() } ?: return
            if (depth > 8) throw invalid()
            if (next in support) return
            val target = byTag[next] ?: throw invalid()
            if (target.text("type") !in PROXY_TYPES && target.text("type") != "direct") throw invalid()
            support[next] = target
            follow(target, depth + 1)
        }
        proxies.forEach { follow(it, 0) }
        val members = proxies.filter { it.text("tag") !in support.keys }
        if (members.isEmpty()) throw invalid()

        // Names stay as the console gave them; a tag that clashes with the template's, or repeats, moves aside.
        val used = mutableSetOf<String>()
        val renamed = mutableMapOf<String, String>()
        fun claim(wanted: String): String {
            val base = wanted.ifBlank { "node" }
            var tag = base
            var index = 2
            while (tag in used || tag in RESERVED || UserRules.viaOf(tag) != null) tag = "$base-${index++}"
            used += tag
            return tag
        }
        val nodes = members.map { outbound ->
            val name = outbound.text("tag").orEmpty()
            val tag = claim(name)
            if (name.isNotBlank()) renamed[name] = tag
            SourceNode(tag, name.ifBlank { tag }, Countries.of(name)?.code, outbound.text("type"))
        }
        val supportTags = support.keys.associateWith { claim(it).also { tag -> renamed[it] = tag } }
        fun retag(outbound: JsonObject, tag: String) = JsonObject(
            outbound + ("tag" to JsonPrimitive(tag)) + (outbound.text("detour")?.let { mapOf("detour" to JsonPrimitive(renamed[it] ?: it)) } ?: emptyMap()),
        )
        val memberOutbounds = members.mapIndexed { index, outbound -> retag(outbound, nodes[index].tag) }
        val supportOutbounds = support.map { (old, outbound) -> retag(outbound, supportTags.getValue(old)) }
        val auto = nodes.size >= 2
        val start = if (auto && selected == AUTO_TAG) AUTO_TAG else nodes.firstOrNull { it.tag == selected }?.tag ?: nodes.first().tag
        val refused = refusals(source)
        val straight = programs(bypass)
        val straightSites = sites(direct)
        val proxiedSites = sites(proxied)
        val own = UserRules.clean(routes)
        // A group for each country the rules go through, of that country's nodes here, tested as
        // Auto is; a country with none has no group, and its rules go through the node in use.
        val through = cc.txwd.kone.shared.Rules.countries(own)
            .mapNotNull { code -> nodes.filter { it.country == code }.map { it.tag }.takeIf { it.isNotEmpty() }?.let { code to it } }
        val groups = through.mapTo(HashSet()) { it.first }
        val fixed = Hosts.clean(hosts)
        val ownServers = NameServers.clean(nameServers)
        val china = cc.txwd.kone.shared.Region.china(region)

        val config = buildJsonObject {
            putJsonObject("log") { put("level", "warn"); put("timestamp", true) }
            putJsonObject("dns") {
                putJsonArray("servers") {
                    val remote = Dns.remote(remoteDns)
                    add(Dns.direct(directDns, region))
                    add(remote)
                    // Each country's own, asked through its group as the node's is through the node in use.
                    through.forEach { (code, _) ->
                        add(JsonObject(remote + mapOf("tag" to JsonPrimitive(cc.txwd.kone.shared.Rules.dnsThrough(code)), "detour" to JsonPrimitive(UserRules.viaTag(code)))))
                    }
                    NameServers.servers(ownServers).forEach { add(it) }
                }
                putJsonArray("rules") {
                    refused.forEach { add(it) }
                    // Fixed answers, after the Claude block so none answers for a refused name.
                    Hosts.dnsRules(fixed).forEach { add(it) }
                    // The person's own servers: after the Claude block too, so none answers for a refused name.
                    NameServers.dnsRules(ownServers).forEach { add(it) }
                    if (straight.isNotEmpty()) add(buildJsonObject { putJsonArray("process_name") { straight.forEach { add(it) } }; put("server", "local-dns") })
                    if (straightSites.isNotEmpty()) add(buildJsonObject { putJsonArray("domain_suffix") { straightSites.forEach { add(it) } }; put("server", "local-dns") })
                    if (proxiedSites.isNotEmpty()) add(buildJsonObject {
                        put("clash_mode", "Rule"); putJsonArray("domain_suffix") { proxiedSites.forEach { add(it) } }; put("server", "proxy-dns")
                    })
                    own.forEach { rule -> UserRules.dns(rule, groups)?.let { add(it) } }
                    if (blockAds) add(buildJsonObject { putJsonArray("rule_set") { add(ADS_RULE_SET) }; put("action", "reject") })
                    add(buildJsonObject { put("clash_mode", "Direct"); put("server", "local-dns") })
                    add(buildJsonObject { put("clash_mode", "Global"); put("server", "proxy-dns") })
                    if (china) add(buildJsonObject { putJsonArray("rule_set") { add("geosite-cn") }; put("server", "local-dns") })
                    if (unmatchedDirect) add(buildJsonObject { put("clash_mode", "Rule"); put("server", "local-dns") })
                }
                put("final", "proxy-dns")
                put("strategy", Dns.strategy(ipv6))
            }
            putJsonArray("inbounds") {
                if (mixed > 0) add(buildJsonObject { put("type", "mixed"); put("tag", "mixed-in"); put("listen", "127.0.0.1"); put("listen_port", mixed) })
                if (tun) add(buildJsonObject {
                    put("type", "tun"); put("tag", "tun-in"); put("interface_name", "KOne")
                    putJsonArray("address") { add("172.19.0.1/30"); add("fdfe:dcba:9876::1/126") }
                    put("auto_route", true)
                    // On Windows strict routing keeps DNS from leaking past the tunnel.
                    put("strict_route", true)
                })
            }
            putJsonArray("outbounds") {
                add(buildJsonObject {
                    put("type", "selector"); put("tag", PROXY_TAG)
                    putJsonArray("outbounds") { if (auto) add(AUTO_TAG); nodes.forEach { add(it.tag) } }
                    put("default", start)
                })
                // The App tests the node in use every few seconds and every node often, which also
                // feeds this group; its own sweep of every node can be rare, so large subscriptions stay cheap.
                if (auto) add(buildJsonObject {
                    put("type", "urltest"); put("tag", AUTO_TAG)
                    putJsonArray("outbounds") { nodes.forEach { add(it.tag) } }
                    put("url", "https://www.gstatic.com/generate_204"); put("interval", "10m"); put("tolerance", 50)
                })
                through.forEach { (code, tags) ->
                    add(buildJsonObject {
                        put("type", "urltest"); put("tag", UserRules.viaTag(code))
                        putJsonArray("outbounds") { tags.forEach { add(it) } }
                        put("url", "https://www.gstatic.com/generate_204"); put("interval", "10m"); put("tolerance", 50)
                    })
                }
                memberOutbounds.forEach { add(it) }
                supportOutbounds.forEach { add(it) }
                add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
            }
            putJsonObject("route") {
                putJsonArray("rules") {
                    add(buildJsonObject { put("action", "sniff") })
                    add(buildJsonObject { put("protocol", "dns"); put("action", "hijack-dns") })
                    refused.forEach { add(it) }
                    // KOne's own test cores (delays, speed) reach their nodes straight out, not through the tunnel.
                    if (tun) add(buildJsonObject { putJsonArray("process_name") { add("sing-box.exe") }; put("outbound", "direct") })
                    // Names with fixed answers are resolved here before routing: a node is never asked for them.
                    if (fixed.isNotEmpty()) add(Hosts.resolveRule(fixed))
                    // Programs the person sent straight out: after the Claude block, so it holds for them too.
                    if (straight.isNotEmpty()) add(buildJsonObject { putJsonArray("process_name") { straight.forEach { add(it) } }; put("outbound", "direct") })
                    // The person's sites, after the Claude block too: straight out in every mode, through the node in Rule.
                    if (straightSites.isNotEmpty()) add(buildJsonObject { putJsonArray("domain_suffix") { straightSites.forEach { add(it) } }; put("outbound", "direct") })
                    if (proxiedSites.isNotEmpty()) add(buildJsonObject {
                        put("clash_mode", "Rule"); putJsonArray("domain_suffix") { proxiedSites.forEach { add(it) } }; put("outbound", PROXY_TAG)
                    })
                    // The person's own rules, in order; then ads, refused in every mode, so a site they let through stays reachable.
                    own.forEach { add(UserRules.route(it, groups)) }
                    if (blockAds) add(buildJsonObject { putJsonArray("rule_set") { add(ADS_RULE_SET) }; put("action", "reject") })
                    add(buildJsonObject { put("clash_mode", "Direct"); put("outbound", "direct") })
                    add(buildJsonObject { put("clash_mode", "Global"); put("outbound", PROXY_TAG) })
                    add(buildJsonObject { put("ip_is_private", true); put("outbound", "direct") })
                    if (china) add(buildJsonObject { put("clash_mode", "Rule"); putJsonArray("rule_set") { add("geosite-cn"); add("geoip-cn") }; put("outbound", "direct") })
                    add(buildJsonObject { put("clash_mode", "Rule"); put("outbound", if (unmatchedDirect) "direct" else PROXY_TAG) })
                }
                putJsonArray("rule_set") {
                    val sets = (if (china) listOf("geosite-cn" to "sing-geosite", "geoip-cn" to "sing-geoip") else emptyList()) + if (blockAds) listOf(ADS_RULE_SET to "sing-geosite") else emptyList()
                    for ((tag, repo) in sets) {
                        add(buildJsonObject {
                            put("type", "remote"); put("tag", tag); put("format", "binary")
                            put("url", "https://raw.githubusercontent.com/SagerNet/$repo/rule-set/$tag.srs")
                            put("initial_path", "$rules/$tag.srs")
                            putJsonObject("http_client") { put("detour", PROXY_TAG); put("domain_resolver", "local-dns") }
                        })
                    }
                    // The person's category lists: local files the App (or the service) keeps, never fetched by the core.
                    UserRules.sets(own).forEach { name ->
                        add(buildJsonObject { put("type", "local"); put("tag", name); put("format", "binary"); put("path", "$rules/sets/$name.srs") })
                    }
                }
                put("auto_detect_interface", true)
                // Which program each connection is, for Tools's list and the programs sent straight out.
                put("find_process", true)
                put("default_domain_resolver", "local-dns")
                put("final", PROXY_TAG)
            }
            // No cache file: the core runs as administrator and writes nothing into the person's folders.
            putJsonObject("experimental") {
                putJsonObject("clash_api") {
                    put("external_controller", "127.0.0.1:${api.port}")
                    put("secret", api.secret)
                    put("default_mode", mode.takeIf { it in MODES } ?: "Rule")
                }
            }
        }
        return Built(if (fakeIp) withFakeIp(config, ipv6) else config, nodes)
    }

    /**
     * The nodes whose names say each country, by code (JP to the tags of Japan's nodes), as the
     * service takes them to build the groups for the rules through a country (Options.Countries).
     */
    fun countries(nodes: List<SourceNode>): Map<String, List<String>> =
        nodes.filter { it.country != null }.groupBy({ it.country!! }, { it.tag })

    /**
     * The programs to send straight out, as file names: a name with a path or anything Windows
     * would not take in a file name is dropped, each name once, 64 at most. The service takes the
     * same (desktop/service/plan.go).
     */
    fun programs(names: List<String>): List<String> = cc.txwd.kone.shared.Names.programs(names)

    /**
     * The person's sites, as domains: anything but a plain domain name of two labels or more is
     * dropped, each name once, 256 at most. The service takes the same (desktop/service/plan.go).
     */
    fun sites(names: List<String>): List<String> = cc.txwd.kone.shared.Names.sites(names)

    /** The site in what the person typed or pasted: a domain, or the one in an address (https://www.example.com/a → www.example.com). */
    fun site(text: String): String? = cc.txwd.kone.shared.Names.site(text)

    fun isSite(text: String) = cc.txwd.kone.shared.Names.isSite(text)

    fun isProgram(text: String) = cc.txwd.kone.shared.Names.isProgram(text)

    /** The ad-blocking rule set carried beside the China ones (v2fly's category-ads-all). */
    const val ADS_RULE_SET = cc.txwd.kone.shared.Rules.ADS

    /**
     * The source's rules that refuse domain names and do nothing else, as the console writes them
     * for a customer kept from Claude. A reject rule with any other condition, or without a name,
     * is ignored: it could refuse far more than names. The list of names is the console's, not the App's.
     */
    fun refusals(source: JsonObject): List<JsonObject> {
        val rules = ((source["route"] as? JsonObject)?.get("rules") as? JsonArray) ?: return emptyList()
        return rules.mapNotNull { it as? JsonObject }.mapNotNull { rule ->
            if (rule.text("action") != "reject" || rule.keys.any { it !in REFUSAL_KEYS }) return@mapNotNull null
            val kept = buildJsonObject {
                for (field in listOf("domain", "domain_suffix")) {
                    val names = (rule[field] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
                        ?.filter { it.isNotEmpty() }.orEmpty()
                    if (names.isNotEmpty()) put(field, buildJsonArray { names.forEach { add(JsonPrimitive(it)) } })
                }
            }
            if (kept.isEmpty()) null else JsonObject(kept + ("action" to JsonPrimitive("reject")))
        }
    }

    private val REFUSAL_KEYS = setOf("action", "domain", "domain_suffix")

    /** Whether anything in it is a file path option (certificate_path, private_key_path and the like). */
    private fun namesAFile(element: kotlinx.serialization.json.JsonElement): Boolean = when (element) {
        is JsonObject -> element.any { (key, value) -> key.lowercase().endsWith("_path") || namesAFile(value) }
        is JsonArray -> element.any(::namesAFile)
        else -> false
    }

    private fun invalid() = ConsoleException(ConsoleFailure.ConfigInvalid)

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}

private fun kotlinx.serialization.json.JsonArrayBuilder.add(value: String) = add(JsonPrimitive(value))
