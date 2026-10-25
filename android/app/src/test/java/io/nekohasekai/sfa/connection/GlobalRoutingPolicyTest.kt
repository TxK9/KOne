package io.nekohasekai.sfa.connection

import io.nekohasekai.sfa.selfuse.HomeLink
import io.nekohasekai.sfa.selfuse.PairedProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GlobalRoutingPolicyTest {
    private fun source(home: Boolean = false) = PairedProfile.fromLink(
        "vless://00000000-0000-0000-0000-000000000001@node.invalid:443?security=tls&type=tcp",
        HomeLink.Home(home, listOf("192.168.1.0/24", "203.0.113.0/24"), "phone"),
    )
    private fun rules(config: JSONObject) = config.getJSONObject("route").getJSONArray("rules").let { a -> (0 until a.length()).map(a::getJSONObject) }
    private fun dnsRules(config: JSONObject) = config.getJSONObject("dns").getJSONArray("rules").let { a -> (0 until a.length()).map(a::getJSONObject) }
    private fun applied(content: String = source(), direct: Set<String> = emptySet(), include: Set<String>? = null) =
        JSONObject(GlobalRoutingPolicy.apply(content, directPackages = direct, proxyPackages = include))
    private fun refuses(change: (JSONObject) -> Unit) {
        val raw = JSONObject(source()); change(raw)
        try { applied(raw.toString()); fail("Unsafe proxy graph accepted") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {}
    }

    @Test fun disabledIsByteForByteUnchangedAndEnabledNeverWritesSource() {
        val original = source()
        assertEquals(original, GlobalRoutingPolicy.apply(original, strict = false))
        assertEquals("not-json", GlobalRoutingPolicy.apply("not-json", strict = false))
        applied(original)
        assertEquals(source(), original)
    }

    @Test fun globalPrecedesPublicDirectAndChinaRulesWhileOtherModesRemainAvailable() {
        val config = applied(); val r = rules(config)
        val global = r.indexOfFirst { it.optString("clash_mode") == "Global" }
        assertEquals(1, r.count { it.optString("clash_mode") == "Global" })
        assertEquals("proxy", r[global].getString("outbound"))
        assertTrue(r.indexOfFirst { it.optString("clash_mode") == "Direct" } > global)
        assertTrue(r.indexOfFirst { it.has("rule_set") } > global)
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        assertTrue(config.getJSONObject("route").getJSONArray("rule_set").length() > 0)
    }

    @Test fun everyTunCapturesBothFamiliesAndRemovesOperatingSystemBypasses() {
        val raw = JSONObject(source()); val tuns = raw.getJSONArray("inbounds")
        tuns.getJSONObject(0).put("auto_route", false).put("address", JSONArray().put("172.20.0.1/30"))
            .put("include_package", JSONArray().put("org.example.selected"))
            .put("exclude_uid", JSONArray().put(10001)).put("include_interface", JSONArray().put("wlan0"))
            .put("route_address", JSONArray().put("1.1.1.1/32")).put("route_exclude_address", JSONArray().put("::/0"))
            .put("route_address_set", JSONArray().put("geoip-cn")).put("dns_mode", "disabled")
        tuns.put(JSONObject().put("type", "tun").put("tag", "second").put("address", JSONArray().put("fd00::1/126")))
        val normalized = applied(raw.toString()).getJSONArray("inbounds")
        for (i in 0 until normalized.length()) {
            val tun = normalized.getJSONObject(i)
            assertTrue(tun.getBoolean("auto_route")); assertTrue(tun.getBoolean("strict_route"))
            assertTrue(tun.getJSONArray("address").toString().contains(':'))
            assertTrue(tun.getJSONArray("address").toString().contains('.'))
            assertFalse(tun.keys().asSequence().any { it.startsWith("include_") || it.startsWith("exclude_") || it.startsWith("route_address") || it.startsWith("route_exclude_address") })
        }
        assertEquals("hijack", normalized.getJSONObject(0).getString("dns_mode"))
    }

    @Test fun explicitlyExemptAppsAreDirectInsideCoreAndAllOthersReachGlobalProxy() {
        val config = applied(direct = setOf("org.example.exempt")); val r = rules(config)
        val exempt = r.indexOfFirst { it.has("package_name") }
        val global = r.indexOfFirst { it.optString("clash_mode") == "Global" }
        assertTrue(exempt in 0 until global)
        assertEquals("direct", r[exempt].getString("outbound"))
        assertEquals("[\"org.example.exempt\"]", r[exempt].getJSONArray("package_name").toString())
        assertTrue(config.getJSONObject("route").getBoolean("find_process"))
        assertEquals("local-dns", dnsRules(config).first { it.has("package_name") }.getString("server"))
        val direct = config.getJSONArray("outbounds").let { a -> (0 until a.length()).map(a::getJSONObject).first { it.optString("type") == "direct" } }
        assertEquals("local-dns", direct.getString("domain_resolver"))
    }

    @Test fun includeOnlyInverseCannotExemptAnUnknownProcess() {
        val config = applied(include = setOf("org.example.selected"))
        val rule = rules(config).first { it.optString("type") == "logical" }
        assertEquals("and", rule.getString("mode"))
        val terms = rule.getJSONArray("rules")
        assertEquals(".+", terms.getJSONObject(0).getJSONArray("package_name_regex").getString(0))
        assertTrue(terms.getJSONObject(1).getBoolean("invert"))
        assertEquals("org.example.selected", terms.getJSONObject(1).getJSONArray("package_name").getString(0))
        assertEquals("proxy", rules(config).first { it.optString("clash_mode") == "Global" }.getString("outbound"))
    }

    @Test fun sourcePackageExemptionsMoveToCoreWithoutBeingLost() {
        val raw = JSONObject(source())
        raw.getJSONArray("inbounds").getJSONObject(0).put("exclude_package", JSONArray().put("org.example.profile"))
        val config = applied(raw.toString(), direct = setOf("org.example.setting"))
        val packages = rules(config).first { it.has("package_name") }.getJSONArray("package_name")
        assertEquals(setOf("org.example.profile", "org.example.setting"), (0 until packages.length()).map(packages::getString).toSet())
        assertEquals("android", rules(applied(direct = setOf("android"))).first { it.has("package_name") }.getJSONArray("package_name").getString(0))
    }

    @Test fun managedRefusalsStayAheadOfAppExceptionsAndGlobalIncludingDns() {
        val raw = JSONObject(source())
        raw.getJSONObject("route").getJSONArray("rules").put(JSONObject().put("action", "reject").put("domain_suffix", JSONArray().put("blocked.invalid")))
        val config = applied(raw.toString(), direct = setOf("org.example.exempt"))
        for (r in listOf(rules(config), dnsRules(config))) {
            assertTrue(r.indexOfFirst { it.optString("action") == "reject" } < r.indexOfFirst { it.has("package_name") })
            assertTrue(r.indexOfFirst { it.optString("action") == "reject" } < r.indexOfFirst { it.optString("clash_mode") == "Global" })
        }
    }

    @Test fun homeKeepsPrivateRangesAndInternalNamesButPublicSubnetCannotBypassGlobal() {
        val config = applied(source(home = true)); val r = rules(config)
        val global = r.indexOfFirst { it.optString("clash_mode") == "Global" }
        val homeRules = r.take(global).filter { it.optString("outbound") == "home-tailnet" }
        assertTrue(homeRules.any { it.toString().contains("192.168.1.0/24") && it.toString().contains("100.64.0.0/10") })
        assertFalse(homeRules.any { it.toString().contains("203.0.113.0/24") })
        assertTrue(r.drop(global + 1).any { it.optString("outbound") == "home-tailnet" && it.toString().contains("203.0.113.0/24") })
        val d = dnsRules(config); val g = d.indexOfFirst { it.optString("clash_mode") == "Global" }
        assertTrue(d.take(g).any { it.optString("server") == "home-dns" && it.toString().contains("ts.net") })
    }

    @Test fun ordinaryGlobalDnsUsesProxyWhileNamedNodeBootstrapStaysExplicit() {
        val config = applied(); val d = dnsRules(config)
        assertEquals("proxy-dns", d.first { it.optString("clash_mode") == "Global" }.getString("server"))
        assertTrue(d.indexOfFirst { it.optString("clash_mode") == "Global" } < d.indexOfFirst { it.has("rule_set") })
        val node = config.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("local-dns", node.getString("domain_resolver"))
        assertEquals("proxy-dns", config.getJSONObject("route").getString("default_domain_resolver"))
        assertTrue(config.getJSONArray("outbounds").getJSONObject(0).getBoolean("interrupt_exist_connections"))
    }

    @Test fun directAndBlockChoicesAndMissingOrCyclicDetoursAreRejected() {
        for (type in listOf("direct", "block")) refuses { raw ->
            raw.getJSONArray("outbounds").put(JSONObject().put("type", type).put("tag", "unsafe"))
            raw.getJSONArray("outbounds").getJSONObject(0).getJSONArray("outbounds").put("unsafe")
        }
        refuses { it.getJSONArray("outbounds").getJSONObject(1).put("detour", "missing") }
        refuses { it.getJSONArray("outbounds").getJSONObject(1).put("detour", "proxy") }
        refuses { it.getJSONArray("outbounds").getJSONObject(1).put("detour", "direct") }
        refuses { it.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).put("detour", "direct") }
    }

    @Test fun nestedUrltestAndTransportDetoursAreValidatedAndPreserved() {
        val raw = JSONObject(source()); val list = raw.getJSONArray("outbounds")
        val nodeTag = list.getJSONObject(1).getString("tag")
        list.put(JSONObject().put("type", "urltest").put("tag", "pool").put("outbounds", JSONArray().put(nodeTag)))
        list.getJSONObject(0).put("outbounds", JSONArray().put("pool")).put("default", "pool")
        val config = applied(raw.toString())
        assertEquals("urltest", config.getJSONArray("outbounds").getJSONObject(3).getString("type"))
        refuses { r ->
            r.getJSONArray("outbounds").getJSONObject(0).put("outbounds", JSONArray().put("pool")).put("default", "pool")
            r.getJSONArray("outbounds").put(JSONObject().put("type", "urltest").put("tag", "pool").put("outbounds", JSONArray().put("direct")))
        }
    }
}
