package io.nekohasekai.sfa.selfuse

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class PairedProfileTest {
    private val link = "vless://00000000-0000-0000-0000-000000000001@node.invalid:443?security=reality&sni=example.org&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&type=tcp&flow=xtls-rprx-vision"

    @Test
    fun oneVpnIncludesPersistentPrivateEndpointAndAllModes() {
        val config = JSONObject(PairedProfile.fromVlessLink(link))
        val tun = config.getJSONArray("inbounds").getJSONObject(0)
        assertEquals("tun", tun.getString("type"))
        // The pinned 1.15 core defaults to its Go stack; an explicit stack is deprecated.
        assertEquals(false, tun.has("stack"))
        assertEquals(2, tun.getJSONArray("address").length())
        val home = config.getJSONArray("endpoints").getJSONObject(0)
        assertEquals("tailscale", home.getString("type"))
        assertTrue(home.getBoolean("accept_routes"))
        assertEquals(false, home.getBoolean("on_demand"))
        val rules = config.getJSONObject("route").getJSONArray("rules")
        val homeIndex = (0 until rules.length()).first { rules.getJSONObject(it).optString("outbound") == PairedProfile.HOME_TAG }
        val globalIndex = (0 until rules.length()).first { rules.getJSONObject(it).optString("clash_mode") == "Global" }
        val directIndex = (0 until rules.length()).first { rules.getJSONObject(it).optString("clash_mode") == "Direct" }
        assertTrue(homeIndex < globalIndex && homeIndex < directIndex)
        assertTrue(rules.toString().contains("fd7a:115c:a1e0::/48"))
        assertTrue(rules.toString().contains("\"Rule\""))
        val sets = config.getJSONObject("route").getJSONArray("rule_set")
        assertTrue(sets.getJSONObject(0).has("http_client"))
        assertEquals(false, sets.getJSONObject(0).has("download_detour"))
        PairedProfile.requirePaired(config.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsProxyOnlyConfiguration() {
        PairedProfile.requirePaired("{\"inbounds\":[{\"type\":\"tun\"}]}")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsGlobalRuleThatShadowsHomeRoute() {
        val config = JSONObject(PairedProfile.fromVlessLink(link))
        val rules = config.getJSONObject("route").getJSONArray("rules")
        val reordered = JSONArray().put(JSONObject().put("clash_mode", "Global").put("outbound", "proxy"))
        for (index in 0 until rules.length()) reordered.put(rules.getJSONObject(index))
        config.getJSONObject("route").put("rules", reordered)
        PairedProfile.requirePaired(config.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnencryptedLink() {
        PairedProfile.fromVlessLink(link.replace("security=reality", "security=none"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateSecurityParameters() {
        PairedProfile.fromVlessLink(link + "&security=tls")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnconditionalEarlyProxyRoute() {
        val config = JSONObject(PairedProfile.fromVlessLink(link))
        val original = config.getJSONObject("route").getJSONArray("rules")
        val reordered = JSONArray().put(JSONObject().put("action", "route").put("outbound", "proxy"))
        for (index in 0 until original.length()) reordered.put(original.getJSONObject(index))
        config.getJSONObject("route").put("rules", reordered)
        PairedProfile.requirePaired(config.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTailnetOnlyProfile() {
        val config = JSONObject(PairedProfile.fromVlessLink(link))
        config.put("outbounds", JSONArray().put(JSONObject().put("type", "direct").put("tag", "direct")))
        PairedProfile.requirePaired(config.toString())
    }

    private fun node(config: String) = JSONObject(config).getJSONArray("outbounds")
        .let { outbounds -> (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.first { it.optString("tag") == "node" } }

    @Test
    fun ipv6ServerIsStoredWithoutBrackets() {
        val node = node(PairedProfile.fromVlessLink(link.replace("@node.invalid:443", "@[2001:db8::7]:8443")))
        assertEquals("2001:db8::7", node.getString("server"))
        assertEquals(8443, node.getInt("server_port"))
    }

    @Test
    fun blankFingerprintFallsBackToChrome() {
        val node = node(PairedProfile.fromVlessLink("$link&fp="))
        assertEquals("chrome", node.getJSONObject("tls").getJSONObject("utls").getString("fingerprint"))
    }

    @Test
    fun alpnListIsForwardedToTls() {
        val node = node(PairedProfile.fromVlessLink("$link&alpn=h2%2Chttp%2F1.1"))
        val alpn = node.getJSONObject("tls").getJSONArray("alpn")
        assertEquals(listOf("h2", "http/1.1"), (0 until alpn.length()).map { alpn.getString(it) })
        assertFalse(node(PairedProfile.fromVlessLink(link)).getJSONObject("tls").has("alpn"))
    }

    @Test
    fun malformedEscapeReportsGenericLinkError() {
        val failure = runCatching { PairedProfile.fromVlessLink("$link&sni=%zz") }.exceptionOrNull()
        assertEquals("Invalid connection link", failure?.message)
    }

    @Test
    fun nonObjectRuleIsRejectedAsInvalidFormat() {
        val config = JSONObject(PairedProfile.fromVlessLink(link))
        val original = config.getJSONObject("route").getJSONArray("rules")
        val reordered = JSONArray().put("not-a-rule")
        for (index in 0 until original.length()) reordered.put(original.getJSONObject(index))
        config.getJSONObject("route").put("rules", reordered)
        val failure = runCatching { PairedProfile.requirePaired(config.toString()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals("Invalid configuration format", failure?.message)
    }

    @Test
    fun everyUserFacingFailureHasATranslation() {
        val failures = listOf(
            { PairedProfile.fromVlessLink("vless://bad link") },
            { PairedProfile.fromVlessLink(link.replace("vless://", "vmess://")) },
            { PairedProfile.fromVlessLink(link.replace("00000000-0000-0000-0000-000000000001", "nope")) },
            { PairedProfile.fromVlessLink(link.replace("node.invalid:443", ":443")) },
            { PairedProfile.fromVlessLink("$link&type=tcp") },
            { PairedProfile.fromVlessLink(link.replace("type=tcp", "type=ws")) },
            { PairedProfile.fromVlessLink(link.replace("security=reality", "security=none")) },
            { PairedProfile.fromVlessLink(link.replace(Regex("pbk=[^&]*&"), "")) },
            { PairedProfile.fromVlessLink(link.replace(Regex("pbk=[^&]*"), "pbk=short")) },
            { PairedProfile.fromVlessLink("$link&sid=xyz") },
            { PairedProfile.build(JSONObject().put("type", "direct")) },
            { PairedProfile.requirePaired("not json") },
            { PairedProfile.requirePaired("{}") },
        )
        for (attempt in failures) {
            try {
                attempt()
                fail("Expected a failure")
            } catch (failure: IllegalArgumentException) {
                assertTrue("Untranslated: ${failure.message}", failure.message in KoneErrorMessages.messageToResource.keys)
            }
        }
    }
}
