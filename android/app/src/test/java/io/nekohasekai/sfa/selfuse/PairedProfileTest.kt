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

    @Test
    fun acceptsBracketedIpv6Server() {
        val config = JSONObject(PairedProfile.fromVlessLink(link.replace("@node.invalid:443", "@[2001:db8::1]:8443")))
        val node = config.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("2001:db8::1", node.getString("server"))
        assertEquals(8443, node.getInt("server_port"))
        assertEquals("example.org", node.getJSONObject("tls").getString("server_name"))
    }

    @Test
    fun ignoresUnencodedDisplayName() {
        val config = JSONObject(PairedProfile.fromVlessLink("$link#Home node 1"))
        assertEquals("node.invalid", config.getJSONArray("outbounds").getJSONObject(1).getString("server"))
    }

    @Test
    fun bracketedIpv6ServerBecomesDefaultServerName() {
        val config = JSONObject(PairedProfile.fromVlessLink(link.replace("node.invalid:443", "[2001:db8::1]:8443").replace("sni=example.org&", "")))
        val node = config.getJSONArray("outbounds").getJSONObject(1)
        assertEquals("2001:db8::1", node.getString("server"))
        assertEquals("2001:db8::1", node.getJSONObject("tls").getString("server_name"))
    }

    @Test
    fun blankFingerprintFallsBackToChrome() {
        val config = JSONObject(PairedProfile.fromVlessLink("$link&fp="))
        val tls = config.getJSONArray("outbounds").getJSONObject(1).getJSONObject("tls")
        assertEquals("chrome", tls.getJSONObject("utls").getString("fingerprint"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonObjectRuleBeforePrivateRoute() {
        val config = JSONObject(PairedProfile.fromVlessLink(link))
        val original = config.getJSONObject("route").getJSONArray("rules")
        val reordered = JSONArray().put("proxy")
        for (index in 0 until original.length()) reordered.put(original.getJSONObject(index))
        config.getJSONObject("route").put("rules", reordered)
        PairedProfile.requirePaired(config.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonObjectConfiguration() {
        PairedProfile.requirePaired("[]")
    }

    @Test
    fun normalizesRealityPublicKeyForCore() {
        // 32 bytes whose Base64 needs '+', '/' and padding in the standard alphabet.
        val bytes = ByteArray(32) { if (it % 2 == 0) 0xfb.toByte() else 0xff.toByte() }
        val standard = java.util.Base64.getEncoder().encodeToString(bytes)
        assertTrue(standard.contains('+') && standard.contains('/') && standard.endsWith("="))
        val config = JSONObject(PairedProfile.fromVlessLink(link.replace("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", standard)))
        val key = config.getJSONArray("outbounds").getJSONObject(1).getJSONObject("tls").getJSONObject("reality").getString("public_key")
        assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes), key)
    }

    @Test
    fun acceptsRawTransportName() {
        val config = JSONObject(PairedProfile.fromVlessLink(link.replace("type=tcp", "type=raw") + "&headerType=none"))
        assertEquals("vless", config.getJSONArray("outbounds").getJSONObject(1).getString("type"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHttpHeaderObfuscation() {
        PairedProfile.fromVlessLink("$link&headerType=http")
    }

    @Test
    fun normalizesFingerprintCase() {
        val config = JSONObject(PairedProfile.fromVlessLink("$link&fp=Firefox"))
        val tls = config.getJSONArray("outbounds").getJSONObject(1).getJSONObject("tls")
        assertEquals("firefox", tls.getJSONObject("utls").getString("fingerprint"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsFingerprintUnknownToCore() {
        PairedProfile.fromVlessLink("$link&fp=randomizednoalpn")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedFlow() {
        PairedProfile.fromVlessLink(link.replace("flow=xtls-rprx-vision", "flow=xtls-rprx-direct"))
    }
}
