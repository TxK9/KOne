package io.nekohasekai.sfa.connection

import io.nekohasekai.sfa.selfuse.HomeLink
import io.nekohasekai.sfa.selfuse.PairedProfile
import java.net.InetAddress
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimeConnectionConfigTest {
    private val applicationPackage = "org.example.kone"
    private val directPackage = "org.example.direct"
    private val proxiedPackage = "org.example.proxied"
    private val calling = CallingOptions(enabled = true)
    private fun source(home: Boolean = false) = PairedProfile.fromLink(
        "vless://00000000-0000-0000-0000-000000000001@node.invalid:443?security=tls&type=tcp",
        HomeLink.Home(home, emptyList(), "example"),
    )
    private fun prepare(raw: String = source(), options: CallingOptions = calling, policy: AppPolicySnapshot =
        AppPolicySnapshot.from(true, setOf(directPackage), false, applicationPackage)) =
        JSONObject(RuntimeConnectionConfig.prepare(raw, options, policy))

    @Test fun finalGlobalPolicyKeepsExplicitAppExceptionsForIkeAndGatewayDns() {
        val config = prepare()
        for (port in listOf(500, 4500)) {
            assertEquals("direct", route(config, Flow(directPackage, port = port)))
            assertEquals("proxy", route(config, Flow(proxiedPackage, port = port)))
            assertEquals("proxy", route(config, Flow(null, port = port)))
        }
        for (domain in listOf("epdg.example.invalid", "epdg.epc.mnc001.mcc001.pub.3gppnetwork.org")) {
            assertEquals("local-dns", dns(config, Flow(directPackage, domain = domain)))
            assertEquals("proxy-dns", dns(config, Flow(proxiedPackage, domain = domain)))
            assertEquals("proxy-dns", dns(config, Flow(null, domain = domain)))
        }
        assertEquals("proxy", route(config, Flow(proxiedPackage, network = "tcp", port = 443)))
        assertEquals("proxy-dns", dns(config, Flow(proxiedPackage, domain = "example.invalid")))
    }

    @Test fun inclusionAndDisabledSnapshotsKeepExistingAppSemanticsAndOwnerNeverBecomesDirect() {
        val packages = mutableSetOf(directPackage, applicationPackage)
        val excluded = AppPolicySnapshot.from(true, packages, false, applicationPackage)
        packages.clear()
        assertEquals(setOf(directPackage), excluded.directPackages)
        assertNull(excluded.proxyPackages)
        val included = AppPolicySnapshot.from(true, setOf(proxiedPackage), true, applicationPackage)
        assertEquals(setOf(proxiedPackage, applicationPackage), included.proxyPackages)
        val config = prepare(policy = included)
        assertEquals("proxy", route(config, Flow(applicationPackage)))
        assertEquals("proxy", route(config, Flow(proxiedPackage)))
        assertEquals("direct", route(config, Flow(directPackage)))
        assertEquals("proxy", route(config, Flow(null)))
        val disabled = AppPolicySnapshot.from(false, setOf(directPackage), true, applicationPackage)
        assertEquals(AppPolicySnapshot(), disabled)
        assertEquals("proxy", route(prepare(policy = disabled), Flow(directPackage)))
    }

    @Test fun refusalsStillWinAgainstCallingAndDirectAppsAndHomeNamesRemainPrivate() {
        val raw = JSONObject(source(home = true))
        val refusal = JSONObject().put("domain_suffix", JSONArray().put("blocked.invalid")).put("action", "reject")
        raw.getJSONObject("route").getJSONArray("rules").put(refusal)
        raw.getJSONObject("dns").getJSONArray("rules").put(JSONObject(refusal.toString()))
        val config = prepare(raw.toString())
        val refused = Flow(directPackage, port = 4500, domain = "epdg.blocked.invalid")
        assertEquals("reject", route(config, refused))
        assertEquals("reject", dns(config, refused))
        assertEquals("home-tailnet", route(config, Flow(proxiedPackage, ip = "100.64.12.34", port = 4500)))
        assertEquals("home-dns", dns(config, Flow(proxiedPackage, domain = "machine.example.ts.net")))
        assertEquals("proxy", route(config, Flow(proxiedPackage, ip = "203.0.113.12", port = 4500)))
        assertEquals(raw.getJSONArray("endpoints").toString(), config.getJSONArray("endpoints").toString())
    }

    @Test fun intermediateCallingValidationCannotAcceptAnUnsafeFinalProxyGraph() {
        val raw = JSONObject(source())
        raw.getJSONArray("outbounds").getJSONObject(0).put("outbounds", JSONArray().put("direct")).put("default", "direct")
        assertNotNull(ConnectionCompatibility.apply(raw.toString(), calling))
        try {
            prepare(raw.toString())
            fail("The actual final policy must reject a selector containing direct")
        } catch (_: IllegalArgumentException) {
            // Final runtime validation, unlike compatibility alone, refuses this configuration.
        }
    }

    @Test fun enablingCallingDefaultsPreservesExistingDnsStrategyAndEveryTunMtu() {
        assertEquals(CallingDns.Original, calling.dns)
        assertEquals(0, calling.mtu)
        val raw = JSONObject(source())
        raw.getJSONObject("dns").put("strategy", "prefer_ipv6")
        raw.getJSONArray("inbounds").getJSONObject(0).put("mtu", 1400)
        raw.getJSONArray("inbounds").put(JSONObject().put("type", "tun").put("tag", "second-tun")
            .put("address", JSONArray().put("172.20.0.1/30")))
        val before = raw.toString()
        val result = prepare(before)
        assertEquals("prefer_ipv6", result.getJSONObject("dns").getString("strategy"))
        assertEquals(1400, result.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
        assertFalse(result.getJSONArray("inbounds").getJSONObject(1).has("mtu"))
        assertEquals(before, raw.toString())
    }

    @Test fun explicitlyChosenLegacyDnsAndMtuValuesStillApplyAfterDefaultChange() {
        val result = prepare(options = CallingOptions(true, CallingDns.PreferIpv4, 1280))
        assertEquals("prefer_ipv4", result.getJSONObject("dns").getString("strategy"))
        assertEquals(1280, result.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
        assertEquals("direct", route(result, Flow(directPackage, port = 4500)))
        assertEquals("proxy", route(result, Flow(proxiedPackage, port = 4500)))
    }

    private data class Flow(
        val app: String?,
        val network: String = "udp",
        val port: Int = 4500,
        val domain: String = "",
        val ip: String = "203.0.113.12",
    )

    // A deliberately small matcher for these Global-mode contract examples, not a native-core
    // replacement: fail on unrecognised predicates instead of silently declaring them matched.
    private fun route(config: JSONObject, flow: Flow): String = decide(config.getJSONObject("route"), "outbound", flow)
    private fun dns(config: JSONObject, flow: Flow): String = decide(config.getJSONObject("dns"), "server", flow)
    private fun decide(section: JSONObject, target: String, flow: Flow): String {
        val rules = section.getJSONArray("rules")
        for (index in 0 until rules.length()) {
            val rule = rules.getJSONObject(index)
            if (rule.optString("action") in setOf("sniff", "hijack-dns")) continue
            if (matches(rule, flow)) return if (rule.optString("action") == "reject") "reject" else rule.getString(target)
        }
        return section.getString("final")
    }
    private fun matches(rule: JSONObject, flow: Flow): Boolean {
        val conditions = rule.keys().asSequence().filterNot { it in setOf("action", "server", "outbound", "invert", "type", "mode") }.map { key ->
            val values = strings(rule.opt(key))
            when (key) {
                "rules" -> rule.getJSONArray(key).let { rules ->
                    val matches = (0 until rules.length()).map { matches(rules.getJSONObject(it), flow) }
                    if (rule.getString("mode") == "or") matches.any { it } else matches.all { it }
                }
                "package_name" -> flow.app in values
                "package_name_regex" -> flow.app?.let { app -> values.any { Regex(it).containsMatchIn(app) } } ?: false
                "clash_mode" -> "Global" in values
                "network" -> flow.network in values
                "port" -> flow.port.toString() in values
                "domain" -> flow.domain in values
                "domain_suffix" -> values.any { flow.domain == it || flow.domain.endsWith(".$it") }
                "domain_keyword" -> values.any(flow.domain::contains)
                "ip_cidr" -> values.any { contains(it, flow.ip) }
                "preferred_by" -> false
                else -> error("Unsupported predicate in Global-mode contract: $key")
            }
        }.toList().all { it }
        return if (rule.optBoolean("invert")) !conditions else conditions
    }
    private fun strings(value: Any?): List<String> = when (value) {
        is JSONArray -> (0 until value.length()).map { value.get(it).toString() }
        null, JSONObject.NULL -> emptyList()
        else -> listOf(value.toString())
    }
    private fun contains(prefix: String, ip: String): Boolean {
        val address = InetAddress.getByName(ip).address
        val network = InetAddress.getByName(prefix.substringBefore('/')).address
        val bits = prefix.substringAfter('/').toInt()
        return address.size == network.size && (0 until bits).all { bit ->
            (address[bit / 8].toInt() and (0x80 ushr (bit % 8))) == (network[bit / 8].toInt() and (0x80 ushr (bit % 8)))
        }
    }
}
