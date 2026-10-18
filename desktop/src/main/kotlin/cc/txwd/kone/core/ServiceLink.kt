package cc.txwd.kone.core

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinError
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.ptr.IntByReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException

/** What the service says of the core: its state, why it last ended, its API while it runs, its new log lines. */
data class ServiceStatus(val state: String, val end: String, val api: CoreApi?, val logs: List<String>, val next: Long)

/** Why a connection ended or never began, in the words the screen shows. */
object Ends {
    fun message(end: String): String? = when {
        end.isBlank() || end == "stopped" -> null
        end == "config_invalid" -> "The received configuration is invalid"
        end == "busy" -> "Another session is already connecting"
        end == "untrusted" || end == "no service" -> "KOne service is not running"
        end.startsWith("exited") -> "Connection ended"
        else -> "Connection failed"
    }

    /** Whether trying again may help: not for a configuration the core refuses, someone else's connection, or a program the service turns away. */
    fun passing(end: String): Boolean = end != "stopped" && end != "config_invalid" && end != "busy" && end != "untrusted"
}

/**
 * The App's side of \\.\pipe\KOne, the KOne service (desktop/service). One link is one session:
 * a core it starts runs until it says disconnect or the link closes, so the connection never
 * outlives the App. Requests are one line of JSON each way, one at a time.
 */
class ServiceLink private constructor(private val pipe: WinNT.HANDLE) : AutoCloseable {
    private val pending = ByteArrayOutputStream()

    companion object {
        const val PIPE = "\\\\.\\pipe\\KOne"

        /**
         * Opens a session, or null when the service is not there. With [expected] the server at
         * the other end must be that very kone-service.exe, so a program that took the pipe's
         * name never sees the nodes.
         */
        fun open(expected: File?): ServiceLink? {
            val kernel = Kernel32.INSTANCE
            repeat(5) {
                val handle = kernel.CreateFile(PIPE, WinNT.GENERIC_READ or WinNT.GENERIC_WRITE, 0, null, WinNT.OPEN_EXISTING, 0, null)
                if (handle != null && handle != WinBase.INVALID_HANDLE_VALUE) {
                    if (expected == null || servedBy(handle, expected)) return ServiceLink(handle)
                    kernel.CloseHandle(handle)
                    return null
                }
                if (kernel.GetLastError() != WinError.ERROR_PIPE_BUSY) return null
                kernel.WaitNamedPipe(PIPE, 2_000)
            }
            return null
        }

        private fun servedBy(pipe: WinNT.HANDLE, expected: File): Boolean {
            val kernel = Kernel32.INSTANCE
            val pid = WinDef.ULONGByReference()
            if (!kernel.GetNamedPipeServerProcessId(pipe, pid)) return false
            val process = kernel.OpenProcess(0x1000 /* PROCESS_QUERY_LIMITED_INFORMATION */, false, pid.value.toInt()) ?: return false
            try {
                val name = CharArray(32_768)
                val size = IntByReference(name.size)
                if (!kernel.QueryFullProcessImageName(process, 0, name, size)) return false
                return File(String(name, 0, size.value)).canonicalPath.equals(expected.canonicalPath, ignoreCase = true)
            } finally {
                kernel.CloseHandle(process)
            }
        }
    }

    /** The service's version, or null when it turns this program away (or has gone). */
    fun hello(): String? = runCatching { request(buildJsonObject { put("op", "hello") }) }.getOrNull()?.takeIf { it.ok }?.get("version")?.text()

    /** Starts the core on the console's [source]; null when it started, else why not. */
    fun connect(
        source: JsonObject, selected: String?, mode: String = "Rule", bypass: List<String> = emptyList(),
        direct: List<String> = emptyList(), proxied: List<String> = emptyList(),
        routes: List<UserRule> = emptyList(), blockAds: Boolean = false,
        remoteDns: String = "", directDns: String = "", ipv6: Boolean = false,
    ): String? {
        val reply = request(buildJsonObject {
            put("op", "connect")
            put("source", source)
            selected?.let { put("selected", it) }
            put("mode", mode)
            putJsonArray("bypass") { bypass.forEach { add(it) } }
            putJsonArray("direct") { direct.forEach { add(it) } }
            putJsonArray("proxied") { proxied.forEach { add(it) } }
            putJsonArray("routes") { routes.forEach { add(it.toJson()) } }
            put("blockAds", blockAds)
            put("remoteDns", remoteDns)
            put("directDns", directDns)
            put("ipv6", ipv6)
        })
        return if (reply.ok) null else reply["error"]?.text() ?: "failed"
    }

    fun status(since: Long): ServiceStatus {
        val reply = request(buildJsonObject { put("op", "status"); put("since", since) })
        if (!reply.ok) throw IOException(reply["error"]?.text() ?: "status")
        val port = (reply["port"] as? JsonPrimitive)?.intOrNull
        val secret = reply["secret"]?.text()
        return ServiceStatus(
            state = reply["state"]?.text() ?: "stopped",
            end = reply["end"]?.text().orEmpty(),
            api = if (port != null && secret != null) CoreApi(port, secret) else null,
            logs = (reply["logs"] as? JsonArray)?.mapNotNull { it.text() }.orEmpty(),
            next = (reply["next"] as? JsonPrimitive)?.longOrNull ?: since,
        )
    }

    /** The category lists the service keeps, with the SHA-256 of each; null when it cannot say (an older service). */
    fun ruleSets(): Map<String, String>? = runCatching {
        val reply = request(buildJsonObject { put("op", "ruleSets") })
        if (!reply.ok) return null
        (reply["sets"] as? JsonObject)?.mapNotNull { (name, sha) -> sha.text()?.let { name to it } }?.toMap()
    }.getOrNull()

    /** Hands the service one category list, which it checks again before keeping; true when kept. */
    fun putRuleSet(name: String, bytes: ByteArray, sha256: String): Boolean = runCatching {
        request(buildJsonObject {
            put("op", "putRuleSet")
            put("name", name)
            put("data", java.util.Base64.getEncoder().encodeToString(bytes))
            put("sha256", sha256)
        }).ok
    }.getOrDefault(false)

    fun disconnect() {
        runCatching { request(buildJsonObject { put("op", "disconnect") }) }
    }

    @Synchronized
    private fun request(message: JsonObject): JsonObject {
        val bytes = (message.toString() + "\n").toByteArray()
        val written = IntByReference()
        if (!Kernel32.INSTANCE.WriteFile(pipe, bytes, bytes.size, written, null) || written.value != bytes.size) throw IOException("pipe closed")
        val line = readLine()
        return Json.parseToJsonElement(line) as? JsonObject ?: throw IOException("bad reply")
    }

    private fun readLine(): String {
        val buffer = ByteArray(65_536)
        while (true) {
            val bytes = pending.toByteArray()
            val end = bytes.indexOf('\n'.code.toByte())
            if (end >= 0) {
                pending.reset()
                pending.write(bytes, end + 1, bytes.size - end - 1)
                return bytes.decodeToString(0, end)
            }
            if (bytes.size > 16 shl 20) throw IOException("reply too long")
            val read = IntByReference()
            if (!Kernel32.INSTANCE.ReadFile(pipe, buffer, buffer.size, read, null) || read.value == 0) throw IOException("pipe closed")
            pending.write(buffer, 0, read.value)
        }
    }

    override fun close() {
        Kernel32.INSTANCE.CloseHandle(pipe)
    }

    private val JsonObject.ok: Boolean get() = (this["ok"] as? JsonPrimitive)?.booleanOrNull == true

    private fun kotlinx.serialization.json.JsonElement.text(): String? = (this as? JsonPrimitive)?.contentOrNull
}
