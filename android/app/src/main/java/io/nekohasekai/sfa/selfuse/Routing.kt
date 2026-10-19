package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.Host
import cc.txwd.kone.shared.Hosts
import cc.txwd.kone.shared.NameServer
import cc.txwd.kone.shared.NameServers
import cc.txwd.kone.shared.Names
import cc.txwd.kone.shared.Region
import cc.txwd.kone.shared.Rule
import cc.txwd.kone.shared.RuleImport
import cc.txwd.kone.shared.Rules
import org.json.JSONArray
import org.json.JSONObject

/**
 * The person's routing choices, applied to a configuration KOne built (a share-link profile or the
 * console's managed one): sites sent straight out or through the node, ad blocking, what nothing
 * matches, the two DNS servers and IPv6. The same choices as KOne for Windows.
 *
 * KOne owns one stretch of the template's rules: after sniffing, Home and the console's refusals
 * (the Claude block), and before the modes. Applying replaces that stretch, so it can run again
 * and again with the same result, and with the default choices it leaves the template exactly as
 * it was built. Nothing here can come before the refusals, so no choice reaches round the Claude
 * block. A configuration not built by KOne comes back unchanged.
 */
object Routing {
    data class Choices(
        /** Sites sent straight out in every mode, by domain (example.cn and what is under it). */
        val direct: List<String> = emptyList(),
        /** Sites sent through the node in Rule mode too. */
        val proxied: List<String> = emptyList(),
        /** The person's own rules, in order, after the site lists ([OwnRules]). */
        val rules: List<Rule> = emptyList(),
        /** Refuse the domains of the ad list. */
        val blockAds: Boolean = false,
        /** In Rule mode, what nothing matched goes straight out instead of through the node. */
        val unmatchedDirect: Boolean = false,
        /** The DNS server asked through the node: a preset's name, "" for Cloudflare. */
        val remoteDns: String = "",
        /** The DNS server asked straight out: a preset's name, "" for AliDNS. */
        val directDns: String = "",
        /** Answer names with IPv6 addresses too (IPv4 first). */
        val ipv6: Boolean = false,
        /**
         * With several nodes, an Automatic choice that uses the fastest one answering, tried every
         * few minutes, and leaves one that stops answering. Off here so the template stays as built.
         */
        val automatic: Boolean = false,
        /**
         * Where the person is ([Region]): in China, Chinese sites go straight out and AliDNS answers
         * by default; elsewhere there is no China split and Cloudflare answers. The template is
         * built for China, so this default leaves it as built.
         */
        val region: String = Region.CHINA,
        /** Fixed answers: a name and an address for it, as a hosts file gives them ([Hosts]). */
        val hosts: List<Host> = emptyList(),
        /** DNS servers of the person's own for the names under chosen domains, as an intranet's ([NameServers]). */
        val nameServers: List<NameServer> = emptyList(),
    )

    /** The DNS-over-HTTPS servers asked through the node, by preset. */
    val REMOTE = linkedMapOf("cloudflare" to "1.1.1.1", "google" to "8.8.8.8", "quad9" to "9.9.9.9")

    /** The plain DNS servers asked straight out, by preset. */
    val DIRECT = Region.DIRECT

    const val ADS = cc.txwd.kone.shared.Rules.ADS
    const val MOST_SITES = 256

    private const val PROXY = PairedProfile.PROXY_TAG
    private const val LOCAL_DNS = "local-dns"
    private const val PROXY_DNS = "proxy-dns"
    private const val OWN_DNS = "own-dns-"

    /** The Automatic choice's tag, as the node list shows it. */
    const val AUTO = "Automatic"

    /** The site in what the person typed or pasted: the shared core's reading ([Names.site]). */
    fun site(text: String): String? = Names.site(text)

    /** Sites as the template takes them: each one cleaned, once, [MOST_SITES] at most. */
    fun sites(names: List<String>): List<String> = names.mapNotNull(::site).distinct().take(MOST_SITES)

    /** What a paste of another client's rules did: the choices to keep, and a line to say. */
    data class Imported(val choices: Choices, val said: String)

    /**
     * Rules pasted from a Clash profile or v2rayN (the shared core reads both), added after the
     * person's own, each checked as any rule is; one blocking the ad list turns on Block ads.
     */
    fun importRules(choices: Choices, text: String): Imported {
        val read = RuleImport.read(text, Rules.ANDROID)
        val ads = read.blockAds && !choices.blockAds
        val rules = OwnRules.clean(choices.rules + read.rules)
        val added = rules.size - OwnRules.clean(choices.rules).size
        val skipped = read.rules.size - added + read.left
        val said = when {
            added == 0 && !ads && read.left > 0 -> "None of these ${read.left} rules is one KOne takes"
            added == 0 && !ads -> "No rules found"
            else -> listOfNotNull(
                "$added rules added".takeIf { added > 0 },
                "Block ads turned on".takeIf { ads },
                "$skipped left out".takeIf { skipped > 0 },
            ).joinToString("; ")
        }
        return Imported(choices.copy(rules = rules, blockAds = choices.blockAds || read.blockAds), said)
    }

    /** Whether [config] is KOne's template: its node selector, its two DNS servers and the Direct mode rule. */
    fun isTemplate(config: JSONObject): Boolean {
        val outbounds = config.optJSONArray("outbounds") ?: return false
        val selector = objects(outbounds).any { it.optString("tag") == PROXY && it.optString("type") == "selector" }
        val servers = objects(config.optJSONObject("dns")?.optJSONArray("servers")).map { it.optString("tag") }.toSet()
        return selector && LOCAL_DNS in servers && PROXY_DNS in servers && directMode(config.optJSONObject("route")?.optJSONArray("rules")) >= 0
    }

    /**
     * [content] with [choices] applied when it is KOne's template; otherwise unchanged. [lists]
     * are the category lists kept on the phone (each name to its file): a rule naming a list not
     * kept yet is left out until the list arrives, so a list that cannot be downloaded never
     * stops KOne from starting.
     */
    fun apply(content: String, choices: Choices, lists: Map<String, String> = emptyMap()): String {
        val config = try { JSONObject(content) } catch (_: Exception) { return content }
        if (!isTemplate(config)) return content
        apply(config, choices, lists)
        return config.toString(2)
    }

    internal fun apply(config: JSONObject, choices: Choices, lists: Map<String, String> = emptyMap()) {
        val direct = sites(choices.direct)
        val proxied = sites(choices.proxied).filter { it !in direct }
        val own = OwnRules.clean(choices.rules).filter { it.kind != "set" || it.value in lists }

        // Route: the stretch between the leading rules and the Direct mode rule is KOne's.
        val route = config.getJSONObject("route")
        val rules = objects(route.optJSONArray("rules"))
        val end = directMode(route.optJSONArray("rules"))
        val start = rules.take(end).indexOfFirst { !leading(it) }.let { if (it < 0) end else it }
        val ours = buildList {
            if (direct.isNotEmpty()) add(JSONObject().put("domain_suffix", JSONArray(direct)).put("outbound", "direct"))
            if (proxied.isNotEmpty()) add(JSONObject().put("clash_mode", "Rule").put("domain_suffix", JSONArray(proxied)).put("outbound", PROXY))
            own.forEach { add(OwnRules.route(it)) }
            // Ads after the person's own rules, so a site they let through stays reachable.
            if (choices.blockAds) add(JSONObject().put("rule_set", JSONArray().put(ADS)).put("action", "reject"))
        }
        val chinaRoute = JSONObject().put("clash_mode", "Rule").put("rule_set", JSONArray().put("geosite-cn").put("geoip-cn")).put("outbound", "direct")
        val rest = rules.drop(end).filterNot { same(it, chinaRoute) }.toMutableList()
        // The China split sits just before the last rule (what nothing matched); outside China it is gone.
        if (Region.china(choices.region)) rest.add((rest.size - 1).coerceAtLeast(0), chinaRoute)
        val routed = rules.take(start) + ours + rest
        // What nothing matched, in Rule mode: the last rule, through the node or straight out.
        routed.lastOrNull { it.length() == 2 && it.optString("clash_mode") == "Rule" && it.has("outbound") }
            ?.put("outbound", if (choices.unmatchedDirect) "direct" else PROXY)
        route.put("rules", JSONArray(routed))
        setAdList(route, choices.blockAds)
        setCategoryLists(route, Rules.sets(own).associateWith { lists.getValue(it) })
        setAutomatic(config, choices.automatic)

        // DNS: the same stretch, each list's names answered where its traffic goes.
        val dns = config.getJSONObject("dns")
        val dnsRules = objects(dns.optJSONArray("rules"))
        val dnsEnd = dnsRules.indexOfFirst { it.length() == 2 && it.optString("clash_mode") == "Direct" && it.optString("server") == LOCAL_DNS }
            .let { if (it < 0) dnsRules.size else it }
        val dnsStart = dnsRules.take(dnsEnd).indexOfFirst { !leadingDns(it) }.let { if (it < 0) dnsEnd else it }
        val ownServers = NameServers.clean(choices.nameServers)
        val dnsOurs = buildList {
            // Fixed answers and the person's own servers first, still after the console's refusals.
            Hosts.dnsRules(Hosts.clean(choices.hosts)).forEach { add(JSONObject(it.toString())) }
            NameServers.dnsRules(ownServers).forEach { add(JSONObject(it.toString())) }
            if (direct.isNotEmpty()) add(JSONObject().put("domain_suffix", JSONArray(direct)).put("server", LOCAL_DNS))
            if (proxied.isNotEmpty()) add(JSONObject().put("clash_mode", "Rule").put("domain_suffix", JSONArray(proxied)).put("server", PROXY_DNS))
            own.forEach { rule -> OwnRules.dns(rule)?.let { add(it) } }
            if (choices.blockAds) add(JSONObject().put("rule_set", JSONArray().put(ADS)).put("action", "reject"))
        }
        val unmatched = { rule: JSONObject -> rule.length() == 2 && rule.optString("clash_mode") == "Rule" && rule.optString("server") == LOCAL_DNS }
        val chinaNames = JSONObject().put("rule_set", JSONArray().put("geosite-cn")).put("server", LOCAL_DNS)
        val dnsRest = dnsRules.drop(dnsEnd).filterNot(unmatched).filterNot { same(it, chinaNames) }.toMutableList()
        // Chinese names are answered straight out in China, right after the modes; elsewhere not.
        if (Region.china(choices.region)) {
            val global = dnsRest.indexOfFirst { it.optString("clash_mode") == "Global" }
            dnsRest.add(if (global < 0) dnsRest.size else global + 1, chinaNames)
        }
        val answered = dnsRules.take(dnsStart) + dnsOurs + dnsRest +
            listOfNotNull(JSONObject().put("clash_mode", "Rule").put("server", LOCAL_DNS).takeIf { choices.unmatchedDirect })
        dns.put("rules", JSONArray(answered))
        dns.put("strategy", if (choices.ipv6) "prefer_ipv4" else "ipv4_only")
        // The person's own servers beside the template's, asked straight out; gone when none is set.
        val servers = objects(dns.optJSONArray("servers")).filterNot { it.optString("tag").startsWith(OWN_DNS) } +
            NameServers.servers(ownServers).map { JSONObject(it.toString()) }
        dns.put("servers", JSONArray(servers))
        objects(dns.optJSONArray("servers")).forEach { server ->
            when (server.optString("tag")) {
                LOCAL_DNS -> server.put("server", DIRECT[choices.directDns] ?: Region.directDefault(choices.region))
                PROXY_DNS -> server.put("server", REMOTE[choices.remoteDns] ?: REMOTE.getValue("cloudflare"))
            }
        }
    }

    /** The ad list among the rule sets when ads are blocked, beside the China lists and like them; gone otherwise. */
    private fun setAdList(route: JSONObject, on: Boolean) {
        val sets = objects(route.optJSONArray("rule_set")).filterNot { it.optString("tag") == ADS }.toMutableList()
        if (on) {
            val china = sets.firstOrNull { it.optString("tag") == "geosite-cn" }
            val folder = china?.optString("initial_path")?.substringBeforeLast('/', "")?.takeIf { it.isNotEmpty() }
            sets += JSONObject()
                .put("type", "remote").put("tag", ADS).put("format", "binary")
                .put("url", "https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/$ADS.srs")
                .apply { if (folder != null) put("initial_path", "$folder/$ADS.srs") }
                .put("http_client", JSONObject().put("detour", PROXY).put("domain_resolver", LOCAL_DNS))
        }
        route.put("rule_set", JSONArray(sets))
    }

    /**
     * The Automatic choice ([AUTO]) among the node choices, first and the default, when [on] and
     * there are two nodes or more; gone otherwise, with the first node the default again. Which
     * node the person picked is kept by sing-box itself (its cache file), so a pick survives.
     */
    private fun setAutomatic(config: JSONObject, on: Boolean) {
        val outbounds = objects(config.optJSONArray("outbounds"))
        val selector = outbounds.firstOrNull { it.optString("tag") == PROXY && it.optString("type") == "selector" } ?: return
        val members = (0 until (selector.optJSONArray("outbounds")?.length() ?: 0)).map { selector.getJSONArray("outbounds").optString(it) }.filter { it != AUTO }
        val kept = outbounds.filterNot { it.optString("tag") == AUTO && it.optString("type") == "urltest" }.toMutableList()
        if (on && members.size >= 2) {
            val index = kept.indexOf(selector) + 1
            kept.add(index, JSONObject().put("type", "urltest").put("tag", AUTO).put("outbounds", JSONArray(members))
                .put("url", "https://www.gstatic.com/generate_204").put("interval", "5m").put("tolerance", 50))
            selector.put("outbounds", JSONArray(listOf(AUTO) + members)).put("default", AUTO)
        } else {
            selector.put("outbounds", JSONArray(members))
            if (selector.optString("default") == AUTO || selector.optString("default") !in members) selector.put("default", members.firstOrNull())
        }
        config.put("outbounds", JSONArray(kept))
    }

    /**
     * The category lists the rules name, read from the phone's own copies ([lists], name to file);
     * the lists no rule names any more are gone. sing-box watches a local list, so a newer copy
     * takes effect without a restart.
     */
    private fun setCategoryLists(route: JSONObject, lists: Map<String, String>) {
        val sets = objects(route.optJSONArray("rule_set")).filterNot { it.optString("type") == "local" && Rules.isSetName(it.optString("tag")) }.toMutableList()
        lists.forEach { (name, path) -> sets += JSONObject().put("type", "local").put("tag", name).put("format", "binary").put("path", path) }
        route.put("rule_set", JSONArray(sets))
    }

    /** The index of the Direct mode rule, which ends KOne's stretch; -1 when there is none. */
    private fun directMode(rules: JSONArray?): Int =
        objects(rules).indexOfFirst { it.length() == 2 && it.optString("clash_mode") == "Direct" && it.optString("outbound") == "direct" }

    /** Rules that stay ahead of KOne's stretch: sniffing, DNS, Home, and the console's refusals. */
    private fun leading(rule: JSONObject): Boolean {
        val action = rule.optString("action", "route")
        return action == "sniff" || action == "resolve" || (action == "hijack-dns" && rule.optString("protocol") == "dns") ||
            rule.optString("outbound") == PairedProfile.HOME_TAG || refusal(rule)
    }

    private fun leadingDns(rule: JSONObject): Boolean = rule.optString("server") == HomeLink.DNS_TAG || refusal(rule)

    /** A rule refusing domain names and nothing else, as the console writes the Claude block. */
    private fun refusal(rule: JSONObject): Boolean =
        rule.optString("action") == "reject" && rule.keys().asSequence().all { it in setOf("action", "domain", "domain_suffix") }

    /** Whether two rules say the same, whatever order their keys were written in. */
    private fun same(a: JSONObject, b: JSONObject): Boolean =
        a.length() == b.length() && a.keys().asSequence().all { key -> b.has(key) && a.get(key).toString() == b.get(key).toString() }

    private fun objects(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
}
