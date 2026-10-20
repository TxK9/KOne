package io.nekohasekai.sfa.managed

import io.nekohasekai.sfa.selfuse.HomeLink
import io.nekohasekai.sfa.selfuse.PairedProfile
import cc.txwd.kone.shared.ShareLinks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManagedConfigTest {
    private fun outbounds(config: JSONObject) = config.getJSONArray("outbounds").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }

    @Test
    fun theConsolesGeneratedRouteBecomesTheKOneNode() {
        // A console-generated route has one kone-route outbound and no Android-specific fields.
        val source = JSONObject("""{"log":{"level":"warn"},"outbounds":[{"type":"vless","tag":"kone-route","server":"n.invalid","server_port":443,
            "uuid":"11111111-1111-4111-8111-111111111111","tls":{"enabled":true,"server_name":"n.invalid"}}],"route":{"final":"kone-route"}}""")
        val config = JSONObject(ManagedConfig.build(source, HomeLink.Home.OFF))
        val tags = outbounds(config).map { it.getString("tag") }
        assertEquals(listOf(PairedProfile.PROXY_TAG, ShareLinks.NODE_TAG, "direct"), tags)
        assertEquals("tun", config.getJSONArray("inbounds").getJSONObject(0).getString("type"))
        assertEquals(PairedProfile.PROXY_TAG, config.getJSONObject("route").getString("final"))
        assertTrue(HomeLink.isKOneConfig(config))
        assertTrue(PairedProfile.nodeKey(outbounds(config)[1]) != null)
    }

    @Test
    fun severalNodesAreOfferedAndChainsStayWhole() {
        val source = JSONObject("""{"outbounds":[
            {"type":"selector","tag":"pick","outbounds":["hk","jp"]},
            {"type":"trojan","tag":"hk","server":"hk.invalid","server_port":443,"password":"p","detour":"relay"},
            {"type":"hysteria2","tag":"relay","server":"r.invalid","server_port":443,"password":"p"},
            {"type":"vmess","tag":"proxy","server":"jp.invalid","server_port":443,"uuid":"11111111-1111-4111-8111-111111111111"},
            {"type":"direct","tag":"direct"},
            {"type":"block","tag":"block"}]}""")
        val config = JSONObject(ManagedConfig.build(source, HomeLink.Home(true, listOf("192.168.1.0/24"), "p")))
        val byTag = outbounds(config).associateBy { it.getString("tag") }
        val selector = byTag.getValue(PairedProfile.PROXY_TAG)
        val choices = (0 until selector.getJSONArray("outbounds").length()).map { selector.getJSONArray("outbounds").getString(it) }
        // Several nodes keep their names, hk first; the source's own "proxy" moves aside; the relay is kept but not offered.
        assertEquals(listOf("hk", "proxy-2"), choices)
        assertEquals("relay", byTag.getValue("hk").getString("detour"))
        assertTrue(HomeLink.isKOneConfig(config))
        assertEquals("hysteria2", byTag.getValue("relay").getString("type"))
        assertFalse(byTag.containsKey("pick"))
        assertFalse(byTag.containsKey("block"))
        PairedProfile.requirePaired(config.toString())
    }

    @Test
    fun aSourceWithoutANodeOrWithABrokenChainIsRefused() {
        listOf(
            """{"outbounds":[{"type":"direct","tag":"demo"}]}""",
            """{"outbounds":[{"type":"trojan","tag":"a","server":"a.invalid","server_port":443,"password":"p","detour":"missing"}]}""",
            """{"outbounds":[{"type":"trojan","tag":"a","server":"a.invalid","server_port":443,"password":"p","detour":"b"},
                {"type":"trojan","tag":"b","server":"b.invalid","server_port":443,"password":"p","detour":"a"}]}""",
            """{"dns":{}}""",
        ).forEach { text ->
            try {
                ManagedConfig.build(JSONObject(text), HomeLink.Home.OFF)
                fail(text)
            } catch (failure: ManagedException) {
                assertEquals(ManagedFailure.ConfigInvalid, failure.failure)
            }
        }
    }

    // What the console puts at the head of route.rules for a customer the owner has kept from Claude.
    private val claude = listOf("anthropic.com", "claude.ai", "claude.com", "claudeusercontent.com")
    private val claudeRule = """{"domain_suffix":["anthropic.com","claude.ai","claude.com","claudeusercontent.com"],"action":"reject"}"""

    private fun source(vararg rules: String) = JSONObject("""{"outbounds":[{"type":"vless","tag":"kone-route","server":"n.invalid","server_port":443,
        "uuid":"11111111-1111-4111-8111-111111111111","tls":{"enabled":true,"server_name":"n.invalid"}}],
        "route":{"rules":[${rules.joinToString(",")}],"final":"kone-route"}}""")

    private fun rules(config: JSONObject, section: String) =
        config.getJSONObject(section).getJSONArray("rules").let { array -> (0 until array.length()).map { array.getJSONObject(it) } }

    private fun names(rule: JSONObject, field: String) = rule.getJSONArray(field).let { array -> (0 until array.length()).map { array.getString(it) } }

    @Test
    fun claudeRefusedByTheConsoleIsRefusedInEveryModeAndInDns() {
        val config = JSONObject(ManagedConfig.build(source(claudeRule, """{"domain_suffix":["example.cn"],"outbound":"direct"}"""), HomeLink.Home.OFF))
        val routes = rules(config, "route")
        // After sniffing (so the name is known) and DNS hijacking, ahead of Direct, Global and Rule.
        assertEquals(listOf("sniff", "hijack-dns", "reject"), routes.take(3).map { it.optString("action") })
        assertEquals(claude, names(routes[2], "domain_suffix"))
        assertEquals("Direct", routes[3].optString("clash_mode"))
        // The source's other rules are not the App's business.
        assertFalse(routes.any { it.has("domain_suffix") && names(it, "domain_suffix") == listOf("example.cn") })
        val dns = rules(config, "dns")
        assertEquals("reject", dns[0].getString("action"))
        assertEquals(claude, names(dns[0], "domain_suffix"))
    }

    @Test
    fun withHomeOnTheRefusalFollowsTheHomeRouteAndTheConfigStillPairs() {
        val config = JSONObject(ManagedConfig.build(source(claudeRule), HomeLink.Home(true, listOf("192.0.2.0/24"), "p")))
        val routes = rules(config, "route")
        assertEquals(PairedProfile.HOME_TAG, routes[2].optString("outbound"))
        assertEquals("reject", routes[3].optString("action"))
        assertEquals(claude, names(routes[3], "domain_suffix"))
        PairedProfile.requirePaired(config.toString())
        assertTrue(rules(config, "dns").any { it.optString("action") == "reject" })
    }

    @Test
    fun onlyRulesThatRefuseNamesAndNothingElseAreKept() {
        val kept = ManagedConfig.refusals(source(
            """{"action":"reject"}""",
            """{"ip_cidr":["0.0.0.0/0"],"action":"reject"}""",
            """{"domain_suffix":["x.example"],"inbound":["tun-in"],"action":"reject"}""",
            """{"domain_suffix":["x.example"],"outbound":"direct"}""",
            """{"domain":["only.example"," "],"action":"reject"}""",
        ))
        assertEquals(1, kept.size)
        assertEquals(listOf("only.example"), names(kept[0], "domain"))
        assertFalse(kept[0].has("domain_suffix"))
        assertEquals("reject", kept[0].getString("action"))
    }

    @Test
    fun withoutARefusalNothingIsAdded() {
        val config = JSONObject(ManagedConfig.build(source(), HomeLink.Home.OFF))
        assertTrue(rules(config, "route").none { it.optString("action") == "reject" })
        assertEquals("Direct", rules(config, "dns")[0].optString("clash_mode"))
        assertEquals(2, ManagedConfig.TEMPLATE)
    }

    @Test
    fun aNodeNamedLikeKOnesOwnGroupIsRenamed() {
        val source = JSONObject("""{"outbounds":[
            {"type":"trojan","tag":"Automatic · Japan","server":"a.invalid","server_port":443,"password":"p"},
            {"type":"trojan","tag":"Tokyo","server":"b.invalid","server_port":443,"password":"p"}]}""")
        val tags = outbounds(JSONObject(ManagedConfig.build(source, HomeLink.Home.OFF))).map { it.getString("tag") }
        assertTrue("Automatic - Japan" in tags)
        assertFalse("Automatic · Japan" in tags)
    }
}
