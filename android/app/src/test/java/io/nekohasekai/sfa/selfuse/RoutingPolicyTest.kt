package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.Policies
import cc.txwd.kone.shared.Rule
import cc.txwd.kone.shared.RuleProviders
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Picks, own lists and sending a host from the Status tab, applied to what the service runs. */
class RoutingPolicyTest {
    private val three = SubscriptionProfile.build(
        listOf(
            "trojan://example-password@node1.invalid:443?sni=example.org#Tokyo 1",
            "trojan://example-password@node2.invalid:443?sni=example.org#Osaka",
            "trojan://example-password@node3.invalid:443?sni=example.org#Frankfurt",
        ).joinToString("\n"),
        HomeLink.Home.OFF,
    )

    private val picked = Routing.Choices(
        rules = listOf(
            Rule("suffix", "example.com", "proxy", Policies.FASTEST),
            Rule("suffix", "example.net", "proxy", Policies.FALLBACK),
            Rule("suffix", "example.org", "via:JP", Policies.FALLBACK),
            Rule("suffix", "example.edu", "via:JP"),
            Rule("suffix", "example.info", "proxy"),
        ),
    )

    private fun objects(array: JSONArray?) = if (array == null) emptyList() else (0 until array.length()).map { array.getJSONObject(it) }
    private fun strings(array: JSONArray?) = if (array == null) emptyList() else (0 until array.length()).map { array.getString(it) }
    private fun outbounds(config: JSONObject) = objects(config.getJSONArray("outbounds"))
    private fun route(config: JSONObject) = objects(config.getJSONObject("route").getJSONArray("rules"))
    private fun outboundOf(config: JSONObject, suffix: String) = route(config).single { it.optJSONArray("domain_suffix")?.optString(0) == suffix }.getString("outbound")

    /**
     * The shape sing-box's check (Libbox.checkConfig) holds outbounds to, as far as the template
     * uses it: tags unique; a selector or URL test names outbounds that exist, and a selector's
     * default is among them; a URL test's interval within its idle timeout (30 minutes), a
     * tolerance that fits 16 bits with a 15-second delay added, an HTTPS test address; every
     * route rule and DNS detour names an outbound that exists, and every rule set a rule names is
     * declared.
     */
    private fun assertRunnable(config: JSONObject) {
        val all = outbounds(config)
        val tags = all.map { it.getString("tag") }
        assertEquals("tags are unique", tags.size, tags.toSet().size)
        for (outbound in all) {
            when (outbound.getString("type")) {
                "selector", "urltest" -> {
                    val members = strings(outbound.getJSONArray("outbounds"))
                    assertTrue(outbound.getString("tag"), members.isNotEmpty() && members.all { it in tags })
                    if (outbound.getString("type") == "selector" && outbound.has("default")) assertTrue(outbound.getString("default") in members)
                    if (outbound.getString("type") == "urltest") {
                        val minutes = outbound.getString("interval").removeSuffix("m").toInt()
                        assertTrue(minutes in 1..30)
                        assertTrue(outbound.getInt("tolerance") in 0..(65535 - 15000))
                        assertTrue(outbound.getString("url").startsWith("https://"))
                    }
                }
            }
        }
        val sets = objects(config.getJSONObject("route").optJSONArray("rule_set")).map { it.getString("tag") }.toSet()
        for (rule in route(config)) {
            if (rule.has("outbound")) assertTrue(rule.getString("outbound"), rule.getString("outbound") in tags + "direct")
            strings(rule.optJSONArray("rule_set")).forEach { assertTrue(it, it in sets) }
        }
        for (server in objects(config.getJSONObject("dns").getJSONArray("servers"))) {
            if (server.has("detour")) assertTrue(server.getString("detour") in tags)
        }
    }

    @Test
    fun eachPickRunsAsAGroupOfItsOwn() {
        val config = JSONObject(Routing.apply(three, picked))
        assertRunnable(config)
        val groups = outbounds(config).associateBy { it.getString("tag") }
        val fastest = groups.getValue(Policies.FASTEST_TAG)
        assertEquals("urltest", fastest.getString("type"))
        assertEquals(listOf("Tokyo 1", "Osaka", "Frankfurt"), strings(fastest.getJSONArray("outbounds")))
        assertEquals(50, fastest.getInt("tolerance"))
        val first = groups.getValue(Policies.FALLBACK_TAG)
        assertEquals(Policies.FALLBACK_TOLERANCE, first.getInt("tolerance"))
        assertEquals(listOf("Tokyo 1", "Osaka", "Frankfurt"), strings(first.getJSONArray("outbounds")))
        val japanFirst = groups.getValue("Through Japan · first that answers")
        assertEquals(listOf("Tokyo 1", "Osaka"), strings(japanFirst.getJSONArray("outbounds")))
        assertEquals(Policies.FALLBACK_TOLERANCE, japanFirst.getInt("tolerance"))

        assertEquals(Policies.FASTEST_TAG, outboundOf(config, "example.com"))
        assertEquals(Policies.FALLBACK_TAG, outboundOf(config, "example.net"))
        assertEquals("Through Japan · first that answers", outboundOf(config, "example.org"))
        assertEquals("Through Japan", outboundOf(config, "example.edu"))
        assertEquals(PairedProfile.PROXY_TAG, outboundOf(config, "example.info"))
        // The saved nodes and the node choices are untouched: the groups are only the rules'.
        val selector = groups.getValue(PairedProfile.PROXY_TAG)
        assertEquals(listOf("Tokyo 1", "Osaka", "Frankfurt"), strings(selector.getJSONArray("outbounds")))
        // Applied again, nothing moves or doubles; without picks, the template is as built.
        assertEquals(config.toString(2), Routing.apply(config.toString(), picked))
        assertEquals(JSONObject(three).toString(2), Routing.apply(config.toString(), Routing.Choices()))
    }

    @Test
    fun theFastestOfEveryNodeIsAutomaticWhenThatIsOn() {
        val config = JSONObject(Routing.apply(three, picked.copy(automatic = true)))
        assertRunnable(config)
        assertEquals(Routing.AUTO, outboundOf(config, "example.com"))
        assertNull(outbounds(config).firstOrNull { it.getString("tag") == Policies.FASTEST_TAG })
        assertEquals(config.toString(2), Routing.apply(config.toString(), picked.copy(automatic = true)))
        assertEquals(JSONObject(three).toString(2), Routing.apply(config.toString(), Routing.Choices()))
    }

    @Test
    fun withOneNodeThereIsNothingToPick() {
        val one = PairedProfile.fromLink("trojan://example-password@node1.invalid:443?sni=example.org#Tokyo", HomeLink.Home.OFF)
        val config = JSONObject(Routing.apply(one, picked))
        assertRunnable(config)
        assertEquals(PairedProfile.PROXY_TAG, outboundOf(config, "example.com"))
        assertEquals(PairedProfile.PROXY_TAG, outboundOf(config, "example.net"))
        // A one-link profile's node is not named by its country: the country's rules go through it.
        assertEquals(PairedProfile.PROXY_TAG, outboundOf(config, "example.org"))
        assertFalse(outbounds(config).any { Policies.isGroup(it.getString("tag")) })
        assertEquals(1, Routing.nodeCount(one))
        assertEquals(3, Routing.nodeCount(three))
    }

    @Test
    fun aPickIsKeptOnlyWhereItMeansSomething() {
        val rules = OwnRules.clean(
            listOf(
                Rule("suffix", "example.com", "direct", Policies.FASTEST),
                Rule("suffix", "example.net", "via:JP", Policies.FASTEST),
                Rule("suffix", "example.org", "proxy", "balance"),
                Rule("suffix", "example.edu", "proxy", Policies.FALLBACK),
            ),
        )
        assertEquals(listOf("", "", "", Policies.FALLBACK), rules.map { it.pick })
        // Kept and read back as saved.
        assertEquals(rules, rules.map { OwnRules.fromJson(OwnRules.toJson(it)) })
        assertFalse(OwnRules.toJson(rules[0]).has("pick"))
    }

    @Test
    fun anOwnListWaitsForItsCopyAndIsReadInItsFormat() {
        val url = "https://lists.example.com/rules/Video.yaml"
        val choices = Routing.Choices(rules = listOf(Rule("url", url, "proxy", Policies.FASTEST), Rule("suffix", "example.com", "direct")))
        // Not kept yet: its rule is left out, and the rest applies.
        val waiting = JSONObject(Routing.apply(three, choices))
        assertRunnable(waiting)
        assertFalse(route(waiting).any { it.has("rule_set") && strings(it.getJSONArray("rule_set")).contains(RuleProviders.tag(url)) })
        val path = "/data/own-lists/${RuleProviders.tag(url)}.json"
        val config = JSONObject(Routing.apply(three, choices, mapOf(url to path)))
        assertRunnable(config)
        val set = objects(config.getJSONObject("route").getJSONArray("rule_set")).single { it.getString("tag") == RuleProviders.tag(url) }
        assertEquals("local", set.getString("type"))
        assertEquals("source", set.getString("format"))
        assertEquals(path, set.getString("path"))
        val rule = route(config).single { strings(it.optJSONArray("rule_set")).contains(RuleProviders.tag(url)) }
        assertEquals(Policies.FASTEST_TAG, rule.getString("outbound"))
        // An own list may hold addresses, so it does not steer DNS.
        assertFalse(objects(config.getJSONObject("dns").getJSONArray("rules")).any { strings(it.optJSONArray("rule_set")).contains(RuleProviders.tag(url)) })
        // A binary copy is read as one; without the rule, the list is gone.
        val binary = JSONObject(Routing.apply(three, choices, mapOf(url to "/data/own-lists/x.srs")))
        assertEquals("binary", objects(binary.getJSONObject("route").getJSONArray("rule_set")).single { it.getString("tag") == RuleProviders.tag(url) }.getString("format"))
        assertEquals(JSONObject(three).toString(2), Routing.apply(config.toString(), Routing.Choices()))
    }

    @Test
    fun aHostSentFromTheStatusTabWinsAndTheRestGoesAsBefore() {
        val choices = Routing.Choices(
            direct = listOf("example.org"),
            proxied = listOf("example.com", "www.example.com"),
            rules = listOf(Rule("suffix", "video.example.com", "proxy", Policies.FASTEST), Rule("suffix", "example.net", "block")),
        )
        val sent = Routing.send(choices, "video.example.com", direct = true)!!
        // First among the own rules, replacing the one on the same site; the list entry it is under moves just after it.
        assertEquals(
            listOf(Rule("suffix", "video.example.com", "direct"), Rule("suffix", "example.com", "proxy"), Rule("suffix", "example.net", "block")),
            sent.rules,
        )
        assertEquals(listOf("example.org"), sent.direct)
        assertEquals(listOf("www.example.com"), sent.proxied)
        val config = JSONObject(Routing.apply(three, sent))
        assertRunnable(config)
        assertEquals("direct", outboundOf(config, "video.example.com"))

        // Through the node: a site straight out by a list it is under moves the same way.
        val back = Routing.send(choices, "www.example.org", direct = false)!!
        assertEquals(listOf(Rule("suffix", "www.example.org", "proxy"), Rule("suffix", "example.org", "direct")), back.rules.take(2))
        assertEquals(emptyList<String>(), back.direct)

        // An address is a rule on that one address; anything else is no rule.
        assertEquals(Rule("cidr", "192.0.2.10/32", "direct"), Routing.send(Routing.Choices(), "192.0.2.10", direct = true)!!.rules.single())
        assertEquals(Rule("cidr", "2001:db8::1/128", "proxy"), Routing.send(Routing.Choices(), "2001:db8::1", direct = false)!!.rules.single())
        assertNull(Routing.send(Routing.Choices(), "not a host", direct = true))
    }

    @Test
    fun aClashProfilesProvidersAndGroupsComeOver() {
        val profile = """
            proxy-groups:
              - {name: Streaming, type: url-test, proxies: [Tokyo 1, Osaka]}
              - {name: Backup, type: fallback, proxies: [Osaka, Tokyo 1]}
            rule-providers:
              video: {type: http, behavior: domain, url: "https://lists.example.com/rules/Video.yaml", interval: 86400}
              plain: {type: http, behavior: domain, url: "http://lists.example.com/rules/Plain.yaml"}
            rules:
              - RULE-SET,video,Streaming
              - RULE-SET,plain,Streaming
              - DOMAIN-SUFFIX,example.com,Backup
              - MATCH,Streaming
        """.trimIndent()
        val imported = Routing.importRules(Routing.Choices(), profile)
        assertEquals(
            listOf(Rule("url", "https://lists.example.com/rules/Video.yaml", "proxy", Policies.FASTEST), Rule("suffix", "example.com", "proxy", Policies.FALLBACK)),
            imported.choices.rules,
        )
        // A provider over plain HTTP is not taken.
        assertNotNull(imported.said)
        assertTrue(imported.said.contains("1 left out"))
    }
}
