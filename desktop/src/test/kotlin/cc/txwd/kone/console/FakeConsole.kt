package cc.txwd.kone.console

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * A stand-in for the KOne console's customer interface, as docs/windows/README.md describes it,
 * over HTTPS on 127.0.0.1 with a certificate made for the test. Never a live console.
 */
class FakeConsole(var source: JsonObject) : AutoCloseable {
    val invites = mutableSetOf<String>()
    val devices = mutableMapOf<String, String>() // credential -> device name
    val revoked = mutableSetOf<String>()
    var deviceLimit = 3
    var refused = false
    var waiting = false
    var revision = "r1"
    var configRevision = "c1"
    /** When this person's use ends, as the console states it in the policy; none by default. */
    var expiresAt: String? = null
    var authGeneration = 0L
    var ownerPublish = false
    val acks = mutableListOf<JsonObject>()
    val diagnostics = mutableListOf<JsonObject>()
    /** Every Authorization header and every path the console saw, to check where the credential went. */
    val seen = mutableListOf<Pair<String, String?>>()
    private var lastDiagnostics = 0L

    private val keystore: KeyStore = makeKeystore()
    private val server: HttpsServer = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        httpsConfigurator = HttpsConfigurator(SSLContext.getInstance("TLS").apply {
            init(KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keystore, PASSWORD) }.keyManagers, null, null)
        })
        createContext("/") { exchange -> exchange.use { handle(it) } }
        start()
    }

    val endpoint: ConsoleEndpoint = checkNotNull(ConsoleEndpoint.parse("https://127.0.0.1:${server.address.port}"))

    /** TLS that trusts this console's certificate and nothing else. */
    val tls: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keystore) }.trustManagers, null)
    }

    fun api() = ConsoleApi(endpoint, tls = tls)

    fun link(code: String) = "kone://claim?server=${endpoint.base}&code=$code"

    private fun handle(exchange: HttpExchange) {
        val auth = exchange.requestHeaders.getFirst("Authorization")
        synchronized(seen) { seen += exchange.requestURI.toString() to auth }
        val body = exchange.requestBody.readBytes().decodeToString().takeIf { it.isNotBlank() }?.let { Json.parseToJsonElement(it) as JsonObject }
        val credential = auth?.removePrefix("Bearer ")
        when ("${exchange.requestMethod} ${exchange.requestURI.path}") {
            "POST /api/client/claim" -> {
                val code = body!!["inviteCode"]!!.jsonPrimitive.content
                when {
                    code !in invites -> reply(exchange, 401, error("Invitation code expired"))
                    devices.size >= deviceLimit -> reply(exchange, 409, error("Device binding limit reached"))
                    else -> {
                        invites -= code
                        val issued = "cred-" + UUID.randomUUID()
                        devices[issued] = body["deviceName"]!!.jsonPrimitive.content
                        reply(exchange, 200, buildJsonObject { put("deviceId", "dev-${devices.size}"); put("credential", issued); put("format", 1) })
                    }
                }
            }
            "POST /api/client/controller/register" -> when {
                credential == null || credential !in devices || credential in revoked -> reply(exchange, 401, error("Device authentication failed"))
                else -> reply(exchange, 200, buildJsonObject { put("controller", controller(credential)) })
            }
            "GET /api/client/config" -> when {
                credential == null || credential !in devices || credential in revoked -> reply(exchange, 401, error("Device authentication failed"))
                refused -> reply(exchange, 403, error("Service period ended"))
                waiting -> reply(exchange, 409, error("No configuration assigned"))
                else -> reply(exchange, 200, buildJsonObject {
                    put("format", 1); put("configKind", "sing-box-source"); put("configName", "United States"); put("groupName", "Test group")
                    put("config", source); put("configRevision", configRevision); put("revision", revision)
                    if (exchange.requestHeaders.getFirst("X-KOne-Controller-Protocol") == "1") put("controller", controller(credential!!))
                    put("policy", buildJsonObject { put("quotaBytes", 0); put("deviceLimit", deviceLimit); put("period", "month"); expiresAt?.let { put("expiresAt", it) } })
                })
            }
            "POST /api/client/ack", "POST /api/client/controller/ack" -> when {
                credential == null || credential !in devices || credential in revoked -> reply(exchange, 401, error("Device authentication failed"))
                body!!["revision"]!!.jsonPrimitive.content != revision -> reply(exchange, 409, error("Configuration revision changed"))
                else -> { synchronized(acks) { acks += body }; reply(exchange, 200, ok()) }
            }
            "POST /api/client/diagnostics" -> {
                val now = System.currentTimeMillis()
                when {
                    credential == null || credential !in devices || credential in revoked -> reply(exchange, 401, error("Device authentication failed"))
                    now - lastDiagnostics < 2000 -> reply(exchange, 429, error("Too many reports"))
                    else -> { lastDiagnostics = now; synchronized(diagnostics) { diagnostics += body!! }; reply(exchange, 200, ok()) }
                }
            }
            else -> reply(exchange, 404, error("Endpoint not found"))
        }
    }

    private fun controller(credential: String) = buildJsonObject {
        put("format", 1); put("deviceId", "dev-${devices.keys.indexOf(credential) + 1}")
        put("mode", "legacy"); put("required", false); put("authGeneration", authGeneration); put("ownerPublish", ownerPublish)
        putJsonArray("nodeIds") {}
    }

    private fun error(text: String) = buildJsonObject { put("error", text) }
    private fun ok() = buildJsonObject { put("ok", true) }

    private fun reply(exchange: HttpExchange, status: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    override fun close() = server.stop(0)

    companion object {
        internal val PASSWORD = "changeit".toCharArray()

        /** A one-day certificate for 127.0.0.1, made with the JDK's keytool. */
        internal fun makeKeystore(): KeyStore {
            val file = File.createTempFile("fake-console", ".p12").apply { delete(); deleteOnExit() }
            val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (File.separatorChar == '\\') ".exe" else "").path
            val process = ProcessBuilder(
                keytool, "-genkeypair", "-alias", "console", "-keyalg", "EC", "-groupname", "secp256r1",
                "-dname", "CN=127.0.0.1", "-ext", "SAN=ip:127.0.0.1", "-validity", "1",
                "-keystore", file.path, "-storetype", "PKCS12", "-storepass", "changeit", "-keypass", "changeit",
            ).redirectErrorStream(true).start()
            val output = process.inputStream.readBytes().decodeToString()
            check(process.waitFor() == 0) { "keytool failed: $output" }
            return KeyStore.getInstance("PKCS12").apply { file.inputStream().use { load(it, PASSWORD) } }
        }

        /** A console source of two nodes and the Claude block, written as the console writes them. */
        fun source(blockClaude: Boolean): JsonObject = Json.parseToJsonElement(
            """
            {
              "outbounds": [
                {"type": "vless", "tag": "United States", "server": "203.0.113.10", "server_port": 443, "uuid": "${UUID(1, 1)}"},
                {"type": "trojan", "tag": "Japan", "server": "203.0.113.11", "server_port": 443, "password": "example"},
                {"type": "direct", "tag": "direct"}
              ],
              "route": {"rules": [${if (blockClaude) CLAUDE_RULE else ""}]}
            }
            """.trimIndent(),
        ) as JsonObject

        const val CLAUDE_RULE = """{"domain_suffix": ["anthropic.com", "claude.ai", "claude.com", "claudeusercontent.com"], "action": "reject"}"""
    }
}
