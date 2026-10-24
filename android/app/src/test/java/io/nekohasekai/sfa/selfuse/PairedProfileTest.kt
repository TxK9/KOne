package io.nekohasekai.sfa.selfuse

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
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

    @Test
    fun ipv6ServerIsWrittenWithoutBrackets() {
        val config = JSONObject(PairedProfile.fromVlessLink(link.replace("node.invalid", "[2001:db8::1]")))
        val node = config.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("2001:db8::1", node.getString("server"))
        assertEquals(443, node.getInt("server_port"))
    }

    @Test
    fun emptyFingerprintFallsBackToChrome() {
        val config = JSONObject(PairedProfile.fromVlessLink("$link&fp="))
        val utls = config.getJSONArray("outbounds").getJSONObject(1).getJSONObject("tls").getJSONObject("utls")
        assertEquals("chrome", utls.getString("fingerprint"))
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
}
