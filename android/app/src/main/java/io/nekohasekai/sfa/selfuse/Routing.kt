package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.Countries
import cc.txwd.kone.shared.FakeIp
import cc.txwd.kone.shared.Host
import cc.txwd.kone.shared.Hosts
import cc.txwd.kone.shared.NameServer
import cc.txwd.kone.shared.NameServers
import cc.txwd.kone.shared.Names
import cc.txwd.kone.shared.Policies
import cc.txwd.kone.shared.Region
import cc.txwd.kone.shared.Rule
import cc.txwd.kone.shared.RuleImport
import cc.txwd.kone.shared.RuleProviders
import cc.txwd.kone.shared.Rules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
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
        /** The names that would be answered through the node get fake addresses ([FakeIp]), kept across restarts. */
        val fakeIp: Boolean = false,
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

    /** The group of one country's nodes that the rules through it use, named as groups are shown: "Through Japan". */
    fun through(code: String): String = "Through ${Countries.byCode(code)?.en ?: code}"

    /** The country a group's [tag] is for ("JP" for "Through Japan"), or null when it is not one. */
    fun throughOf(tag: String): String? = if (tag.startsWith("Through ")) Countries.all.firstOrNull { through(it.code) == tag }?.code else null

    /** The countries the node choices of [content] say, by code, when it is KOne's template; rules can go through these. */
    fun countries(content: String): List<String> {
        val config = try { JSONObject(content) } catch (_: Exception) { return emptyList() }
        if (!isTemplate(config)) return emptyList()
        return nodeChoices(config).mapNotNull { Countries.of(it)?.code }.distinct()
    }

    /** The site in what the person typed or pasted: the shared core's reading ([Names.site]). */
    fun site(text: String): String? = Names.site(text)

    /** Sites as the template takes them: each one cleaned, once, [MOST_SITES] at most. */
    fun sites(names: List<String>): List<String> = names.mapNotNull(::site).distinct().take(MOST_SITES)

    /** How many nodes [content] chooses between when it is KOne's template; a pick needs two. */
    fun nodeCount(content: String): Int {
        val config = try { JSONObject(content) } catch (_: Exception) { return 0 }
        return if (isTemplate(config)) nodeChoices(config).size else 0
    }

    /**
     * [choices] with [host] (a site or an address, as the Status tab shows it) sent straight out
     * ([direct]) or through the node, by an own rule first among them; null when [host] is
     * neither. The site lists come before the own rules, so the same site there leaves them, and
     * one it is under that goes the other way moves into the own rules just after the new rule,
     * where it means what it did: the new rule wins and every other site goes as before.
     */
    fun send(choices: Choices, host: String, direct: Boolean): Choices? {
        val action = if (direct) "direct" else "proxy"
        val site = site(host)
        val rule = site?.let { Rule("suffix", it, action) } ?: Names.network(host.trim())?.let { Rule("cidr", it, action) } ?: return null
        val directSites = sites(choices.direct)
        val proxiedSites = sites(choices.proxied).filter { it !in directSites }
        fun under(entry: String) = site != null && site.endsWith(".$entry")
        val moved = if (direct) proxiedSites.filter(::under).map { Rule("suffix", it, "proxy") } else directSites.filter(::under).map { Rule("suffix", it, "direct") }
        val movedSites = moved.map { it.value }.toSet()
        return choices.copy(
            direct = directSites.filter { it != site && it !in movedSites },
            proxied = proxiedSites.filter { it != site && it !in movedSites },
            rules = OwnRules.clean(listOf(rule) + moved + choices.rules.filterNot { it.kind == rule.kind && it.value == rule.value }),
        )
    }

    /** What a paste of another client's rules did: the choices to keep, and a line to say. */
    data class Imported(val choices: Choices, val said: String)

    /**
     * Rules pasted from a Clash profile or v2rayN (the shared core reads both), added after the
     * person's own, each checked as any rule is; one blocking the ad list turns on Block ads.
     */
    fun importRules(choices: Choices, text: String): Imported {
        val read = RuleImport.read(text, Rules.ANDROID, picks = true)
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
     * are the lists kept on the phone, each to its file: category lists by name, own lists by
     * address. A rule naming a list not kept yet is left out until the list arrives, so a list
     * that cannot be downloaded never stops KOne from starting.
     */
    fun apply(content: String, choices: Choices, lists: Map<String, String> = emptyMap()): String {
        val config = try { JSONObject(content) } catch (_: Exception) { return content }
        if (!isTemplate(config)) return content
        apply(config, choices, lists)
        return config.toString(2)
    }

    internal fun apply(config: JSONObject, choices: Choices, lists: Map<String, String> = emptyMap()) {
        // Fake-IP answers stand beside the rules they fake; taken out first, the rest is read as built.
        stripFakeIp(config)
        val direct = sites(choices.direct)
        val proxied = sites(choices.proxied).filter { it !in direct }
        val own = OwnRules.clean(choices.rules).filter { (it.kind != "set" && it.kind != "url") || it.value in lists }
        // The countries the rules go through that the nodes here say, each with its nodes.
        val through = throughGroups(config, own)
        val groups = through.keys
        // The groups the rules' picks go through: the fastest or the first that answers.
        val picks = picks(config, own, through, choices.automatic)

        // Route: the stretch between the leading rules and the Direct mode rule is KOne's.
        val route = config.getJSONObject("route")
        val rules = objects(route.optJSONArray("rules"))
        val end = directMode(route.optJSONArray("rules"))
        val start = rules.take(end).indexOfFirst { !leading(it) }.let { if (it < 0) end else it }
        val ours = buildList {
            if (direct.isNotEmpty()) add(JSONObject().put("domain_suffix", JSONArray(direct)).put("outbound", "direct"))
            if (proxied.isNotEmpty()) add(JSONObject().put("clash_mode", "Rule").put("domain_suffix", JSONArray(proxied)).put("outbound", PROXY))
            own.forEach { add(OwnRules.route(it, groups, picks.via[it])) }
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
        setCategoryLists(route, Rules.sets(own).associateWith { lists.getValue(it) }, Rules.urls(own).associateWith { lists.getValue(it) })
        setAutomatic(config, choices.automatic)
        setThrough(config, through)
        setPicks(config, picks.groups)

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
            own.forEach { rule -> OwnRules.dns(rule, groups)?.let { add(it) } }
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
        val servers = objects(dns.optJSONArray("servers")).filterNot { it.optString("tag").let { tag -> tag.startsWith(OWN_DNS) || tag.startsWith(Rules.DNS_THROUGH) } } +
            NameServers.servers(ownServers).map { JSONObject(it.toString()) }
        servers.forEach { server ->
            when (server.optString("tag")) {
                LOCAL_DNS -> server.put("server", DIRECT[choices.directDns] ?: Region.directDefault(choices.region))
                PROXY_DNS -> server.put("server", REMOTE[choices.remoteDns] ?: REMOTE.getValue("cloudflare"))
            }
        }
        // Each country's own after the node's, asked through its group as the node's is through the node in use.
        val remote = servers.indexOfFirst { it.optString("tag") == PROXY_DNS }
        val countryServers = if (remote < 0) emptyList() else through.keys.map { code ->
            JSONObject(servers[remote].toString()).put("tag", Rules.dnsThrough(code)).put("detour", through(code))
        }
        dns.put("servers", JSONArray(servers.take(remote + 1) + countryServers + servers.drop(remote + 1)))
        // Last, over the DNS rules as they now stand.
        setFakeIp(config, choices.fakeIp, choices.ipv6)
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
     * Fake-IP answers ([FakeIp]) for what goes through the node when [on], as KOne for Windows
     * builds them; the mappings are kept in sing-box's cache file across restarts, so an app
     * holding a fake address still reaches its site. Off, every trace of it is gone.
     */
    private fun setFakeIp(config: JSONObject, on: Boolean, ipv6: Boolean) {
        if (!on) return
        val dns = config.getJSONObject("dns")
        val faked = FakeIp.rules(objects(dns.optJSONArray("rules")).map { Json.parseToJsonElement(it.toString()).jsonObject }, ipv6)
        dns.put("rules", JSONArray(faked.map { JSONObject(it.toString()) }))
        dns.put("servers", JSONArray(objects(dns.optJSONArray("servers")) + JSONObject(FakeIp.server(ipv6).toString())))
        config.optJSONObject("experimental")?.optJSONObject("cache_file")?.put("store_fakeip", true)
    }

    /** Every trace of [setFakeIp]: its rules, its server and the cache file's mappings. */
    private fun stripFakeIp(config: JSONObject) {
        val dns = config.optJSONObject("dns") ?: return
        dns.put(
            "rules",
            JSONArray(
                objects(dns.optJSONArray("rules")).filterNot { rule ->
                    val names = rule.optJSONArray("domain_suffix")?.let { array -> (0 until array.length()).map { array.optString(it) } }
                    rule.optString("server") == FakeIp.TAG || FakeIp.isRealNames(names, rule.optString("server"))
                },
            ),
        )
        dns.put("servers", JSONArray(objects(dns.optJSONArray("servers")).filterNot { it.optString("tag") == FakeIp.TAG }))
        config.optJSONObject("experimental")?.optJSONObject("cache_file")?.remove("store_fakeip")
    }

    /**
     * The countries [rules] go through that the node choices say, each to its nodes in order. A
     * country whose group's tag a node already has is left out, so its rules go through the node.
     */
    private fun throughGroups(config: JSONObject, rules: List<Rule>): Map<String, List<String>> {
        val codes = Rules.countries(rules)
        if (codes.isEmpty()) return emptyMap()
        val members = nodeChoices(config)
        val taken = objects(config.optJSONArray("outbounds")).filterNot { it.optString("type") == "urltest" }.map { it.optString("tag") }.toSet()
        return codes.mapNotNull { code ->
            members.filter { Countries.of(it)?.code == code }.takeIf { it.isNotEmpty() && through(code) !in taken }?.let { code to it }
        }.toMap()
    }

    /** The groups for the rules through a country ([through], code to nodes), after the node choices; gone when no rule needs one. */
    private fun setThrough(config: JSONObject, through: Map<String, List<String>>) {
        val kept = objects(config.optJSONArray("outbounds")).filterNot { it.optString("type") == "urltest" && throughOf(it.optString("tag")) != null }.toMutableList()
        var index = kept.indexOfFirst { it.optString("tag") == PROXY && it.optString("type") == "selector" } + 1
        while (index < kept.size && kept[index].optString("type") == "urltest" && automatic(kept[index].optString("tag"))) index++
        kept.addAll(index, through.map { (code, tags) -> urlTest(through(code), tags) })
        config.put("outbounds", JSONArray(kept))
    }

    /** The node choices of the template's selector, without the Automatic ones. */
    private fun nodeChoices(config: JSONObject): List<String> {
        val selector = objects(config.optJSONArray("outbounds")).firstOrNull { it.optString("tag") == PROXY && it.optString("type") == "selector" } ?: return emptyList()
        val choices = selector.optJSONArray("outbounds") ?: return emptyList()
        return (0 until choices.length()).map { choices.optString(it) }.filterNot(::automatic)
    }

    /** Whether [tag] is the Automatic choice or one of its countries'. */
    private fun automatic(tag: String) = tag == AUTO || tag.startsWith("$AUTO · ")

    /** A group that uses the fastest of [of], tested every few minutes, as Automatic does. */
    private fun urlTest(tag: String, of: List<String>) = JSONObject(Policies.group(tag, of, Policies.FASTEST).toString())

    /** The groups the rules' picks need (each tag to its nodes and pick), and the group each such rule goes through. */
    private class Picks(val groups: Map<String, Pair<List<String>, String>>, val via: Map<Rule, String>)

    /**
     * The groups for the rules that pick their node ([Policies]): the fastest or the first that
     * answers, of every node or of a country's ([through]). With one node there is nothing to
     * pick, and the rule goes as it would without one. The fastest of every node is Automatic
     * itself when that is on, rather than a second group testing the same nodes.
     */
    private fun picks(config: JSONObject, rules: List<Rule>, through: Map<String, List<String>>, automatic: Boolean): Picks {
        val members = nodeChoices(config)
        val taken = objects(config.optJSONArray("outbounds")).filterNot { it.optString("type") == "urltest" }.map { it.optString("tag") }.toSet()
        val groups = LinkedHashMap<String, Pair<List<String>, String>>()
        val via = HashMap<Rule, String>()
        for (rule in rules) {
            if (rule.pick.isEmpty() || !Rules.throughNode(rule.action)) continue
            val code = Rules.via(rule.action)
            val nodes = if (code == null) members else through[code] ?: continue
            if (nodes.size < 2) continue
            if (code == null && rule.pick == Policies.FASTEST && automatic) {
                via[rule] = AUTO
                continue
            }
            val tag = if (code == null) (Policies.allTag(rule.pick) ?: continue) else Policies.countryTag(through(code), rule.pick)
            if (tag in taken) continue
            groups.getOrPut(tag) { nodes to rule.pick }
            via[rule] = tag
        }
        return Picks(groups, via)
    }

    /** The picks' groups ([picks]) after the node choices and the countries' groups; gone when no rule picks. */
    private fun setPicks(config: JSONObject, groups: Map<String, Pair<List<String>, String>>) {
        val kept = objects(config.optJSONArray("outbounds")).filterNot { it.optString("type") == "urltest" && Policies.isGroup(it.optString("tag")) }.toMutableList()
        var index = kept.indexOfFirst { it.optString("tag") == PROXY && it.optString("type") == "selector" } + 1
        while (index < kept.size && kept[index].optString("type") == "urltest" && kept[index].optString("tag").let { automatic(it) || throughOf(it) != null }) index++
        kept.addAll(index, groups.map { (tag, group) -> JSONObject(Policies.group(tag, group.first, group.second).toString()) })
        config.put("outbounds", JSONArray(kept))
    }

    /**
     * The Automatic choice ([AUTO]) among the node choices, first and the default, when [on] and
     * there are two nodes or more; and after it one per country with two nodes or more
     * ("Automatic · Japan"), when not every node is in that country. Gone otherwise, with the
     * first node the default again. Which node the person picked is kept by sing-box itself (its
     * cache file), so a pick survives.
     */
    private fun setAutomatic(config: JSONObject, on: Boolean) {
        val outbounds = objects(config.optJSONArray("outbounds"))
        val selector = outbounds.firstOrNull { it.optString("tag") == PROXY && it.optString("type") == "selector" } ?: return
        val members = nodeChoices(config)
        val kept = outbounds.filterNot { it.optString("type") == "urltest" && automatic(it.optString("tag")) }.toMutableList()
        if (on && members.size >= 2) {
            val countries = members.groupBy { tag -> Countries.of(tag) }
                .filter { (country, tags) -> country != null && tags.size >= 2 && tags.size < members.size }
                .map { (country, tags) -> urlTest("$AUTO · ${country!!.en}", tags) }
            val index = kept.indexOf(selector) + 1
            kept.addAll(index, listOf(urlTest(AUTO, members)) + countries)
            selector.put("outbounds", JSONArray(listOf(AUTO) + countries.map { it.getString("tag") } + members)).put("default", AUTO)
        } else {
            selector.put("outbounds", JSONArray(members))
            if (automatic(selector.optString("default")) || selector.optString("default") !in members) selector.put("default", members.firstOrNull())
        }
        config.put("outbounds", JSONArray(kept))
    }

    /**
     * The category lists the rules name ([lists], name to file) and the own lists ([own], address
     * to file, tagged by [RuleProviders.tag]), read from the phone's own copies; the lists no rule
     * names any more are gone. sing-box watches a local list, so a newer copy takes effect without
     * a restart. An own list kept as source JSON says so.
     */
    private fun setCategoryLists(route: JSONObject, lists: Map<String, String>, own: Map<String, String> = emptyMap()) {
        val sets = objects(route.optJSONArray("rule_set")).filterNot {
            it.optString("type") == "local" && it.optString("tag").let { tag -> Rules.isSetName(tag) || RuleProviders.isTag(tag) }
        }.toMutableList()
        lists.forEach { (name, path) -> sets += JSONObject().put("type", "local").put("tag", name).put("format", "binary").put("path", path) }
        own.forEach { (url, path) ->
            val format = if (path.endsWith(".json")) RuleProviders.SOURCE else RuleProviders.BINARY
            sets += JSONObject().put("type", "local").put("tag", RuleProviders.tag(url)).put("format", format).put("path", path)
        }
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
