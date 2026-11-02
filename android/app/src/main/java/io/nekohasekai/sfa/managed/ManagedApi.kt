package io.nekohasekai.sfa.managed

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** Why talking to the console did not give what was asked; each has a fixed message for the UI. */
enum class ManagedFailure(val message: String) {
    Offline("Cannot reach the service"),
    Server("Service unavailable"),
    InviteInvalid("The claim code is invalid or expired."),
    DeviceLimit("This account has reached its device limit."),
    Revoked("Access revoked"),
    Refused("Service paused"),
    Waiting("No route has been assigned."),
    ConfigInvalid("The received configuration cannot be used."),
    ApplyFailed("The new configuration failed to start. The previous one was restored."),
    Stale("Configuration updated"),
}

/** A failure with bounded console wording after request secrets are excluded at the API boundary. */
class ManagedException(val failure: ManagedFailure, val detail: String? = null) : Exception(detail ?: failure.message) {
    val shown: String get() = detail?.takeIf { it.isNotBlank() && it.length <= 60 } ?: failure.message
}

class ManagedClaim(val deviceId: String, val credential: String)

/** What GET /api/client/config delivers: the console's sing-box source and the customer's policy. */
class ManagedDelivery(
    val revision: String,
    val configRevision: String,
    val configName: String,
    val groupName: String,
    val config: JSONObject,
    val policy: ManagedPolicy,
)

/** The customer's terms as the console states them; enforcement happens on the servers, not here. */
class ManagedPolicy(
    val quotaBytes: Long,
    val deviceLimit: Int,
    val expiresAt: String?,
    val period: String,
) {
    fun toJson(): JSONObject = JSONObject().put("quotaBytes", quotaBytes).put("deviceLimit", deviceLimit)
        .put("expiresAt", expiresAt ?: JSONObject.NULL).put("period", period)

    companion object {
        fun from(json: JSONObject?): ManagedPolicy = ManagedPolicy(
            quotaBytes = json?.optLong("quotaBytes", 0L)?.coerceAtLeast(0L) ?: 0L,
            deviceLimit = json?.optInt("deviceLimit", 1)?.coerceAtLeast(0) ?: 1,
            expiresAt = json?.optString("expiresAt")?.takeIf { it.isNotBlank() && it != "null" },
            period = json?.optString("period").orEmpty(),
        )
    }
}

/** The fixed values the console accepts in reports; anything else is refused there. */
object ManagedReport {
    const val RECEIVED = "received"
    const val APPLIED = "applied"
    const val FAILED = "failed"

    val ACK_CODES = setOf("ok", "config_invalid", "start_failed", "network_unavailable", "rollback", "unknown")
    val EVENTS = setOf("connection_ok", "connection_failed", "dns_failed", "config_invalid", "network_changed", "app_started", "tunnel_stopped", "retrying")
    val NETWORKS = setOf("wifi", "cellular", "ethernet", "offline", "unknown")
}

/**
 * The customer side of the KOne console (docs/customer-api.md in the console): claim with an
 * invite code, fetch the configuration, acknowledge it, and send diagnostics. The device
 * credential only ever travels in the Authorization header, never in a URL or a log.
 */
class ManagedApi(private val endpoint: ManagedEndpoint, private val timeoutMillis: Int = 15_000) {
    fun claim(inviteCode: String, deviceName: String): ManagedClaim {
        val body = JSONObject().put("inviteCode", inviteCode).put("deviceName", deviceName.take(60))
        val reply = call("POST", "/api/client/claim", body, null) { status ->
            when (status) {
                401 -> ManagedFailure.InviteInvalid
                409 -> ManagedFailure.DeviceLimit
                else -> null
            }
        }
        val deviceId = reply.optString("deviceId")
        val credential = reply.optString("credential")
        if (deviceId.isBlank() || credential.isBlank() || reply.optInt("format") != 1) throw ManagedException(ManagedFailure.Server)
        return ManagedClaim(deviceId, credential)
    }

    fun config(credential: String): ManagedDelivery {
        val reply = call("GET", "/api/client/config", null, credential) { status ->
            when (status) {
                401 -> ManagedFailure.Revoked
                403 -> ManagedFailure.Refused
                409 -> ManagedFailure.Waiting
                else -> null
            }
        }
        if (reply.optInt("format") != 1 || reply.optString("configKind") != "sing-box-source") throw ManagedException(ManagedFailure.ConfigInvalid)
        val revision = reply.optString("revision")
        val config = reply.optJSONObject("config")
        if (revision.isBlank() || config == null) throw ManagedException(ManagedFailure.ConfigInvalid)
        return ManagedDelivery(
            revision = revision,
            configRevision = reply.optString("configRevision").ifBlank { revision },
            configName = reply.optString("configName").trim().take(60),
            groupName = reply.optString("groupName").trim().take(60),
            config = config,
            policy = ManagedPolicy.from(reply.optJSONObject("policy")),
        )
    }

    /** A late or repeated report is not an error to the App: the console simply keeps the newer one. */
    fun ack(credential: String, revision: String, status: String, code: String = "ok") {
        require(status in setOf(ManagedReport.RECEIVED, ManagedReport.APPLIED, ManagedReport.FAILED) && code in ManagedReport.ACK_CODES)
        val body = JSONObject().put("revision", revision).put("status", status).put("code", code)
        try {
            call("POST", "/api/client/ack", body, credential) { http ->
                when (http) {
                    401 -> ManagedFailure.Revoked
                    409 -> ManagedFailure.Stale
                    else -> null
                }
            }
        } catch (failure: ManagedException) {
            if (failure.failure != ManagedFailure.Stale) throw failure
        }
    }

    fun diagnostics(credential: String, appVersion: String, network: String, events: List<String>) {
        val known = events.filter { it in ManagedReport.EVENTS }.take(10)
        val body = JSONObject()
            .put("appVersion", appVersion.filter { it.isLetterOrDigit() || it in "._+-" }.take(32).ifBlank { "unknown" })
            .put("network", network.takeIf { it in ManagedReport.NETWORKS } ?: "unknown")
            .put("events", JSONArray(known))
        call("POST", "/api/client/diagnostics", body, credential) { http -> if (http == 401) ManagedFailure.Revoked else null }
    }

    private fun call(method: String, path: String, body: JSONObject?, credential: String?, map: (Int) -> ManagedFailure?): JSONObject {
        val connection = try {
            URL(endpoint.url(path)).openConnection() as HttpURLConnection
        } catch (_: IOException) {
            throw ManagedException(ManagedFailure.Offline)
        }
        try {
            connection.requestMethod = method
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (credential != null) connection.setRequestProperty("Authorization", "Bearer $credential")
            if (body != null) {
                val bytes = body.toString().toByteArray()
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)?.use { readLimited(it) }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrNull()
            if (status == 200) return json ?: throw ManagedException(ManagedFailure.Server)
            val requestSecrets = listOfNotNull(credential, body?.optString("inviteCode")?.takeIf { it.isNotEmpty() })
            val said = json?.optString("error")?.takeIf { text ->
                text.isNotBlank() && text.length <= 160 && text.none { it.isISOControl() } &&
                    requestSecrets.none { secret -> secret.isNotEmpty() && text.contains(secret) }
            }
            val failure = map(status) ?: ManagedFailure.Server
            throw ManagedException(failure, if (failure == ManagedFailure.Server) "$status ${said.orEmpty()}".trim() else said)
        } catch (failure: ManagedException) {
            throw failure
        } catch (_: IOException) {
            throw ManagedException(ManagedFailure.Offline)
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimited(stream: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > MAX_BODY) throw ManagedException(ManagedFailure.ConfigInvalid)
        }
        return out.toByteArray().decodeToString()
    }

    private companion object {
        const val MAX_BODY = 2 * 1024 * 1024
    }
}
