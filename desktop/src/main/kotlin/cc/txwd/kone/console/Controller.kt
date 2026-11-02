package cc.txwd.kone.console

import cc.txwd.kone.app.Places
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add

/** Device-scoped authority metadata; it contains no node or account credential. */
data class ControllerInfo(val deviceId: String, val mode: String, val authGeneration: Long, val nodeIds: Set<String>, val ownerPublish: Boolean = false) {
    fun toJson() = buildJsonObject {
        put("format", 1); put("deviceId", deviceId); put("mode", mode); put("required", mode == "enabled")
        put("authGeneration", authGeneration); put("ownerPublish", ownerPublish)
        putJsonArray("nodeIds") { nodeIds.forEach { add(it) } }
    }

    companion object {
        fun read(value: JsonObject?, requireNodes: Boolean = false): ControllerInfo {
            fun invalid(): Nothing = throw ConsoleException(ConsoleFailure.ConfigInvalid)
            if (value == null || (value["format"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull != 1) invalid()
            val device = value.string("deviceId")?.takeIf { it.length in 1..128 } ?: invalid()
            val mode = value.string("mode")?.takeIf { it == "legacy" || it == "enabled" } ?: invalid()
            val required = (value["required"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: invalid()
            val generation = (value["authGeneration"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull?.takeIf { it >= 0 } ?: invalid()
            val nodes = (value["nodeIds"] as? JsonArray)?.map { item ->
                (item as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.length in 1..128 } ?: invalid()
            } ?: emptyList()
            if (requireNodes && mode == "enabled" && nodes.isEmpty() || required != (mode == "enabled") || nodes.size > 512 || nodes.distinct().size != nodes.size || mode == "legacy" && nodes.isNotEmpty()) invalid()
            val ownerPublish = (value["ownerPublish"] as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull ?: false
            return ControllerInfo(device, mode, generation, nodes.toSet(), ownerPublish)
        }
    }
}

/** This secret-bearing value is only serialized into the authenticated local service pipe. */
class ControllerAuthority(
    private val endpoint: String,
    private val deviceId: String,
    private val credential: String,
    private val configRevision: String,
    private val revision: String,
    private val generation: Long,
    private val managedSource: JsonObject,
) {
    fun toJson() = buildJsonObject {
        put("endpoint", endpoint); put("deviceId", deviceId); put("credential", credential)
        put("configRevision", configRevision); put("revision", revision); put("authGeneration", generation)
        put("managedSource", managedSource)
    }
    override fun toString() = "ControllerAuthority(redacted)"
}

internal fun controllerRegistration() = buildJsonObject {
    put("protocol", 1); put("platform", "windows"); put("clientVersion", Places.version)
    put("startupLease", true); put("runtimeLease", true)
    putJsonArray("protocols") { listOf("vless", "vmess", "trojan", "shadowsocks", "hysteria2", "tuic", "anytls", "socks", "http").forEach { add(it) } }
    putJsonArray("transports") { listOf("tcp", "ws", "grpc", "httpupgrade", "quic").forEach { add(it) } }
}
