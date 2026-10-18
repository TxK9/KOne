package cc.txwd.kone.core

import cc.txwd.kone.console.ConsoleException
import cc.txwd.kone.console.ConsoleFailure
import cc.txwd.kone.console.FakeConsole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        assertEquals(listOf("proxy-2", "Japan 2"), selector["outbounds"]!!.jsonArray.map { it.jsonPrimitive.content })
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
        for (source in sources) for (tun in listOf(true, false)) {
            val ours = KoneConfig.build(source, rules, api, selected = "Japan 2", tun = tun).config
            val request = buildJsonObject {
                put("source", source); put("selected", "Japan 2"); put("rules", rules)
                put("port", api.port); put("secret", api.secret); put("tun", tun)
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
        val file = File(folder, "config.json").apply { writeText(build(FakeConsole.source(blockClaude = true)).config.toString()) }
        val process = ProcessBuilder(core.path, "check", "-c", file.path).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "sing-box check did not finish")
        assertEquals(0, process.exitValue(), output)
    }
}
