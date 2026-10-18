package cc.txwd.kone.core

import cc.txwd.kone.console.ConsoleException
import cc.txwd.kone.console.ConsoleFailure
import cc.txwd.kone.console.FakeConsole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class KoneConfigTest {
    private val folder = Files.createTempDirectory("kone-config").toFile().apply { deleteOnExit() }
    private val rules = RuleSets.ensure(File(folder, "rules"))
    private val api = CoreApi(port = 9090, secret = "test-secret")

    private fun build(source: JsonObject, selected: String? = null) = KoneConfig.build(source, rules, api, selected)

    private fun JsonObject.rules(section: String) = this[section]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }

    @Test
    fun theClaudeBlockComesRightAfterSniffAndIsRefusedInDnsToo() {
        val config = build(FakeConsole.source(blockClaude = true)).config
        val routes = config.rules("route")
        assertEquals("sniff", routes[0]["action"]!!.jsonPrimitive.content)
        assertEquals("hijack-dns", routes[1]["action"]!!.jsonPrimitive.content)
        val claude = Json.parseToJsonElement(FakeConsole.CLAUDE_RULE)
        assertEquals(claude, routes[2])
        // Every rule that sends traffic anywhere comes after it.
        assertTrue(routes.drop(3).all { "outbound" in it })
        assertEquals(claude, config.rules("dns")[0])
    }

    @Test
    fun noBlockMeansNothingIsRefused() {
        val config = build(FakeConsole.source(blockClaude = false)).config
        assertTrue(config.rules("route").none { it["action"]?.jsonPrimitive?.content == "reject" })
        assertTrue(config.rules("dns").none { it["action"]?.jsonPrimitive?.content == "reject" })
    }

    @Test
    fun onlyRulesThatRefuseNamesAreCarriedOver() {
        val source = Json.parseToJsonElement(
            """
            {"outbounds": [{"type": "vless", "tag": "\u7f8e\u56fd", "server": "203.0.113.10", "server_port": 443, "uuid": "00000000-0000-0001-0000-000000000001"}],
             "route": {"rules": [
               {"ip_cidr": ["0.0.0.0/0"], "action": "reject"},
               {"domain_suffix": ["claude.ai"], "inbound": "tun-in", "action": "reject"},
               {"domain_suffix": ["claude.ai"], "outbound": "direct"},
               {"domain": [" claude.com ", ""], "domain_suffix": ["anthropic.com"], "action": "reject"}
             ]}}
            """,
        ) as JsonObject
        assertEquals(
            listOf(Json.parseToJsonElement("""{"domain": ["claude.com"], "domain_suffix": ["anthropic.com"], "action": "reject"}""")),
            KoneConfig.refusals(source),
        )
    }

    @Test
    fun nodesKeepTheirNamesAndChainsStayWhole() {
        val source = Json.parseToJsonElement(
            """
            {"outbounds": [
              {"type": "vless", "tag": "proxy", "server": "203.0.113.10", "server_port": 443, "uuid": "00000000-0000-0001-0000-000000000001"},
              {"type": "trojan", "tag": "Japan 2", "server": "203.0.113.11", "server_port": 443, "password": "example", "detour": "relay"},
              {"type": "shadowsocks", "tag": "relay", "server": "203.0.113.12", "server_port": 8388, "method": "aes-128-gcm", "password": "example"},
              {"type": "block", "tag": "block"}
            ]}
            """,
        ) as JsonObject
        val built = build(source, selected = "Japan 2")
        assertEquals(listOf("proxy-2", "Japan 2"), built.nodes.map { it.tag })
        assertEquals(listOf("proxy", "Japan 2"), built.nodes.map { it.name })
        assertEquals("JP", built.nodes[1].country)
        val outbounds = built.config["outbounds"]!!.jsonArray.map { it.jsonObject }
        val selector = outbounds.first()
        assertEquals("Japan 2", selector["default"]!!.jsonPrimitive.content)
        assertEquals(listOf("auto", "proxy-2", "Japan 2"), selector["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content })
        // The relay is kept, but not offered as a node of its own.
        assertEquals("relay", outbounds.first { it["tag"]!!.jsonPrimitive.content == "Japan 2" }["detour"]!!.jsonPrimitive.content)
        assertTrue(outbounds.any { it["tag"]!!.jsonPrimitive.content == "relay" })
    }

    @Test
    fun aSourceWithoutNodesIsRefused() {
        val empty = Json.parseToJsonElement("""{"outbounds": [{"type": "direct", "tag": "direct"}]}""") as JsonObject
        assertEquals(ConsoleFailure.ConfigInvalid, assertFailsWith<ConsoleException> { build(empty) }.failure)
        val broken = Json.parseToJsonElement("""{"outbounds": [{"type": "vless", "tag": "a", "detour": "missing"}]}""") as JsonObject
        assertEquals(ConsoleFailure.ConfigInvalid, assertFailsWith<ConsoleException> { build(broken) }.failure)
    }

    /**
     * The service builds the configuration it runs itself (desktop/service/plan.go); for the
     * same source it must build exactly what the App does, Claude block and all.
     */
    @Test
    fun theServiceBuildsTheSameConfiguration() {
        val found = System.getProperty("kone.service")?.let(::File)?.takeIf { it.canExecute() }
        assumeTrue(found != null, "no kone-service here (-Pkone.service=…, or the workflow's)")
        val sources = listOf(
            FakeConsole.source(blockClaude = true),
            FakeConsole.source(blockClaude = false),
            Json.parseToJsonElement(
                """
                {"outbounds": [
                  {"type": "vless", "tag": "proxy", "server": "203.0.113.10", "server_port": 443, "uuid": "00000000-0000-0001-0000-000000000001"},
                  {"type": "trojan", "tag": "Japan 2", "server": "203.0.113.11", "server_port": 443, "password": "example", "detour": "relay"},
                  {"type": "shadowsocks", "tag": "relay", "server": "203.0.113.12", "server_port": 8388, "method": "aes-128-gcm", "password": "example"},
                  {"type": "trojan", "tag": "\u5e26\u8bc1\u4e66", "server": "203.0.113.13", "tls": {"enabled": true, "certificate_path": "C:/x.pem"}},
                  {"type": "naive", "tag": "naive", "server": "203.0.113.14"}
                ], "route": {"rules": [{"domain_suffix": ["claude.ai"], "action": "reject"}, {"ip_cidr": ["0.0.0.0/0"], "action": "reject"}]}}
                """,
            ) as JsonObject,
        )
        val bypass = listOf("WeChat.exe", "steam.EXE", "wechat.exe", "C:\\x\\cmd.exe", "notes.txt")
        val cases = listOf(Triple("Japan 2", "Rule", emptyList()), Triple("auto", "Global", bypass), Triple("Japan 2", "Everything", bypass))
        val direct = listOf("Example.CN", "example.cn", "com", "https://x.example/", "a.example.org")
        val proxied = listOf("example.com", "*.bad.example", "203.0.113.7")
        // Everything the rules editor could send, and things it should never let through.
        val routes = listOf(
            UserRule("suffix", " Example.COM ", "direct"), UserRule("domain", "*.example.com", "block"), UserRule("keyword", "Ads", "block"),
            UserRule("cidr", "192.168.1.77/24", "direct"), UserRule("cidr", "10.0.0.1", "proxy"), UserRule("cidr", "2001:DB8:0:0:1::5/64", "block"),
            UserRule("cidr", "fe80::/10", "direct"), UserRule("cidr", "::", "block"), UserRule("cidr", "1:2:3:4:5:6:7::", "proxy"),
            UserRule("cidr", "::ffff:102:304", "block"), UserRule("cidr", "010.0.0.1/8", "block"), UserRule("cidr", "fe80::1%eth0", "block"),
            UserRule("cidr", "1::2::3", "block"), UserRule("cidr", "10.0.0.0/0", "block"), UserRule("program", "Steam.exe", "proxy"),
            UserRule("program", "C:\\x\\cmd.exe", "direct"), UserRule("port", "443", "block"), UserRule("port", "1000:2000", "direct"),
            UserRule("port", "2000-1000", "direct"), UserRule("port", "0443", "direct"), UserRule("port", "\u0661\u0662", "direct"),
            UserRule("suffix", "example.org", "reject"), UserRule("geoip", "cn", "direct"), UserRule("suffix", "claude.ai", "direct"),
        )
        for (source in sources) for ((tun, mixed) in listOf(true to 0, false to 0, false to 7890)) for ((selected, mode, straight) in cases) {
            val sites = if (straight.isEmpty()) emptyList() else direct
            val through = if (mode == "Rule") emptyList() else proxied
            val own = if (mode == "Global") emptyList() else routes
            val ads = mode != "Rule"
            // The DNS choices: defaults, presets, custom addresses, and ones the template must refuse.
            val (remote, direct) = when (mode) {
                "Rule" -> "" to ""
                "Global" -> "https://[2001:DB8::53]:853/dns" to "2001:DB8::1"
                else -> "http://evil.example.com" to "dns.example"
            }
            val ipv6 = mode == "Global"
            val ours = KoneConfig.build(
                source, rules, api, selected = selected, tun = tun, mode = mode, bypass = straight, mixed = mixed, direct = sites, proxied = through,
                routes = own, blockAds = ads, remoteDns = remote, directDns = direct, ipv6 = ipv6,
            ).config
            val request = buildJsonObject {
                put("source", source); put("selected", selected); put("rules", rules)
                put("port", api.port); put("secret", api.secret); put("tun", tun)
                put("mode", mode); putJsonArray("bypass") { straight.forEach { add(it) } }; put("mixed", mixed)
                putJsonArray("direct") { sites.forEach { add(it) } }; putJsonArray("proxied") { through.forEach { add(it) } }
                putJsonArray("routes") { own.forEach { add(it.toJson()) } }; put("blockAds", ads)
                put("remoteDns", remote); put("directDns", direct); put("ipv6", ipv6)
            }
            val process = ProcessBuilder(found!!.path, "build-config").start()
            process.outputStream.use { it.write(request.toString().toByteArray()) }
            val output = process.inputStream.readBytes().decodeToString()
            val errors = process.errorStream.readBytes().decodeToString()
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue(), errors)
            assertEquals(ours, Json.parseToJsonElement(output))
        }
    }

    @Test
    fun theCoreAcceptsWhatTheAppBuilds() {
        val found = System.getProperty("kone.singbox")?.let(::File)?.takeIf { it.canExecute() }
        assumeTrue(found != null, "no sing-box binary here (-Pkone.singbox=…, or the workflow's)")
        val core = found!!
        val built = KoneConfig.build(
            FakeConsole.source(blockClaude = true), rules, api, bypass = listOf("WeChat.exe"), mixed = 7890,
            direct = listOf("example.cn"), proxied = listOf("example.com"),
            routes = listOf(
                UserRule("keyword", "tracker", "block"), UserRule("cidr", "2001:db8::/32", "direct"), UserRule("port", "1000-2000", "proxy"),
                UserRule("port", "8443", "block"), UserRule("program", "Steam.exe", "proxy"), UserRule("domain", "www.example.org", "direct"),
            ),
            blockAds = true, remoteDns = "https://dns.example.com:8443/q", directDns = "2001:db8::1", ipv6 = true,
        )
        val file = File(folder, "config.json").apply { writeText(built.config.toString()) }
        val process = ProcessBuilder(core.path, "check", "-c", file.path).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "sing-box check did not finish")
        assertEquals(0, process.exitValue(), output)
    }

    @Test
    fun onlyProgramFileNamesAreSentStraightOutAndAfterTheClaudeBlock() {
        assertEquals(listOf("WeChat.exe", "steam.EXE"), KoneConfig.programs(listOf("WeChat.exe", " steam.EXE ", "wechat.exe", "C:\\Windows\\cmd.exe", "../x.exe", "notes.txt", "a:b.exe", ".exe", "")))
        assertEquals(64, KoneConfig.programs((0 until 100).map { "p$it.exe" }).size)
        val config = KoneConfig.build(FakeConsole.source(blockClaude = true), rules, api, bypass = listOf("WeChat.exe")).config
        val routeRules = (config["route"] as JsonObject)["rules"] as JsonArray
        val block = routeRules.indexOfFirst { (it as JsonObject)["action"]?.jsonPrimitive?.content == "reject" }
        val straight = routeRules.indexOfFirst { "process_name" in (it as JsonObject) }
        assertTrue(block in 0 until straight, "the Claude block comes before the programs sent straight out")
    }

    @Test
    fun sitesArePlainDomainsAfterTheClaudeBlock() {
        assertEquals(listOf("example.cn", "a.b.example.org"), KoneConfig.sites(listOf("Example.CN", " example.cn ", "a.b.example.org", "com", "*.example.com", "https://example.net/", "203.0.113.7", "-bad.example", "")))
        assertEquals(256, KoneConfig.sites((0 until 300).map { "s$it.example" }).size)
        assertEquals("www.example.com", KoneConfig.site("https://WWW.Example.com:8443/a/b?c#d"))
        assertEquals("example.com", KoneConfig.site("*.example.com"))
        assertEquals("example.com", KoneConfig.site(".example.com."))
        assertEquals(null, KoneConfig.site("203.0.113.7"))
        assertEquals(null, KoneConfig.site("localhost"))
        val config = KoneConfig.build(FakeConsole.source(blockClaude = true), rules, api, direct = listOf("claude.ai", "example.cn"), proxied = listOf("example.com")).config
        for (section in listOf("route", "dns")) {
            val rules = config.rules(section)
            val block = rules.indexOfFirst { it["action"]?.jsonPrimitive?.content == "reject" }
            val straight = rules.indexOfFirst { "domain_suffix" in it && "action" !in it && "clash_mode" !in it }
            val through = rules.indexOfFirst { "domain_suffix" in it && it["clash_mode"]?.jsonPrimitive?.content == "Rule" }
            assertTrue(block in 0 until straight && straight < through, "$section: the Claude block, then the sites straight out, then those through the node")
        }
    }

    @Test
    fun rulesAreCheckedAndWrittenAsTheServiceWritesThem() {
        val got = UserRules.clean(
            listOf(
                UserRule("suffix", " Example.COM ", "direct"), UserRule("suffix", "example.com", "direct"), UserRule("domain", "*.example.com", "block"),
                UserRule("keyword", "Ads", "block"), UserRule("keyword", "a", "block"), UserRule("cidr", "192.168.1.77/24", "direct"),
                UserRule("cidr", "10.0.0.1", "proxy"), UserRule("cidr", "2001:DB8:0:0:1::5/64", "block"), UserRule("cidr", "::ffff:102:304", "block"),
                UserRule("cidr", "010.0.0.1/8", "block"), UserRule("cidr", "10.0.0.0/08", "block"), UserRule("cidr", "fe80::1%eth0", "block"),
                UserRule("program", "Steam.exe", "proxy"), UserRule("program", "C:\\x\\cmd.exe", "direct"), UserRule("port", "443", "block"),
                UserRule("port", "1000:2000", "direct"), UserRule("port", "2000-1000", "direct"), UserRule("port", "0443", "direct"),
                UserRule("port", "70000", "direct"), UserRule("suffix", "example.org", "reject"), UserRule("geoip", "cn", "direct"),
            ),
        )
        assertEquals(
            listOf(
                UserRule("suffix", "example.com", "direct"), UserRule("keyword", "ads", "block"), UserRule("cidr", "192.168.1.0/24", "direct"),
                UserRule("cidr", "10.0.0.1/32", "proxy"), UserRule("cidr", "2001:db8::/64", "block"), UserRule("program", "Steam.exe", "proxy"),
                UserRule("port", "443", "block"), UserRule("port", "1000-2000", "direct"),
            ),
            got,
        )
        assertEquals(UserRules.MOST, UserRules.clean((0 until 300).map { UserRule("suffix", "s$it.example", "direct") }).size)
    }

    @Test
    fun dnsChoicesAreCheckedAsTheServiceChecksThem() {
        val remote = mapOf(
            "" to "", "google" to "google", "https://dns.example.com" to "https://dns.example.com:443/dns-query",
            " https://DNS.Example.com:8443/q " to "https://dns.example.com:8443/q", "https://203.0.113.53/dns-query" to "https://203.0.113.53:443/dns-query",
            "https://[2001:DB8::53]:853/dns" to "https://[2001:db8::53]:853/dns", "http://dns.example.com" to "", "https://dns.example.com/a?b=c" to "",
            "https://user@dns.example.com" to "", "https://localhost" to "", "https://dns.example.com:0" to "", "https://[203.0.113.53]" to "", "tls://1.1.1.1" to "",
        )
        remote.forEach { (given, wanted) -> assertEquals(wanted, Dns.remoteChoice(given), "remote $given") }
        val direct = mapOf(
            "" to "", "dnspod" to "dnspod", " 203.0.113.1 " to "203.0.113.1", "2001:DB8::1" to "2001:db8::1", "10.0.0.0/8" to "", "dns.example" to "", "cloudflare" to "",
        )
        direct.forEach { (given, wanted) -> assertEquals(wanted, Dns.directChoice(given), "direct $given") }
        val dns = KoneConfig.build(FakeConsole.source(blockClaude = true), rules, api, remoteDns = "https://dns.example.com:8443/q", directDns = "2001:db8::1", ipv6 = true)
            .config["dns"]!!.jsonObject
        val servers = dns["servers"]!!.jsonArray.map { it.jsonObject }
        assertEquals("2001:db8::1", servers[0]["server"]!!.jsonPrimitive.content)
        assertEquals("dns.example.com", servers[1]["server"]!!.jsonPrimitive.content)
        assertEquals("local-dns", servers[1]["domain_resolver"]!!.jsonPrimitive.content)
        assertEquals("prefer_ipv4", dns["strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun twoNodesOrMoreBringAutoThatPicksTheFastest() {
        val two = KoneConfig.build(FakeConsole.source(blockClaude = true), rules, api, selected = KoneConfig.AUTO_TAG).config
        val outbounds = (two["outbounds"] as JsonArray).map { it as JsonObject }
        val selector = outbounds.first { it["tag"]?.jsonPrimitive?.content == KoneConfig.PROXY_TAG }
        assertEquals(listOf("auto", "United States", "Japan"), (selector["outbounds"] as JsonArray).map { it.jsonPrimitive.content })
        assertEquals("auto", selector["default"]?.jsonPrimitive?.content)
        val auto = outbounds.first { it["tag"]?.jsonPrimitive?.content == KoneConfig.AUTO_TAG }
        assertEquals("urltest", auto["type"]?.jsonPrimitive?.content)
        assertEquals(listOf("United States", "Japan"), (auto["outbounds"] as JsonArray).map { it.jsonPrimitive.content })

        val one = Json.parseToJsonElement("""{"outbounds":[{"type":"trojan","tag":"auto","server":"203.0.113.11","server_port":443,"password":"example"}]}""") as JsonObject
        val single = KoneConfig.build(one, rules, api, selected = KoneConfig.AUTO_TAG)
        assertEquals(listOf("auto-2"), single.nodes.map { it.tag }, "a node named auto moves aside")
        assertTrue((single.config["outbounds"] as JsonArray).none { (it as JsonObject)["type"]?.jsonPrimitive?.content == "urltest" }, "one node has no Auto")
    }
}
