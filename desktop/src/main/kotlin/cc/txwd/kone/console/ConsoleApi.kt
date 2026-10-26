package cc.txwd.kone.console

import cc.txwd.kone.i18n.tr
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.IOException
import java.io.InputStream
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.net.ssl.SSLContext

/** Why talking to the console did not give what was asked; each has a fixed message for the screen. */
enum class ConsoleFailure(private val englishMessage: String) {
    Offline("Unable to reach the service"),
    Server("Service temporarily unavailable"),
    InviteInvalid("Invitation code is invalid or expired"),
    DeviceLimit("Device limit reached for this account"),
    Revoked("Service revoked"),
    Refused("Service suspended"),
    Waiting("No route has been assigned"),
    ConfigInvalid("The received configuration is invalid"),
    Stale("Configuration updated");
    val message: String get() = tr(englishMessage)
}

/** A failure, with the console's own wording when it gave one (it never echoes secrets). */
class ConsoleException(val failure: ConsoleFailure, val detail: String? = null) : Exception(detail ?: failure.message) {
    val shown: String get() = detail?.takeIf { it.isNotBlank() && it.length <= 60 } ?: failure.message
}

class Claim(val deviceId: String, val credential: String)

/** What GET /api/client/config delivers: the console's sing-box source and the customer's terms. */
class Delivery(
    val revision: String,
    val configRevision: String,
    val configName: String,
    val groupName: String,
    val config: JsonObject,
    val policy: Policy,
)

/** The customer's terms as the console states them; the servers enforce them, not the App. */
class Policy(val quotaBytes: Long, val deviceLimit: Int, val expiresAt: String?, val period: String) {
    companion object {
        fun from(json: JsonObject?): Policy = Policy(
            quotaBytes = json.long("quotaBytes")?.coerceAtLeast(0L) ?: 0L,
            deviceLimit = json.long("deviceLimit")?.toInt()?.coerceAtLeast(0) ?: 1,
            expiresAt = json.string("expiresAt")?.takeIf { it.isNotBlank() && it != "null" },
            period = json.string("period").orEmpty(),
        )
    }
}

/** The fixed values the console accepts in reports; anything else is refused there. */
object Report {
    const val RECEIVED = "received"
    const val APPLIED = "applied"
    const val FAILED = "failed"

    val ACK_CODES = setOf("ok", "config_invalid", "start_failed", "network_unavailable", "rollback", "unknown")
    val EVENTS = setOf("connection_ok", "connection_failed", "dns_failed", "config_invalid", "network_changed", "app_started", "tunnel_stopped", "retrying")
    val NETWORKS = setOf("wifi", "cellular", "ethernet", "offline", "unknown")
}

/**
 * The customer side of the KOne console, the same four calls as the phone's ManagedApi: claim
 * with an invite code, fetch the configuration, acknowledge it, and send diagnostics. The
 * device credential only travels in the Authorization header, never in a URL or a log.
 */
class ConsoleApi(
    private val endpoint: ConsoleEndpoint,
    private val timeoutMillis: Int = 15_000,
    /** Only tests set this, to trust the certificate of their own console. */
    private val tls: SSLContext? = null,
) {
    fun claim(inviteCode: String, deviceName: String): Claim {
        val body = buildJsonObject { put("inviteCode", inviteCode); put("deviceName", deviceName.take(60)) }
        val reply = call("POST", "/api/client/claim", body, null) { status ->
            when (status) {
                401 -> ConsoleFailure.InviteInvalid
                409 -> ConsoleFailure.DeviceLimit
                else -> null
            }
        }
        val deviceId = reply.string("deviceId").orEmpty()
        val credential = reply.string("credential").orEmpty()
        if (deviceId.isBlank() || credential.isBlank() || reply.long("format") != 1L) throw ConsoleException(ConsoleFailure.Server)
        return Claim(deviceId, credential)
    }

    fun config(credential: String): Delivery {
        val reply = call("GET", "/api/client/config", null, credential) { status ->
            when (status) {
                401 -> ConsoleFailure.Revoked
                403 -> ConsoleFailure.Refused
                409 -> ConsoleFailure.Waiting
                else -> null
            }
        }
        if (reply.long("format") != 1L || reply.string("configKind") != "sing-box-source") throw ConsoleException(ConsoleFailure.ConfigInvalid)
        val revision = reply.string("revision").orEmpty()
        val config = reply["config"] as? JsonObject
        if (revision.isBlank() || config == null) throw ConsoleException(ConsoleFailure.ConfigInvalid)
        return Delivery(
            revision = revision,
            configRevision = reply.string("configRevision").orEmpty().ifBlank { revision },
            configName = reply.string("configName").orEmpty().trim().take(60),
            groupName = reply.string("groupName").orEmpty().trim().take(60),
            config = config,
            policy = Policy.from(reply["policy"] as? JsonObject),
        )
    }

    /** A late or repeated report is not an error to the App: the console simply keeps the newer one. */
    fun ack(credential: String, revision: String, status: String, code: String = "ok") {
        require(status in setOf(Report.RECEIVED, Report.APPLIED, Report.FAILED) && code in Report.ACK_CODES)
        val body = buildJsonObject { put("revision", revision); put("status", status); put("code", code) }
        try {
            call("POST", "/api/client/ack", body, credential) { http ->
                when (http) {
                    401 -> ConsoleFailure.Revoked
                    409 -> ConsoleFailure.Stale
                    else -> null
                }
            }
        } catch (failure: ConsoleException) {
            if (failure.failure != ConsoleFailure.Stale) throw failure
        }
    }

    fun diagnostics(credential: String, appVersion: String, network: String, events: List<String>, downloadBytes: Long = 0, uploadBytes: Long = 0) {
        val known = events.filter { it in Report.EVENTS }.take(10)
        val body = buildJsonObject {
            put("appVersion", appVersion.filter { it.isLetterOrDigit() || it in "._+-" }.take(32).ifBlank { "unknown" })
            put("network", network.takeIf { it in Report.NETWORKS } ?: "unknown")
            put("events", JsonArray(known.map(::JsonPrimitive)))
            put("downloadBytes", downloadBytes.coerceAtLeast(0))
            put("uploadBytes", uploadBytes.coerceAtLeast(0))
        }
        call("POST", "/api/client/diagnostics", body, credential) { http -> if (http == 401) ConsoleFailure.Revoked else null }
    }

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(timeoutMillis.toLong()))
        .followRedirects(HttpClient.Redirect.NEVER)
        .proxy(ProxySelector.getDefault() ?: HttpClient.Builder.NO_PROXY)
        .apply { if (tls != null) sslContext(tls) }
        .build()

    private fun call(method: String, path: String, body: JsonObject?, credential: String?, map: (Int) -> ConsoleFailure?): JsonObject {
        val request = try {
            HttpRequest.newBuilder(URI(endpoint.url(path)))
                .timeout(Duration.ofMillis(timeoutMillis.toLong()))
                .header("Accept", "application/json")
                .apply { if (credential != null) header("Authorization", "Bearer $credential") }
                .apply {
                    if (body == null) method(method, HttpRequest.BodyPublishers.noBody())
                    else header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body.toString()))
                }
                .build()
        } catch (_: IllegalArgumentException) {
            throw ConsoleException(ConsoleFailure.Offline)
        }
        val response = try {
            client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        } catch (_: IOException) {
            throw ConsoleException(ConsoleFailure.Offline)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ConsoleException(ConsoleFailure.Offline)
        }
        val text = try {
            response.body().use { readLimited(it) }
        } catch (_: IOException) {
            throw ConsoleException(ConsoleFailure.Offline)
        }
        val status = response.statusCode()
        val json = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        if (status == 200) return json ?: throw ConsoleException(ConsoleFailure.Server)
        val said = json.string("error")?.takeIf { it.isNotBlank() }
        val failure = map(status) ?: ConsoleFailure.Server
        throw ConsoleException(failure, if (failure == ConsoleFailure.Server) "$status ${said.orEmpty()}".trim() else said)
    }

    private fun readLimited(stream: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BODY) throw ConsoleException(ConsoleFailure.ConfigInvalid)
        }
        return out.toByteArray().decodeToString()
    }

    private companion object {
        const val MAX_BODY = 2 * 1024 * 1024
    }
}

internal fun JsonObject?.string(key: String): String? = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject?.long(key: String): Long? = (this?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
