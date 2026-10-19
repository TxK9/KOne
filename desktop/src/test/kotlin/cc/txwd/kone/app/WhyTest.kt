package cc.txwd.kone.app

import cc.txwd.kone.shared.Why
import cc.txwd.kone.core.ClashApi
import cc.txwd.kone.core.CoreApi
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WhyTest {
    private val lists = Why.Lists(bypass = listOf("WeChat.exe"), direct = listOf("example.cn", "example.net"), proxied = listOf("example.com"))

    @Test
    fun eachRuleOfTheTemplateIsSaidInThePersonsTerms() {
        val cases = mapOf(
            "final" to "Everything else",
            "clash_mode=Rule => route(proxy)" to "Everything else",
            "clash_mode=Global => route(proxy)" to "Global mode",
            "clash_mode=Direct => route(direct)" to "Direct mode",
            "ip_is_private=true => route(direct)" to "Local network",
            "clash_mode=Rule rule_set=[geosite-cn geoip-cn] => route(direct)" to "China list",
            "process_name=sing-box.exe => route(direct)" to "KOne's own test",
            "process_name=WeChat.exe => route(direct)" to "Your direct apps",
            "process_name=Game.exe => route(direct)" to "Your rule",
            "domain_suffix=[example.cn example.net] => route(direct)" to "Your direct sites",
            "clash_mode=Rule domain_suffix=example.com => route(proxy)" to "Your proxied sites",
            "domain_suffix=example.org => route(direct)" to "Your rule",
            "domain_keyword=tracker => route(proxy)" to "Your rule",
            "ip_cidr=203.0.113.0/24 => route(direct)" to "Your rule",
            "rule_set=geosite-netflix => route(proxy)" to "Category Netflix",
            "rule_set=geosite-some-list => route(direct)" to "Category geosite-some-list",
        )
        for ((rule, said) in cases) assertEquals(said, Why.explain(rule, lists), rule)
        assertNull(Why.explain(null, lists))
        assertNull(Why.explain(" ", lists))
    }

    /** With a real sing-box: the rule it reports for a held connection, read back (lists over three names are cut short by the core). */
    @Test
    fun theRuleARealCoreReportsIsReadBack() {
        val core = System.getProperty("kone.singbox")?.let(::File)?.takeIf { it.canExecute() }
        assumeTrue(core != null, "no sing-box binary here (-Pkone.singbox=…, or the workflow's)")
        // A "node" that accepts and holds, so the connection stays in the core's list.
        val hold = ServerSocket(0)
        val held = mutableListOf<Socket>()
        thread(isDaemon = true) { runCatching { while (true) held += hold.accept() } }
        val api = CoreApi(ServerSocket(0).use { it.localPort }, "why")
        val mixed = ServerSocket(0).use { it.localPort }
        val config = """{"log":{"level":"error"},
            "inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":$mixed}],
            "outbounds":[{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":${hold.localPort}},{"type":"direct","tag":"direct"}],
            "route":{"rules":[{"domain_suffix":["example.com","example.org","example.edu","example.cn"],"outbound":"proxy"}],"final":"direct"},
            "experimental":{"clash_api":{"external_controller":"127.0.0.1:${api.port}","secret":"${api.secret}"}}}"""
        val process = ProcessBuilder(core!!.path, "run", "-c", "stdin").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        process.outputStream.use { it.write(config.toByteArray()) }
        val clash = ClashApi(api)
        try {
            repeat(50) { if (clash.answers()) return@repeat; Thread.sleep(100) }
            Socket("127.0.0.1", mixed).use { client ->
                client.getOutputStream().write("CONNECT www.example.cn:443 HTTP/1.1\r\nHost: www.example.cn:443\r\n\r\n".toByteArray())
                var rule: String? = null
                repeat(30) {
                    rule = clash.connections()?.connections?.firstOrNull()?.rule
                    if (rule != null) return@repeat
                    Thread.sleep(100)
                }
                assertEquals("Your proxied sites", Why.explain(rule, Why.Lists(emptyList(), emptyList(), listOf("example.com"))), rule)
                assertEquals("Your rule", Why.explain(rule, Why.Lists(emptyList(), emptyList(), listOf("example.net"))), rule)
            }
        } finally {
            process.destroy()
            process.waitFor(3, TimeUnit.SECONDS)
            held.forEach { it.close() }
            hold.close()
        }
    }
}
