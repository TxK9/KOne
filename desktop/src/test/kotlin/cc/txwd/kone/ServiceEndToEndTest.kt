package cc.txwd.kone

import cc.txwd.kone.app.Account
import cc.txwd.kone.shared.CategoryLists
import cc.txwd.kone.app.SystemProxy
import cc.txwd.kone.app.BindingStore
import cc.txwd.kone.app.Vault
import cc.txwd.kone.console.ClaimLink
import cc.txwd.kone.console.ConsoleApi
import cc.txwd.kone.console.FakeConsole
import cc.txwd.kone.core.ClashApi
import cc.txwd.kone.core.CoreApi
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.LocalCore
import cc.txwd.kone.core.ServiceLink
import cc.txwd.kone.core.ServiceStatus
import cc.txwd.kone.core.UserRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.URI
import java.net.UnknownHostException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.security.Security
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * KOne end to end on a real Windows machine, through the installed KOne service: only on the
 * workflow's runner, after it has installed the MSI (-Pkone.e2e=reject, then =full once it has
 * set AllowAnyClient). A local sing-box plays the proxy server, FakeConsole the console; the
 * connection is a real TUN, so this checks what the person will get: the key claims, traffic
 * goes through the node, the Claude block holds in DNS and in TLS, other sites work, and the
 * connection ends when asked and when the App goes away.
 */
class ServiceEndToEndTest {
    private val mode = System.getProperty("kone.e2e")
    private val installed = File(System.getenv("ProgramFiles") ?: "C:\\Program Files", "KOne\\core")
    private val service = File(installed, "kone-service.exe")
    private val http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(10)).build()

    @Test
    fun aProgramOtherThanKOneIsTurnedAway() {
        assumeTrue(mode == "reject", "runs on the Windows runner before AllowAnyClient is set")
        val link = assertNotNull(ServiceLink.open(service), "the service serves the pipe")
        link.use { assertNull(it.hello(), "the test JVM is not KOne.exe and must be refused") }
    }

    @Test
    fun keyConnectionClaudeBlockAndDisconnection() {
        assumeTrue(mode == "full", "runs on the Windows runner after the MSI is installed")
        // No address caching in this JVM: each lookup below must ask the network as it is then.
        Security.setProperty("networkaddress.cache.ttl", "0")
        Security.setProperty("networkaddress.cache.negative.ttl", "0")
        val folder = Files.createTempDirectory("kone-e2e").toFile()

        // A proxy server on this machine, its own traffic bound to the real network card so the
        // tunnel does not catch it, logging the name of every site it carries (sniffed, as the
        // node only gets addresses).
        val nic = command("powershell", "-NoProfile", "-Command",
            "(Get-NetRoute -DestinationPrefix '0.0.0.0/0' | Sort-Object RouteMetric | Select-Object -First 1).InterfaceAlias").trim()
        assertTrue(nic.isNotEmpty(), "no default route")
        val port = ServerSocket(0).use { it.localPort }
        val serverLog = File(folder, "server.log")
        val serverConfig = File(folder, "server.json").apply {
            writeText(
                """{"log":{"level":"debug"},
                   "inbounds":[{"type":"shadowsocks","listen":"127.0.0.1","listen_port":$port,"method":"aes-128-gcm","password":"kone-e2e"}],
                   "outbounds":[{"type":"direct","bind_interface":${JsonPrimitive(nic)}}],
                   "route":{"rules":[{"action":"sniff"}]}}""",
            )
        }
        val singBox = File(installed, "sing-box.exe")
        val server = ProcessBuilder(singBox.path, "run", "-c", serverConfig.path).redirectErrorStream(true)
            .redirectOutput(serverLog).start()
        try {
            waitFor("the proxy server") { runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess }

            // Before connecting: claude.ai's address, for the TLS check later.
            val claudeAddress = InetAddress.getByName("claude.ai")

            // The key: claimed from a console like the release publisher's, with the Claude block for this person.
            val node = """{"type":"shadowsocks","tag":"United States","server":"127.0.0.1","server_port":$port,"method":"aes-128-gcm","password":"kone-e2e"}"""
            val blocked = Json.parseToJsonElement("""{"outbounds":[$node],"route":{"rules":[${FakeConsole.CLAUDE_RULE}]}}""") as JsonObject
            val open = Json.parseToJsonElement("""{"outbounds":[$node],"route":{"rules":[]}}""") as JsonObject
            FakeConsole(blocked).use { console ->
                console.invites += "e2e-invite-0001"
                val account = Account(BindingStore(File(folder, "app"), Vault.forThisComputer()), "KOne · e2e", api = { ConsoleApi(it, tls = console.tls) })
                account.claim(assertNotNull(ClaimLink.read(console.link("e2e-invite-0001"), null)))
                val source = assertNotNull(account.binding?.source, "the key brought the nodes")

                // Connect: no prompt, the service does it.
                val link = assertNotNull(ServiceLink.open(service))
                assertNotNull(link.hello())

                // A category list, made here with the installed sing-box: the service refuses junk,
                // keeps a real list in rules\sets\, and a rule naming it is applied after the Claude block.
                val listSource = File(folder, "list.json").apply { writeText("""{"version": 3, "rules": [{"domain_suffix": ["example.org"]}]}""") }
                val listFile = File(folder, "geosite-kone-e2e.srs")
                command(File(service.parentFile, "sing-box.exe").path, "rule-set", "compile", "--output", listFile.path, listSource.path)
                val list = listFile.readBytes()
                val junk = "SRS\u0001not a rule set".toByteArray()
                assertFalse(link.putRuleSet("geosite-kone-e2e", junk, CategoryLists.sha256(junk)), "a list sing-box cannot read is refused")
                assertFalse(link.putRuleSet("geosite-kone-e2e", list, CategoryLists.sha256(junk)), "a list that is not what was sent is refused")
                assertFalse(link.putRuleSet("..\\geosite-kone-e2e", list, CategoryLists.sha256(list)), "only list names are taken")
                assertTrue(link.putRuleSet("geosite-kone-e2e", list, CategoryLists.sha256(list)), "the service keeps a real list")
                assertEquals(CategoryLists.sha256(list), link.ruleSets()?.get("geosite-kone-e2e"))
                val routes = listOf(UserRule("set", "geosite-kone-e2e", "block"), UserRule("set", "geosite-never-handed-over", "block"))
                assertNull(link.connect(source, null, routes = routes), "the service started the core")
                val running = waitForRunning(link)
                assertTrue(tunnelUp(), "the KOne adapter is there")

                // Traffic goes through the node.
                flushDns()
                assertEquals(200, get("https://www.example.com/"))
                waitFor("the server to carry example.com") { "example.com" in serverLog.readText() }
                val clash = ClashApi(assertNotNull(running.api))
                assertTrue((clash.delay("United States") ?: 0) > 0, "the node answers its delay test")

                // The Claude block: names are refused in DNS…
                flushDns()
                for (name in listOf("api.anthropic.com", "www.claude.com", "claudeusercontent.com")) {
                    assertFailsWith<UnknownHostException>("$name must not resolve") { InetAddress.getByName(name) }
                }
                // …and a connection made by address is refused once its TLS names claude.ai.
                assertFailsWith<IOException>("TLS to claude.ai must be refused") { tls(claudeAddress, "claude.ai") }
                assertFalse("claude.ai" in serverLog.readText() || "anthropic.com" in serverLog.readText(), "nothing of Claude reached the node")
                // The category rule: example.org is refused, example.com still goes through; the list never handed over is simply left out.
                flushDns()
                assertFailsWith<UnknownHostException>("example.org is in the blocked list") { InetAddress.getByName("www.example.org") }
                assertEquals(200, get("https://www.example.com/", fresh = true))

                // Disconnect: the core stops, the adapter goes, the network works directly.
                link.disconnect()
                waitForStopped(link)
                link.close()
                waitFor("the adapter to go") { !tunnelUp() }
                val carried = serverLog.readText().length
                flushDns()
                assertEquals(200, get("https://www.example.org/"))
                Thread.sleep(1000)
                assertFalse("example.org" in serverLog.readText().substring(carried), "after disconnecting nothing goes through the node")

                // KOne going away ends the connection: close the session without a word.
                val second = assertNotNull(ServiceLink.open(service))
                assertNull(second.connect(source, null))
                waitForRunning(second)
                assertTrue(tunnelUp())
                second.close()
                waitFor("the core to stop when its session ends") { !tunnelUp() }

                // The core dying as if it crashed is reported, so KOne connects again.
                val crashed = assertNotNull(ServiceLink.open(service))
                assertNull(crashed.connect(source, null))
                waitForRunning(crashed)
                // The core is the service's own sing-box.exe child (the test's proxy server is this JVM's).
                val serviceProcess = ProcessHandle.allProcesses().filter { it.info().command().orElse("").endsWith("kone-service.exe", ignoreCase = true) }.findFirst()
                val core = serviceProcess.flatMap { it.children().filter { child -> child.info().command().orElse("").endsWith("sing-box.exe", ignoreCase = true) }.findFirst() }
                assertTrue(core.isPresent, "the service's core: ${serviceProcess.map { it.children().map { c -> c.info().command().orElse("?") }.toList() }}")
                println(command("taskkill", "/F", "/PID", core.get().pid().toString()))
                waitFor("the service to see the core gone") { crashed.status(Long.MAX_VALUE).let { it.state == "stopped" && it.end.startsWith("exited") } }
                assertNull(crashed.connect(source, null), "connecting again on the same session works")
                waitForRunning(crashed)
                crashed.disconnect()
                waitForStopped(crashed)
                crashed.close()

                // Tools: this very program (the test's java.exe) sent straight out goes past the node,
                // shows in the connections list by name, and still meets the Claude block.
                val straight = assertNotNull(ServiceLink.open(service))
                assertNull(straight.connect(source, null, bypass = listOf("java.exe")))
                val straightApi = ClashApi(assertNotNull(waitForRunning(straight).api))
                var mark = serverLog.readText().length
                flushDns()
                assertEquals(200, get("https://www.example.net/"))
                Thread.sleep(1000)
                assertFalse("example.net" in serverLog.readText().substring(mark), "a program sent straight out does not go through the node")
                waitFor("the connections list to name java.exe") {
                    straightApi.connections()?.connections.orEmpty().any { it.program.equals("java.exe", ignoreCase = true) && it.via == "direct" }
                }
                flushDns()
                assertFailsWith<UnknownHostException>("the Claude block holds for a program sent straight out") { InetAddress.getByName("api.anthropic.com") }
                straight.disconnect()
                waitForStopped(straight)
                straight.close()

                // Mode: everything straight out in Direct, the block still first; switched live to Global, all through the node.
                val modes = assertNotNull(ServiceLink.open(service))
                assertNull(modes.connect(source, null, mode = "Direct"))
                val modesApi = ClashApi(assertNotNull(waitForRunning(modes).api))
                mark = serverLog.readText().length
                flushDns()
                assertEquals(200, get("https://www.example.org/?direct"))
                Thread.sleep(1000)
                assertFalse("example.org" in serverLog.readText().substring(mark), "in Direct nothing goes through the node")
                flushDns()
                assertFailsWith<UnknownHostException>("the Claude block holds in Direct") { InetAddress.getByName("www.claude.com") }
                assertTrue(modesApi.mode("Global"), "the mode changes while running")
                modesApi.close(null)
                flushDns()
                assertEquals(200, get("https://www.example.org/?global", fresh = true), "a new connection, not one kept from Direct")
                waitFor("Global to carry example.org through the node") { "example.org" in serverLog.readText().substring(mark) }
                modes.disconnect()
                waitForStopped(modes)
                modes.close()

                // Auto: with a dead node listed first, the core settles on the one that answers.
                val dead = """{"type":"shadowsocks","tag":"Japan","server":"203.0.113.1","server_port":9,"method":"aes-128-gcm","password":"kone-e2e"}"""
                val two = Json.parseToJsonElement("""{"outbounds":[$dead,$node],"route":{"rules":[${FakeConsole.CLAUDE_RULE}]}}""") as JsonObject
                val automatic = assertNotNull(ServiceLink.open(service))
                assertNull(automatic.connect(two, "auto"))
                val autoApi = ClashApi(assertNotNull(waitForRunning(automatic).api))
                waitFor("Auto to settle on the node that answers", seconds = 60) { autoApi.now("auto") == "United States" }
                mark = serverLog.readText().length
                flushDns()
                assertEquals(200, get("https://www.example.com/?auto", fresh = true))
                waitFor("Auto to carry example.com through the node") { "example.com" in serverLog.readText().substring(mark) }
                automatic.disconnect()
                waitForStopped(automatic)
                automatic.close()

                // Someone without the block reaches Claude's names.
                val third = assertNotNull(ServiceLink.open(service))
                assertNull(third.connect(open, null))
                waitForRunning(third)
                flushDns()
                assertTrue(InetAddress.getByName("console.anthropic.com").hostAddress.isNotEmpty(), "without the block the name resolves")
                third.disconnect()
                waitForStopped(third)
                third.close()

                // Connecting and disconnecting again and again leaves nothing behind.
                repeat(3) {
                    val again = assertNotNull(ServiceLink.open(service))
                    assertNull(again.connect(source, null))
                    waitForRunning(again)
                    again.disconnect()
                    waitForStopped(again)
                    again.close()
                }
                waitFor("the adapter to go after the last run") { !tunnelUp() }
            }
        } finally {
            server.destroy()
            server.waitFor(5, TimeUnit.SECONDS)
            println("proxy server log:\n" + serverLog.takeIf { it.isFile }?.readText()?.takeLast(4000))
        }
    }

    /**
     * Without the adapter: the core run as KOne runs it itself, Windows' proxy pointed at it.
     * Windows PowerShell follows that proxy as browsers do; its traffic goes through the node,
     * Claude is refused, and the person's own proxy setting comes back exactly.
     */
    @Test
    fun theSystemProxyCarriesTrafficAndKeepsTheClaudeBlock() {
        assumeTrue(mode == "full", "runs on the Windows runner after the MSI is installed")
        val folder = Files.createTempDirectory("kone-proxy").toFile()
        val port = ServerSocket(0).use { it.localPort }
        val serverLog = File(folder, "server.log")
        val serverConfig = File(folder, "server.json").apply {
            writeText(
                """{"log":{"level":"debug"},
                   "inbounds":[{"type":"shadowsocks","listen":"127.0.0.1","listen_port":$port,"method":"aes-128-gcm","password":"kone-e2e"}],
                   "outbounds":[{"type":"direct"}],
                   "route":{"rules":[{"action":"sniff"}]}}""",
            )
        }
        val singBox = File(installed, "sing-box.exe")
        val server = ProcessBuilder(singBox.path, "run", "-c", serverConfig.path).redirectErrorStream(true).redirectOutput(serverLog).start()
        val before = assertNotNull(SystemProxy.read(), "Windows' proxy setting reads")
        val core = LocalCore(singBox) { println("core: $it") }
        try {
            waitFor("the proxy server") { runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess }
            val node = """{"type":"shadowsocks","tag":"United States","server":"127.0.0.1","server_port":$port,"method":"aes-128-gcm","password":"kone-e2e"}"""
            val source = Json.parseToJsonElement("""{"outbounds":[$node],"route":{"rules":[${FakeConsole.CLAUDE_RULE}]}}""") as JsonObject
            val api = CoreApi(ServerSocket(0).use { it.localPort }, "e2e-secret")
            val mixed = ServerSocket(0).use { it.localPort }
            val config = KoneConfig.build(source, File(installed, "rules").path.replace('\\', '/'), api, tun = false, mixed = mixed).config.toString()
            assertTrue(core.start(config), "the core starts as the person")
            waitFor("the core to answer") { ClashApi(api).answers() }
            assertTrue(SystemProxy.point(mixed), "Windows' proxy points at KOne")
            assertTrue(SystemProxy.pointsAt(mixed))

            // What follows Windows' proxy goes through the node…
            assertEquals("200", powershell("(Invoke-WebRequest -UseBasicParsing -TimeoutSec 20 https://www.example.com/).StatusCode").trim())
            waitFor("the server to carry example.com") { "example.com" in serverLog.readText() }
            // …and Claude does not.
            val refused = powershell("try { (Invoke-WebRequest -UseBasicParsing -TimeoutSec 20 https://claude.ai/).StatusCode } catch { 'refused' }").trim()
            assertEquals("refused", refused, "claude.ai through the system proxy")
            assertFalse("claude.ai" in serverLog.readText(), "nothing of Claude reached the node")
        } finally {
            core.stop()
            SystemProxy.restore()
            server.destroy()
            server.waitFor(5, TimeUnit.SECONDS)
            println("proxy server log:\n" + serverLog.takeIf { it.isFile }?.readText()?.takeLast(3000))
        }
        assertEquals(before, SystemProxy.read(), "the person's own proxy setting is back")
        assertFalse(core.running, "the core stopped")
    }

    private fun powershell(script: String): String = command("powershell", "-NoProfile", "-Command", script)

    private fun waitForRunning(link: ServiceLink): ServiceStatus {
        val deadline = System.currentTimeMillis() + 40_000
        var since = 0L
        while (System.currentTimeMillis() < deadline) {
            val status = link.status(since)
            status.logs.forEach { println("core: $it") }
            since = status.next
            if (status.state == "running") return status
            if (status.state == "stopped") fail("the core stopped: ${status.end}")
            Thread.sleep(500)
        }
        fail("the core did not start in time")
    }

    private fun waitForStopped(link: ServiceLink) {
        waitFor("the core to stop") { link.status(Long.MAX_VALUE).state == "stopped" }
    }

    private fun waitFor(what: String, seconds: Int = 30, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            if (runCatching(check).getOrDefault(false)) return
            Thread.sleep(500)
        }
        fail("timed out waiting for $what")
    }

    /** Whether KOne's adapter is up, known by the template's own address (Java names it by its description, not "KOne"). */
    private fun tunnelUp(): Boolean = NetworkInterface.networkInterfaces().anyMatch { adapter ->
        adapter.isUp && adapter.inetAddresses().anyMatch { it.hostAddress == "172.19.0.1" }
    }

    private fun get(url: String, fresh: Boolean = false): Int {
        val client = if (fresh) HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).connectTimeout(Duration.ofSeconds(10)).build() else http
        return client.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    /** A TLS handshake to [address] that names [host], as a browser connecting by address would. */
    private fun tls(address: InetAddress, host: String) {
        (SSLSocketFactory.getDefault().createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(address, 443), 10_000)
            socket.soTimeout = 10_000
            socket.sslParameters = socket.sslParameters.apply { serverNames = listOf(SNIHostName(host)) }
            socket.startHandshake()
        }
    }

    private fun flushDns() {
        command("ipconfig", "/flushdns")
    }

    private fun command(vararg line: String): String {
        val process = ProcessBuilder(*line).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        process.waitFor(30, TimeUnit.SECONDS)
        return output
    }
}
