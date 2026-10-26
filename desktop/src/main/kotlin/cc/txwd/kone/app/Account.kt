package cc.txwd.kone.app

import cc.txwd.kone.i18n.tr

import cc.txwd.kone.console.ClaimLink
import cc.txwd.kone.console.ConsoleApi
import cc.txwd.kone.console.ConsoleEndpoint
import cc.txwd.kone.console.ConsoleException
import cc.txwd.kone.console.ConsoleFailure
import cc.txwd.kone.console.Report
import cc.txwd.kone.console.string
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Base64

/** This computer's binding to the console, as the App keeps it between runs. */
data class Binding(
    val server: String,
    val deviceId: String,
    val deviceName: String,
    /** The device credential; only ever written sealed. */
    val credential: String,
    val revision: String? = null,
    val configRevision: String? = null,
    val configName: String = "",
    /** The console's last good source, the nodes in it; only ever written sealed. */
    val source: JsonObject? = null,
    /** The node chosen last, by its tag. */
    val selected: String? = null,
    /** When the console says this person's use ends (ISO 8601), if it ends. */
    val expiresAt: String? = null,
)

/**
 * The binding on disk, binding.json in the App's folder. The credential and the source are
 * sealed by the [Vault]; the rest says nothing secret. A binding that cannot be opened here
 * (copied from another user or computer) counts as none.
 */
class BindingStore(private val folder: File, private val vault: Vault) {
    private val file get() = File(folder, "binding.json")

    fun load(): Binding? {
        val json = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return null
        val credential = json.string("credential")?.let(::open) ?: return null
        return Binding(
            server = json.string("server") ?: return null,
            deviceId = json.string("deviceId").orEmpty(),
            deviceName = json.string("deviceName").orEmpty(),
            credential = credential,
            revision = json.string("revision"),
            configRevision = json.string("configRevision"),
            configName = json.string("configName").orEmpty(),
            source = json.string("source")?.let(::open)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() },
            selected = json.string("selected"),
            expiresAt = json.string("expiresAt"),
        )
    }

    fun save(binding: Binding) {
        val json = buildJsonObject {
            put("format", 1)
            put("server", binding.server)
            put("deviceId", binding.deviceId)
            put("deviceName", binding.deviceName)
            put("credential", seal(binding.credential))
            binding.revision?.let { put("revision", it) }
            binding.configRevision?.let { put("configRevision", it) }
            put("configName", binding.configName)
            binding.source?.let { put("source", seal(it.toString())) }
            binding.selected?.let { put("selected", it) }
            binding.expiresAt?.let { put("expiresAt", it) }
        }
        folder.mkdirs()
        val next = File(folder, "binding.json.next")
        next.writeText(json.toString())
        if (!next.renameTo(file)) {
            file.delete()
            check(next.renameTo(file)) { tr("Unable to save") }
        }
    }

    fun clear() {
        file.delete()
    }

    private fun seal(plain: String): String = Base64.getEncoder().encodeToString(vault.seal(plain.toByteArray()))

    private fun open(sealed: String): String? = runCatching { Base64.getDecoder().decode(sealed) }.getOrNull()?.let(vault::open)?.decodeToString()
}

/** How a sync with the console went, in the words the screen needs. */
enum class Sync(val message: String?) {
    Unchanged(null),
    Updated(null),
    Waiting(ConsoleFailure.Waiting.message),
    Refused(ConsoleFailure.Refused.message),
    Revoked(ConsoleFailure.Revoked.message),
    Offline(ConsoleFailure.Offline.message),
    Failed(ConsoleFailure.ConfigInvalid.message),
}

/**
 * Claims with a key and keeps the nodes in step with the console, the way the phone's
 * ManagedSync does: fetch, acknowledge, check what was sent, keep it only when it is good, and
 * report how that went. When the console takes the service back the binding goes; the App then
 * asks for a key again.
 */
class Account(
    private val store: BindingStore,
    private val deviceName: String,
    private val api: (ConsoleEndpoint) -> ConsoleApi = { ConsoleApi(it) },
    /** Whether the core accepts a source; returns false when it would not run. */
    private val accepts: (JsonObject) -> Boolean = { true },
) {
    @Volatile
    var binding: Binding? = store.load()
        private set

    /** Binds this computer with a key, then fetches its nodes. Throws [ConsoleException] when the console says no. */
    fun claim(link: ClaimLink): Sync {
        val claim = api(link.endpoint).claim(link.code, deviceName)
        val bound = Binding(link.endpoint.base, claim.deviceId, deviceName, claim.credential)
        store.save(bound)
        binding = bound
        return sync()
    }

    fun sync(): Sync {
        val current = binding ?: return Sync.Revoked
        val endpoint = ConsoleEndpoint.parse(current.server) ?: return Sync.Revoked.also { forget() }
        val console = api(endpoint)
        val delivery = try {
            console.config(current.credential)
        } catch (failure: ConsoleException) {
            return when (failure.failure) {
                ConsoleFailure.Revoked -> Sync.Revoked.also { forget() }
                ConsoleFailure.Refused -> Sync.Refused
                ConsoleFailure.Waiting -> Sync.Waiting
                ConsoleFailure.Offline -> Sync.Offline
                else -> Sync.Failed
            }
        }
        if (delivery.revision == current.revision && current.source != null) {
            // The same nodes; the terms may still have moved (a renewal).
            if (delivery.policy.expiresAt != current.expiresAt) {
                val renewed = current.copy(expiresAt = delivery.policy.expiresAt)
                store.save(renewed)
                binding = renewed
            }
            return Sync.Unchanged
        }
        runCatching { console.ack(current.credential, delivery.revision, Report.RECEIVED) }
        if (delivery.configRevision != current.configRevision || current.source == null) {
            val good = runCatching { accepts(delivery.config) }.getOrDefault(false)
            if (!good) {
                runCatching { console.ack(current.credential, delivery.revision, Report.FAILED, "config_invalid") }
                return Sync.Failed
            }
        }
        val updated = current.copy(
            revision = delivery.revision,
            configRevision = delivery.configRevision,
            configName = delivery.configName,
            source = delivery.config,
            expiresAt = delivery.policy.expiresAt,
        )
        store.save(updated)
        binding = updated
        runCatching { console.ack(current.credential, delivery.revision, Report.APPLIED) }
        return Sync.Updated
    }

    /** Remembers the node chosen, so the next start begins there. */
    fun select(tag: String) {
        val current = binding ?: return
        if (current.selected == tag) return
        val updated = current.copy(selected = tag)
        store.save(updated)
        binding = updated
    }

    private fun forget() {
        store.clear()
        binding = null
    }
}

/** How soon this person's use ends, as the dashboard says it in its last week; null before then or when it never ends. */
object Expiry {
    fun hint(expiresAt: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String? {
        val end = expiresAt?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() } ?: return null
        if (!end.isAfter(now)) return tr("Expired")
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), end.atZone(zone).toLocalDate())
        return when {
            days > 7 -> null
            days <= 0L -> tr("Expires today")
            days == 1L -> tr("Expires tomorrow")
            else -> tr("{0} days remaining", days)
        }
    }
}
