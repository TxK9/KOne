package io.nekohasekai.sfa.compose.screen.connections

import io.nekohasekai.sfa.compose.model.Connection
import io.nekohasekai.sfa.compose.model.ProcessInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTrafficTest {
    private fun connection(
        id: String,
        app: String? = "org.example.browser",
        uid: Int = 10123,
        domain: String = "",
        destination: String = "192.0.2.10:443",
        down: Long = 0,
        up: Long = 0,
        total: Long = 0,
        outbound: String = "node",
        outboundType: String = "vless",
        rule: String = "",
        closedAt: Long? = null,
        processPath: String = "",
    ) = Connection(
        id = id, inbound = "tun-in", inboundType = "tun", ipVersion = 4, network = "tcp",
        source = "198.51.100.2:40000", destination = destination, domain = domain, displayDestination = destination,
        protocolName = "tls", user = "", fromOutbound = "", createdAt = 0, closedAt = closedAt,
        upload = up, download = down, uploadTotal = 0, downloadTotal = total,
        rule = rule, outbound = outbound, outboundType = outboundType, chain = listOf(outbound),
        processInfo = if (app == null && processPath.isEmpty() && uid < 0) {
            null
        } else {
            ProcessInfo(1, uid, "", processPath, listOfNotNull(app))
        },
    )

    @Test fun groupsByAppThenHostAndRouteAndSumsTraffic() {
        val apps = AppTrafficGrouping.group(
            listOf(
                connection("1", domain = "www.example.com", down = 3000, total = 9000),
                connection("2", domain = "www.example.com", down = 1000, up = 200, total = 1000),
                connection("3", domain = "www.example.com", outbound = "direct", outboundType = "direct", rule = "rule_set=geosite-example"),
                connection("4", app = "org.example.mail", domain = "mail.example.org", total = 50),
            ),
            ownPackage = null,
        )
        assertEquals(listOf("package:org.example.browser", "package:org.example.mail"), apps.map { it.key })
        val browser = apps.first()
        assertEquals(AppKind.Package, browser.kind)
        assertEquals("org.example.browser", browser.name)
        assertEquals(3, browser.count)
        assertEquals(4000L, browser.download)
        assertEquals(200L, browser.upload)
        assertEquals(10_000L, browser.total)
        assertEquals(listOf("1", "2", "3"), browser.ids)
        // One host over two routes stays two rows, so each route is shown honestly.
        assertEquals(2, browser.hosts.size)
        assertEquals(listOf("1", "2"), browser.hosts.first().ids)
        assertEquals("node", browser.hosts.first().outbound)
        assertEquals("direct", browser.hosts.last().outbound)
    }

    @Test fun closedConnectionsAreLeftOut() {
        val apps = AppTrafficGrouping.group(listOf(connection("1", closedAt = 5), connection("2", app = "org.example.mail")), null)
        assertEquals(listOf("package:org.example.mail"), apps.map { it.key })
        assertTrue(AppTrafficGrouping.group(listOf(connection("1", closedAt = 5)), null).isEmpty())
    }

    @Test fun busiestFirstByTierThenTotalThenKey() {
        val apps = AppTrafficGrouping.group(
            listOf(
                connection("a", app = "org.example.a", total = 900_000),
                connection("b", app = "org.example.b", down = 300_000, total = 10),
                connection("c", app = "org.example.c", down = 2_000, total = 10),
                connection("d", app = "org.example.d", down = 500, total = 5_000),
                connection("e", app = "org.example.e", total = 5_000),
            ),
            null,
        )
        // Moving apps lead by speed; below 1 KiB/s an app counts as idle and ranks by what it carried.
        assertEquals(listOf("b", "c", "a", "d", "e").map { "package:org.example.$it" }, apps.map { it.key })
    }

    @Test fun similarSpeedsShareATierSoTotalsKeepTheOrderSteady() {
        assertEquals(AppTrafficGrouping.speedTier(5_000), AppTrafficGrouping.speedTier(12_000))
        val apps = AppTrafficGrouping.group(
            listOf(
                connection("x", app = "org.example.x", down = 12_000, total = 100),
                connection("y", app = "org.example.y", down = 5_000, total = 80_000),
            ),
            null,
        )
        assertEquals("package:org.example.y", apps.first().key)
        assertEquals(0, AppTrafficGrouping.speedTier(1023))
        assertEquals(1, AppTrafficGrouping.speedTier(1024))
        assertTrue(AppTrafficGrouping.speedTier(64L shl 20) > AppTrafficGrouping.speedTier(1L shl 20))
    }

    @Test fun hostsAreSortedTheSameWay() {
        val hosts = AppTrafficGrouping.group(
            listOf(
                connection("1", domain = "a.example.com", total = 10),
                connection("2", domain = "b.example.com", down = 50_000),
                connection("3", domain = "c.example.com", total = 900),
            ),
            null,
        ).single().hosts
        assertEquals(listOf("b.example.com", "c.example.com", "a.example.com"), hosts.map { it.host })
    }

    @Test fun systemProcessAndUnknownTrafficGetTheirOwnGroups() {
        val apps = AppTrafficGrouping.group(
            listOf(
                connection("1", app = "android", uid = 1000),
                connection("2", app = "org.example.work", uid = 1_010_200),
                connection("3", app = null, processPath = "/system/bin/example-daemon"),
                connection("4", app = null, uid = -1),
            ),
            null,
        )
        assertEquals(
            setOf("system", "package:org.example.work", "process:example-daemon", "unknown"),
            apps.map { it.key }.toSet(),
        )
        assertEquals(AppKind.System, apps.first { it.key == "system" }.kind)
        assertEquals("example-daemon", apps.first { it.kind == AppKind.Process }.name)
    }

    @Test fun koneOwnTrafficIsCountedButItsHostsAreWithheld() {
        val apps = AppTrafficGrouping.group(
            listOf(connection("1", app = "org.example.kone", domain = "console.example.net", down = 4096)),
            ownPackage = "org.example.kone",
        )
        val own = apps.single()
        assertEquals(1, own.count)
        assertEquals(4096L, own.download)
        assertEquals(listOf("1"), own.ids)
        assertTrue(own.hosts.isEmpty())
    }

    @Test fun hostIsTheDomainOrTheAddressWithoutItsPort() {
        assertEquals("www.example.com", AppTrafficGrouping.hostOf(connection("1", domain = "www.example.com")))
        assertEquals("192.0.2.10", AppTrafficGrouping.hostOf(connection("1", destination = "192.0.2.10:443")))
        assertEquals("2001:db8::1", AppTrafficGrouping.hostOf(connection("1", destination = "[2001:db8::1]:443")))
        assertEquals("2001:db8::1", AppTrafficGrouping.hostOf(connection("1", destination = "2001:db8::1")))
    }

    @Test fun routeKinds() {
        assertEquals(RouteKind.Direct, RouteLabels.kind("direct", "direct", "home-tailnet"))
        assertEquals(RouteKind.Blocked, RouteLabels.kind("block", "block", "home-tailnet"))
        assertEquals(RouteKind.Home, RouteLabels.kind("home-tailnet", "tailscale", "home-tailnet"))
        assertEquals(RouteKind.Node, RouteLabels.kind("Tokyo 1", "vless", "home-tailnet"))
    }

    @Test fun outboundTagsThatReadAsAddressesAreRecognised() {
        listOf("192.0.2.10", "192.0.2.10:443", "node 203.0.113.7", "2001:db8::1", "[2001:db8::1]:443", "relay.example.net", "relay.example.net:8443")
            .forEach { assertTrue(it, RouteLabels.looksLikeAddress(it)) }
        listOf("Tokyo 1", "Through Japan", "node", "proxy", "Home", "", "Tokyo 1.5 fast")
            .forEach { assertFalse(it, RouteLabels.looksLikeAddress(it)) }
    }

    @Test fun rulesKeepRuleSetNamesAndDropLiteralValues() {
        assertEquals(RouteRule(RuleKind.Default), RouteLabels.rule(""))
        assertEquals(RouteRule(RuleKind.RuleSet, "geosite-example"), RouteLabels.rule("rule_set=geosite-example"))
        assertEquals(RouteRule(RuleKind.RuleSet, "geosite-a, geoip-b"), RouteLabels.rule("rule_set=[geosite-a geoip-b]"))
        assertEquals(RouteRule(RuleKind.RuleSet, "ads"), RouteLabels.rule("ip_cidr=192.0.2.0/24 rule_set=ads"))
        assertEquals(RouteRule(RuleKind.Private), RouteLabels.rule("ip_is_private=true"))
        assertEquals(RouteRule(RuleKind.Domain), RouteLabels.rule("domain_suffix=[.example.com .example.org]"))
        assertEquals(RouteRule(RuleKind.Address), RouteLabels.rule("ip_cidr=[192.0.2.0/24 2001:db8::/32]"))
        assertEquals(RouteRule(RuleKind.Protocol, "quic"), RouteLabels.rule("protocol=quic"))
        assertEquals(RouteRule(RuleKind.Network, "udp"), RouteLabels.rule("network=udp port=443"))
        assertEquals(RouteRule(RuleKind.Mode, "global"), RouteLabels.rule("clash_mode=global"))
        assertEquals(RouteRule(RuleKind.WiFi), RouteLabels.rule("wifi_ssid=[example-ssid]"))
        assertEquals(RouteRule(RuleKind.App), RouteLabels.rule("package_name=org.example.browser"))
        assertEquals(RouteRule(RuleKind.Domain), RouteLabels.rule("!(domain=www.example.com && port=80)"))
        assertEquals(RouteRule(RuleKind.Other), RouteLabels.rule("inbound=tun-in"))
        // A rule set named like a server never reaches the screen.
        assertEquals(RouteRule(RuleKind.Other), RouteLabels.rule("rule_set=relay.example.net"))
    }
}
