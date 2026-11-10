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

    private fun withHomeRule(change: (JSONObject) -> Unit): String {
        val config = JSONObject(PairedProfile.fromVlessLink(link))
        val rules = config.getJSONObject("route").getJSONArray("rules")
        change((0 until rules.length()).map { rules.getJSONObject(it) }.first { it.optString("outbound") == PairedProfile.HOME_TAG })
        return config.toString()
    }

    @Test
    fun explicitRouteActionOnHomeRuleIsAccepted() {
        PairedProfile.requirePaired(withHomeRule { it.put("action", "route").put("invert", false) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvertedHomeRule() {
        // Inverted, the rule would send all non-tailnet traffic to Home and tailnet traffic to the proxy.
        PairedProfile.requirePaired(withHomeRule { it.put("invert", true) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsHomeRuleThatDoesNotRoute() {
        PairedProfile.requirePaired(withHomeRule { it.put("action", "reject") })
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

    @Test
    fun displayNameComesFromTheFragment() {
        assertEquals("US West 01", PairedProfile.displayName("$link#US%20West%2001"))
        assertEquals("Japan · Tokyo", PairedProfile.displayName("$link#Japan · Tokyo"))
        assertEquals("A+B", PairedProfile.displayName("$link#A+B"))
        assertEquals("100% uptime", PairedProfile.displayName("$link#100% uptime"))
        assertEquals("Node2", PairedProfile.displayName("$link#Node%0A2"))
    }

    @Test
    fun displayNameFallsBackAndStaysBounded() {
        assertEquals(PairedProfile.DEFAULT_NAME, PairedProfile.displayName(link))
        assertEquals(PairedProfile.DEFAULT_NAME, PairedProfile.displayName("$link#%20%0A"))
        assertEquals(64, PairedProfile.displayName("$link#" + "x".repeat(200)).length)
        val emoji = PairedProfile.displayName("$link#" + "x".repeat(63) + "🇺🇸")
        assertTrue(!emoji.last().isHighSurrogate())
    }

    @Test
    fun acceptsHostnameWithUnderscore() {
        val node = JSONObject(PairedProfile.fromVlessLink(link.replace("@node.invalid:443", "@edge_1.node.invalid:2053"))).getJSONArray("outbounds").getJSONObject(1)
        assertEquals("edge_1.node.invalid", node.getString("server"))
        assertEquals(2053, node.getInt("server_port"))
        assertEquals("00000000-0000-0000-0000-000000000001", node.getString("uuid"))
        val defaultPort = JSONObject(PairedProfile.fromVlessLink(link.replace("@node.invalid:443", "@edge_1.node.invalid"))).getJSONArray("outbounds").getJSONObject(1)
        assertEquals(443, defaultPort.getInt("server_port"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidPortOnUnderscoreHostname() {
        PairedProfile.fromVlessLink(link.replace("@node.invalid:443", "@edge_1.node.invalid:70000"))
    }

    @Test
    fun rejectsNonCanonicalIdentifier() {
        // java.util.UUID accepts "1-1-1-1-1" and silently rewrites it to a different identity.
        val failure = reasonOf { replaced("00000000-0000-0000-0000-000000000001", "1-1-1-1-1") }
        assertEquals(PairedProfile.Reason.INVALID_IDENTIFIER, failure.reason)
    }

    @Test
    fun acceptsUppercaseIdentifierAndNormalizesIt() {
        val node = JSONObject(replaced("00000000-0000-0000-0000-000000000001", "0000000A-0000-0000-0000-00000000000B"))
            .getJSONArray("outbounds").getJSONObject(1)
        assertEquals("0000000a-0000-0000-0000-00000000000b", node.getString("uuid"))
    }

    private fun reasonOf(block: () -> Unit): PairedProfileException =
        try {
            block()
            throw AssertionError("expected a validation failure")
        } catch (failure: PairedProfileException) {
            failure
        }

    private fun replaced(from: String, to: String) = PairedProfile.fromVlessLink(link.replace(from, to))

    @Test
    fun failuresCarryStableReasonsForTranslation() {
        val cases = mapOf(
            PairedProfile.Reason.INVALID_LINK to { PairedProfile.fromVlessLink("vless://bad host") },
            PairedProfile.Reason.NOT_VLESS to { PairedProfile.fromVlessLink(link.replace("vless://", "trojan://")) },
            PairedProfile.Reason.INVALID_IDENTIFIER to { replaced("00000000-0000-0000-0000-000000000001", "x") },
            PairedProfile.Reason.DUPLICATE_PARAMETER to { PairedProfile.fromVlessLink("$link&sni=b.example") },
            PairedProfile.Reason.UNSUPPORTED_TRANSPORT to { PairedProfile.fromVlessLink(link.replace("type=tcp", "type=grpc")) },
            PairedProfile.Reason.UNENCRYPTED to { PairedProfile.fromVlessLink(link.replace("security=reality", "security=none")) },
            PairedProfile.Reason.MISSING_REALITY_KEY to { PairedProfile.fromVlessLink(link.replace("pbk=", "pbx=")) },
            PairedProfile.Reason.INVALID_REALITY_KEY to { PairedProfile.fromVlessLink(link.replace("pbk=A", "pbk=")) },
            PairedProfile.Reason.INVALID_SHORT_ID to { PairedProfile.fromVlessLink("$link&sid=abc") },
            PairedProfile.Reason.UNSUPPORTED_FLOW to { replaced("flow=xtls-rprx-vision", "flow=xtls-rprx-direct") },
            PairedProfile.Reason.UNSUPPORTED_ENCRYPTION to { PairedProfile.fromVlessLink("$link&encryption=mlkem768x25519plus.native.0rtt.abc") },
            PairedProfile.Reason.UNSUPPORTED_FINGERPRINT to { PairedProfile.fromVlessLink("$link&fp=unknown") },
            PairedProfile.Reason.INVALID_SERVER to { PairedProfile.fromVlessLink(link.replace("@node.invalid:443", "@edge_1!:443")) },
            PairedProfile.Reason.INVALID_PORT to { replaced("@node.invalid:443", "@edge_1.node.invalid:70000") },
            PairedProfile.Reason.INVALID_CONFIGURATION to { PairedProfile.requirePaired("[]") },
            PairedProfile.Reason.TAILSCALE_MISSING to { PairedProfile.requirePaired("{}") },
            PairedProfile.Reason.NO_PRIVATE_RULE to { PairedProfile.requirePaired(withHomeRule { it.put("invert", true) }) },
        )
        for ((reason, block) in cases) {
            assertEquals(reason.name, reason, reasonOf { block() }.reason)
        }
    }

    @Test
    fun malformedPercentEscapeIsALinkErrorNotADecoderMessage() {
        val failure = reasonOf { PairedProfile.fromVlessLink("$link&spx=%zz") }
        assertEquals(PairedProfile.Reason.INVALID_LINK, failure.reason)
        assertEquals("Invalid connection link", failure.message)
    }

    @Test
    fun detailIsBoundedToOneShortLine() {
        val failure = reasonOf { PairedProfile.fromVlessLink(link.replace("flow=xtls-rprx-vision", "flow=" + "a%0A".repeat(40))) }
        assertEquals(PairedProfile.Reason.UNSUPPORTED_FLOW, failure.reason)
        assertEquals("a".repeat(32), failure.detail)
        assertEquals("Unsupported VLESS flow: " + "a".repeat(32), failure.message)
    }

    @Test
    fun plainEncryptionIsAccepted() {
        for (value in listOf("none", "")) {
            val node = JSONObject(PairedProfile.fromVlessLink("$link&encryption=$value")).getJSONArray("outbounds").getJSONObject(1)
            assertEquals(false, node.has("encryption"))
        }
    }

    @Test
    fun alpnBecomesATlsList() {
        val tls = JSONObject(PairedProfile.fromVlessLink("$link&alpn=h2%2C%20http%2F1.1,h2")).getJSONArray("outbounds")
            .getJSONObject(1).getJSONObject("tls")
        assertEquals(JSONArray(listOf("h2", "http/1.1")).toString(), tls.getJSONArray("alpn").toString())
        val noAlpn = JSONObject(PairedProfile.fromVlessLink("$link&alpn=")).getJSONArray("outbounds").getJSONObject(1).getJSONObject("tls")
        assertEquals(false, noAlpn.has("alpn"))
    }
}
