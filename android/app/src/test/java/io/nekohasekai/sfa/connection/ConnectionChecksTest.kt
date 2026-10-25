package io.nekohasekai.sfa.connection

import io.nekohasekai.sfa.selfuse.HomeLink
import io.nekohasekai.sfa.selfuse.PairedProfile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ConnectionChecksTest {
    private fun source(home: Boolean = false) = PairedProfile.fromLink(
        "vless://00000000-0000-0000-0000-000000000001@node.invalid:443?security=tls&type=tcp",
        HomeLink.Home(home, listOf("192.168.1.0/24"), "example"),
    )
    private fun runtime(direct: Set<String> = emptySet(), include: Set<String>? = null, calling: Boolean = false, home: Boolean = false): String =
        GlobalRoutingPolicy.apply(ConnectionCompatibility.apply(source(home), CallingOptions(enabled = calling)), directPackages = direct, proxyPackages = include)
    private fun input(content: String = runtime()): ConnectionCheckInput {
        val proxy = JSONObject(content).getJSONArray("outbounds").getJSONObject(0)
        val selected = proxy.getJSONArray("outbounds").getString(0)
        return ConnectionCheckInput(
            local = true, started = true, commandConnected = true, mode = ConnectionCheckMode.Global,
            ownership = VpnOwnership.Ours, vpnNetwork = 11, underlyingNetwork = 22,
            runtimeEpoch = 7, runtimeNetwork = 22, runtimeConfig = content, selections = mapOf("proxy" to selected),
            protection = VpnProtectionSnapshot(VpnProtectionReadability.Available, true, true),
        )
    }
    private fun check(value: ConnectionCheckInput, id: ConnectionCheckId) = ConnectionChecks.inspect(value).items.single { it.id == id }
    private fun mutated(content: String = runtime(), mutation: (JSONObject) -> Unit): ConnectionCheckInput =
        JSONObject(content).also(mutation).toString().let(::input)

    @Test fun acceptedRuntimeReportsConfigurationWithoutClaimingRealExit() {
        val result = ConnectionChecks.inspect(input())
        for (id in listOf(ConnectionCheckId.Vpn, ConnectionCheckId.Mode, ConnectionCheckId.Capture, ConnectionCheckId.Dns)) {
            assertEquals(ConnectionCheckLevel.Configured, result.items.single { it.id == id }.level)
        }
        assertTrue(check(input(), ConnectionCheckId.Capture).detail.contains("untested"))
        assertTrue(check(input(), ConnectionCheckId.Calling).detail.contains("unverified"))
    }

    @Test fun stoppedRemoteUnknownOwnerAndUnsettledNetworkNeverKeepConfirmedResults() {
        val ready = input()
        val cases = listOf(
            ready.copy(local = false), ready.copy(started = false), ready.copy(commandConnected = false),
            ready.copy(mode = ConnectionCheckMode.Unknown), ready.copy(ownership = VpnOwnership.Unknown),
            ready.copy(ownership = VpnOwnership.Other), ready.copy(vpnNetwork = null),
            ready.copy(underlyingNetwork = 0), ready.copy(runtimeNetwork = 23), ready.copy(runtimeConfig = null),
            ready.copy(runtimeEpoch = null), ready.copy(selectedProfileMatches = false),
        )
        cases.forEach { candidate -> assertTrue(ConnectionChecks.inspect(candidate).items.all { it.level == ConnectionCheckLevel.Unknown }) }
        assertTrue(ConnectionChecks.changed().items.all { it.level == ConnectionCheckLevel.Unknown })
    }

    @Test fun ruleAndDirectAreNotGlobalEvenThoughTheConfigContainsGlobalRules() {
        for (mode in listOf(ConnectionCheckMode.Rule, ConnectionCheckMode.Direct)) {
            val value = input().copy(mode = mode)
            assertEquals(ConnectionCheckLevel.Attention, check(value, ConnectionCheckId.Mode).level)
            assertEquals(ConnectionCheckLevel.Unknown, check(value, ConnectionCheckId.Dns).level)
        }
        assertEquals(ConnectionCheckMode.Unknown, ConnectionCheckMode.from("sensitive-mode.invalid"))
    }

    @Test fun malformedConfigsAndUntrustedErrorsNeverReachTheDisplayBoundary() {
        val secret = "sensitive-host.invalid|secret-token|https://subscription.invalid"
        val result = ConnectionChecks.inspect(input().copy(runtimeConfig = "not-json-$secret"))
        assertTrue(result.items.all { it.level == ConnectionCheckLevel.Unknown })
        assertFalse(result.toString().contains(secret))
        assertFalse(ConnectionChecks.inspect(input()).toString().contains("node.invalid"))
        assertFalse(ConnectionChecks.inspect(input()).toString().contains("proxy-dns"))
    }

    @Test fun doubleStackNeedsBothFamiliesStrictRoutingAndNoOperatingSystemBypass() {
        val missingV6 = mutated { it.getJSONArray("inbounds").getJSONObject(0).put("address", JSONArray().put("172.19.0.1/30")) }
        val malformedV6 = mutated { it.getJSONArray("inbounds").getJSONObject(0).put("address", JSONArray().put("172.19.0.1/30").put("::::/64")) }
        val routeOff = mutated { it.getJSONArray("inbounds").getJSONObject(0).put("strict_route", false) }
        val bypass = mutated { it.getJSONArray("inbounds").getJSONObject(0).put("exclude_uid", JSONArray().put(10001)) }
        for (candidate in listOf(missingV6, malformedV6, routeOff, bypass)) {
            assertEquals(ConnectionCheckLevel.Attention, check(candidate, ConnectionCheckId.Capture).level)
        }
    }

    @Test fun dnsRequiresHijackProxyDetourAndIndependentNamedNodeBootstrap() {
        val wrongDetour = mutated { it.getJSONObject("dns").getJSONArray("servers").getJSONObject(1).put("detour", "direct") }
        val noHijack = mutated {
            val route = it.getJSONObject("route"); val rules = route.getJSONArray("rules")
            route.put("rules", JSONArray((0 until rules.length()).map(rules::getJSONObject).filterNot { r -> r.optString("action") == "hijack-dns" }))
        }
        val loop = mutated { it.getJSONArray("outbounds").getJSONObject(1).put("domain_resolver", "proxy-dns") }
        val disabled = mutated { it.getJSONArray("inbounds").getJSONObject(0).put("dns_mode", "disabled") }
        for (candidate in listOf(wrongDetour, noHijack, loop, disabled)) assertEquals(ConnectionCheckLevel.Unknown, check(candidate, ConnectionCheckId.Dns).level)
        assertEquals(ConnectionCheckLevel.Configured, check(input(), ConnectionCheckId.Dns).level)
    }

    @Test fun actualCoreExceptionsAreCountedOnceWithoutClaimingTheirSource() {
        val original = JSONObject(source())
        original.getJSONArray("inbounds").getJSONObject(0).put("exclude_package", JSONArray().put("org.example.one"))
        val accepted = GlobalRoutingPolicy.apply(original.toString(), directPackages = setOf("org.example.one", "org.example.two"))
        val result = check(input(accepted), ConnectionCheckId.Exceptions)
        assertEquals(2, result.count)
        assertFalse(result.detail.contains("Import"))
        assertFalse(result.toString().contains("org.example"))
        assertEquals(ConnectionCheckLevel.Configured, check(input(accepted), ConnectionCheckId.Dns).level)
    }

    @Test fun includeOnlyMeansAnUnboundedComplementRatherThanZeroDirectApps() {
        val result = check(input(runtime(include = setOf("org.example.allowed"))), ConnectionCheckId.Exceptions)
        assertNull(result.count)
        assertTrue(result.detail.contains("Only selected apps use the proxy"))
    }

    @Test fun emptyIncludeOnlyListMeansKnownAppsMayDirectAndNeverZeroExceptions() {
        val value = input(runtime(include = emptySet(), calling = true))
        val result = check(value, ConnectionCheckId.Exceptions)
        assertEquals(ConnectionCheckLevel.Information, result.level)
        assertNull(result.count)
        assertTrue(result.detail.contains("recognized apps to connect directly"))
        assertFalse(result.detail.contains("No explicit"))
        assertEquals(ConnectionCheckLevel.Configured, check(value, ConnectionCheckId.Dns).level)
        assertTrue(check(value, ConnectionCheckId.Calling).detail.contains("Direct-app"))
    }

    @Test fun unfamiliarOrPublicDirectRulesBeforeGlobalAreNotSilentlyApproved() {
        val candidate = mutated {
            val route = it.getJSONObject("route"); val rules = route.getJSONArray("rules")
            route.put("rules", JSONArray().put(JSONObject().put("ip_cidr", JSONArray().put("203.0.113.0/24")).put("outbound", "direct")).also { list ->
                (0 until rules.length()).forEach { index -> list.put(rules.getJSONObject(index)) }
            })
        }
        assertEquals(ConnectionCheckLevel.Unknown, check(candidate, ConnectionCheckId.Exceptions).level)
        assertEquals(ConnectionCheckLevel.Unknown, check(candidate, ConnectionCheckId.Mode).level)
        assertEquals(ConnectionCheckLevel.Unknown, check(candidate, ConnectionCheckId.Dns).level)
    }

    @Test fun applicationDnsMustMatchTheRuntimeDirectPredicates() {
        val candidate = mutated(runtime(direct = setOf("org.example.direct"))) {
            val dns = it.getJSONObject("dns"); val rules = dns.getJSONArray("rules")
            dns.put("rules", JSONArray((0 until rules.length()).map(rules::getJSONObject).filterNot { r -> r.has("package_name") }))
        }
        assertEquals(ConnectionCheckLevel.Unknown, check(candidate, ConnectionCheckId.Dns).level)
    }

    @Test fun callingSettingsDoNotOverrideDirectExceptionsOrClaimRegistration() {
        val value = input(runtime(direct = setOf("org.example.direct"), calling = true))
        val calling = check(value, ConnectionCheckId.Calling)
        assertEquals(ConnectionCheckLevel.Information, calling.level)
        assertTrue(calling.detail.contains("Direct-app"))
        assertTrue(calling.detail.contains("unverified"))
        assertFalse(calling.detail.contains("1280"))
        assertEquals(ConnectionCheckLevel.Attention, check(value.copy(mode = ConnectionCheckMode.Direct), ConnectionCheckId.Calling).level)
    }

    @Test fun callingCannotBeApprovedForTcpOnlyOrUnconfirmedSelectedProxy() {
        val tcpOnly = mutated(runtime(calling = true)) { it.getJSONArray("outbounds").getJSONObject(1).put("network", "tcp") }
        assertEquals(ConnectionCheckLevel.Unknown, check(tcpOnly, ConnectionCheckId.Calling).level)
        val value = input(runtime(calling = true)).copy(selections = emptyMap())
        assertEquals(ConnectionCheckLevel.Unknown, check(value, ConnectionCheckId.Calling).level)
        assertEquals(ConnectionCheckLevel.Unknown, check(value, ConnectionCheckId.Mode).level)
    }

    @Test fun unusedTcpOnlyBackupDoesNotRestrictTheSelectedUdpNode() {
        val value = mutated(runtime(calling = true)) {
            val nodes = it.getJSONArray("outbounds")
            nodes.getJSONObject(1).put("network", "tcp,udp")
            nodes.put(JSONObject(nodes.getJSONObject(1).toString()).put("tag", "backup").put("network", "tcp"))
            nodes.getJSONObject(0).getJSONArray("outbounds").put("backup")
        }
        assertEquals(ConnectionCheckLevel.Information, check(value, ConnectionCheckId.Calling).level)
        assertEquals(ConnectionCheckLevel.Unknown, check(value.copy(selections = mapOf("proxy" to "backup")), ConnectionCheckId.Calling).level)
        assertEquals(ConnectionCheckLevel.Unknown, check(value.copy(selections = emptyMap()), ConnectionCheckId.Calling).level)
        assertEquals(ConnectionCheckLevel.Unknown, check(value.copy(selections = mapOf("proxy" to "missing")), ConnectionCheckId.Calling).level)
    }

    @Test fun selectedNestedChainIncludesItsDetourButNotAnUnusedPool() {
        val value = mutated(runtime(calling = true)) {
            val nodes = it.getJSONArray("outbounds"); val node = nodes.getJSONObject(1)
            val leaf = node.getString("tag")
            node.put("network", "tcp,udp").put("detour", "transport")
            nodes.put(JSONObject(node.toString()).put("tag", "transport").put("network", "tcp").also { copy -> copy.remove("detour") })
            nodes.put(JSONObject().put("type", "selector").put("tag", "active-pool").put("outbounds", JSONArray().put(leaf)))
            nodes.put(JSONObject().put("type", "selector").put("tag", "unused-pool").put("outbounds", JSONArray().put("transport")))
            nodes.getJSONObject(0).put("outbounds", JSONArray().put("active-pool").put("unused-pool")).put("default", "active-pool")
        }.let { it.copy(selections = it.selections + ("proxy" to "active-pool") + ("active-pool" to JSONObject(it.runtimeConfig!!).getJSONArray("outbounds").getJSONObject(1).getString("tag"))) }
        // The unused pool has no reported choice; only the actual selected chain needs one.
        assertEquals(ConnectionCheckLevel.Configured, check(value, ConnectionCheckId.Mode).level)
        assertEquals(ConnectionCheckLevel.Unknown, check(value, ConnectionCheckId.Calling).level)
        val withoutRestriction = JSONObject(value.runtimeConfig!!).also { config ->
            val nodes = config.getJSONArray("outbounds")
            (0 until nodes.length()).map(nodes::getJSONObject).first { it.optString("tag") == "transport" }.put("network", "tcp,udp")
        }.toString()
        assertEquals(ConnectionCheckLevel.Information, check(value.copy(runtimeConfig = withoutRestriction), ConnectionCheckId.Calling).level)
        assertEquals(ConnectionCheckLevel.Unknown, check(value.copy(selections = mapOf("proxy" to "active-pool")), ConnectionCheckId.Calling).level)
    }

    @Test fun unspecifiedNetworkIsNotReportedAsDynamicallyVerifiedUdp() {
        val calling = check(input(runtime(calling = true)), ConnectionCheckId.Calling)
        assertEquals(ConnectionCheckLevel.Information, calling.level)
        assertTrue(calling.detail.contains("UDP availability"))
        assertTrue(calling.detail.contains("unverified"))
        assertFalse(calling.detail.contains("UDP available"))
    }

    @Test fun anUnselectedUnsafeChoiceStillPreventsGraphConfirmation() {
        val value = mutated(runtime(calling = true)) { config ->
            config.getJSONArray("outbounds").getJSONObject(0).getJSONArray("outbounds").put("direct")
        }
        assertEquals(ConnectionCheckLevel.Unknown, check(value, ConnectionCheckId.Mode).level)
        assertEquals(ConnectionCheckLevel.Unknown, check(value, ConnectionCheckId.Calling).level)
    }

    @Test fun privateHomeAndBootstrapHaveExplicitSeparatePurposes() {
        val value = input(runtime(home = true))
        assertEquals(ConnectionCheckLevel.Configured, check(value, ConnectionCheckId.Mode).level)
        val dns = check(value, ConnectionCheckId.Dns)
        assertEquals(ConnectionCheckLevel.Configured, dns.level)
        assertTrue(dns.detail.contains("bootstrap resolution"))
        assertTrue(dns.detail.contains("Home domains"))
    }

    @Test fun systemFlagsAreIndependentAndUnsupportedIsNotDisabled() {
        val value = input().copy(protection = VpnProtectionSnapshot(VpnProtectionReadability.Available, true, false))
        assertEquals(ConnectionCheckLevel.Configured, check(value, ConnectionCheckId.AlwaysOn).level)
        assertEquals(ConnectionCheckLevel.Attention, check(value, ConnectionCheckId.Lockdown).level)
        for (readability in listOf(VpnProtectionReadability.Unsupported, VpnProtectionReadability.Unavailable)) {
            val unknown = value.copy(protection = VpnProtectionSnapshot(readability))
            assertEquals(ConnectionCheckLevel.Unknown, check(unknown, ConnectionCheckId.AlwaysOn).level)
            assertEquals(ConnectionCheckLevel.Unknown, check(unknown, ConnectionCheckId.Lockdown).level)
        }
    }
}
