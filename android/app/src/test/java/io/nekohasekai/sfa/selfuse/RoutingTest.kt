package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.Rule
import io.nekohasekai.sfa.managed.ManagedConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    private val claudeRule = """{"domain_suffix":["anthropic.com","claude.ai","claude.com","claudeusercontent.com"],"action":"reject"}"""

    private fun managed(home: HomeLink.Home = HomeLink.Home.OFF) = ManagedConfig.build(
        JSONObject("""{"outbounds":[{"type":"vless","tag":"kone-route","server":"n.invalid","server_port":443,
            "uuid":"11111111-1111-4111-8111-111111111111","tls":{"enabled":true,"server_name":"n.invalid"}}],
            "route":{"rules":[$claudeRule],"final":"kone-route"}}"""),
        home, rulesDirectory = "/data/rules",
    )

    private fun rules(config: JSONObject, section: String) =
        config.getJSONObject(section).getJSONArray("rules").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }

    private val choices = Routing.Choices(
        direct = listOf("https://www.Example.cn/path", "example.cn", "not a site"),
        proxied = listOf("example.com", "example.cn"),
        blockAds = true, unmatchedDirect = true, remoteDns = "quad9", directDns = "dnspod", ipv6 = true,
    )

    @Test
    fun theDefaultChoicesLeaveTheTemplateAsItWasBuilt() {
        val built = managed()
        assertTrue(Routing.isTemplate(JSONObject(built)))
        assertEquals(JSONObject(built).toString(2), Routing.apply(built, Routing.Choices()))
    }

    @Test
    fun choicesComeAfterTheClaudeBlockAndBeforeTheModes() {
        val config = JSONObject(Routing.apply(managed(), choices))
        val route = rules(config, "route")
        val claude = route.indexOfFirst { it.optString("action") == "reject" && it.has("domain_suffix") }
        val direct = route.indexOfFirst { it.optString("outbound") == "direct" && it.has("domain_suffix") }
        val proxied = route.indexOfFirst { it.optString("outbound") == PairedProfile.PROXY_TAG && it.has("domain_suffix") }
        val ads = route.indexOfFirst { it.optJSONArray("rule_set")?.optString(0) == Routing.ADS }
        val directMode = route.indexOfFirst { it.optString("clash_mode") == "Direct" }
        assertTrue(claude in 0 until direct && direct < proxied && proxied < ads && ads < directMode)
        // Cleaned, once each, and a site on both lists goes straight out.
        assertEquals("[\"www.example.cn\",\"example.cn\"]", route[direct].getJSONArray("domain_suffix").toString())
        assertEquals("[\"example.com\"]", route[proxied].getJSONArray("domain_suffix").toString())
        assertEquals("Rule", route[proxied].getString("clash_mode"))
        assertEquals("direct", route.last().getString("outbound"))
        // The ad list beside the China lists, read from the same folder before it is first downloaded.
        val sets = config.getJSONObject("route").getJSONArray("rule_set").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }
        assertEquals("/data/rules/${Routing.ADS}.srs", sets.single { it.getString("tag") == Routing.ADS }.getString("initial_path"))

        val dns = config.getJSONObject("dns")
        val answers = rules(config, "dns")
        assertEquals("reject", answers.first().getString("action"))
        assertTrue(answers.any { it.optString("server") == "local-dns" && it.has("domain_suffix") })
        assertEquals(JSONObject().put("clash_mode", "Rule").put("server", "local-dns").toString(), answers.last().toString())
        assertEquals("prefer_ipv4", dns.getString("strategy"))
        val servers = dns.getJSONArray("servers").let { array -> (0 until array.length()).associate { array.getJSONObject(it).getString("tag") to array.getJSONObject(it).getString("server") } }
        assertEquals("119.29.29.29", servers["local-dns"])
        assertEquals("9.9.9.9", servers["proxy-dns"])
    }

    @Test
    fun applyingAgainChangesNothingAndTurningOffUndoesIt() {
        val once = Routing.apply(managed(), choices)
        assertEquals(once, Routing.apply(once, choices))
        assertEquals(JSONObject(managed()).toString(2), Routing.apply(once, Routing.Choices()))
    }

    @Test
    fun homeStaysAheadOfTheChoices() {
        val config = JSONObject(Routing.apply(managed(HomeLink.Home(true, listOf("192.0.2.0/24"), "p")), choices))
        val route = rules(config, "route")
        val home = route.indexOfFirst { it.optString("outbound") == PairedProfile.HOME_TAG }
        val direct = route.indexOfFirst { it.optString("outbound") == "direct" && it.has("domain_suffix") }
        assertTrue(home in 0 until direct)
        assertTrue(rules(config, "dns").takeWhile { it.optString("server") == HomeLink.DNS_TAG }.isNotEmpty())
    }

    @Test
    fun aConfigurationKOneDidNotBuildIsLeftAlone() {
        val other = """{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"rules":[]}}"""
        assertFalse(Routing.isTemplate(JSONObject(other)))
        assertEquals(other, Routing.apply(other, choices))
        assertEquals("not json", Routing.apply("not json", choices))
    }

    @Test
    fun sitesAreReadFromWhatPeopleType() {
        assertEquals("example.com", Routing.site(" HTTPS://user@Example.com:8443/a?b#c "))
        assertEquals("example.com", Routing.site("*.example.com"))
        assertNull(Routing.site("localhost"))
        assertNull(Routing.site("bad name.com"))
    }

    @Test
    fun ownRulesComeInOrderAfterTheSitesAndBeforeAds() {
        val rules = listOf(
            Rule("suffix", "Example.ORG", "direct"), Rule("keyword", "tracker", "block"),
            Rule("cidr", "203.0.113.7/24", "direct"), Rule("port", "1000:2000", "proxy"),
            Rule("app", "com.example.app", "direct"), Rule("port", "0443", "block"),
            Rule("app", "not a package", "direct"), Rule("suffix", "example.org", "direct"),
        )
        val config = JSONObject(Routing.apply(managed(), choices.copy(rules = rules)))
        val route = rules(config, "route")
        val first = route.indexOfFirst { it.optJSONArray("domain_suffix")?.optString(0) == "example.org" }
        val ads = route.indexOfFirst { it.optJSONArray("rule_set")?.optString(0) == Routing.ADS }
        val proxied = route.indexOfFirst { it.optString("outbound") == PairedProfile.PROXY_TAG && it.has("domain_suffix") }
        assertTrue(proxied < first && first < ads)
        // Checked and written one way; the bad ones and the repeat left out.
        val own = route.subList(first, ads).map { it.toString() }
        assertEquals(
            listOf(
                """{"domain_suffix":["example.org"],"outbound":"direct"}""",
                """{"domain_keyword":["tracker"],"action":"reject"}""",
                """{"ip_cidr":["203.0.113.0/24"],"outbound":"direct"}""",
                """{"port_range":["1000:2000"],"clash_mode":"Rule","outbound":"proxy"}""",
                """{"package_name":["com.example.app"],"outbound":"direct"}""",
            ).map { JSONObject(it).toString() },
            own.map { JSONObject(it).toString() },
        )
        // Names are answered where their traffic goes; addresses and ports have no DNS rule.
        val dns = rules(config, "dns")
        assertTrue(dns.any { it.optJSONArray("domain_keyword")?.optString(0) == "tracker" && it.optString("action") == "reject" })
        assertTrue(dns.any { it.optJSONArray("package_name")?.optString(0) == "com.example.app" && it.optString("server") == "local-dns" })
        assertFalse(dns.any { it.has("ip_cidr") || it.has("port_range") })
    }
}
