package io.nekohasekai.sfa.managed

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import io.nekohasekai.sfa.Application
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The device credential, sealed with a key that never leaves this phone's keystore. A copy of
 * the App's data on another phone cannot open it, so a binding does not travel with a backup.
 */
internal object ManagedVault {
    private const val ALIAS = "kone_managed_credential"
    private const val KEYSTORE = "AndroidKeyStore"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = runCatching {
        val bytes = Base64.decode(sealed, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, 12))
        String(cipher.doFinal(bytes, 12, bytes.size - 12))
    }.getOrNull()

    fun forget() {
        runCatching { KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(ALIAS) }
    }
}

/** Where the binding stands, in words the screen shows. */
enum class ManagedStatus(val label: String) {
    Unbound("Not linked"),
    Synced("Synced"),
    Waiting("Awaiting route assignment"),
    Refused("Service paused"),
    Revoked("Access revoked"),
    Offline("Cannot reach the service"),
    Failed("Sync incomplete"),
}

/** One moment of the binding, for the screen; [ManagedState.snapshot] always holds the latest. */
data class ManagedSnapshot(
    val endpoint: String?,
    val deviceName: String,
    val bound: Boolean,
    val profileId: Long,
    val configName: String,
    val groupName: String,
    val policy: ManagedPolicy?,
    val syncedAt: Long,
    val status: ManagedStatus,
    val detail: String?,
    val diagnostics: Boolean,
)

/**
 * The binding to a console, kept in the App's private preferences: the address, the device, the
 * sealed credential, the configuration last applied and the managed node it became. Nothing here
 * is in the backup file (see KoneBackup), and the credential itself only as [ManagedVault] seals it.
 */
object ManagedState {
    private val prefs by lazy { Application.application.getSharedPreferences("kone_managed", Context.MODE_PRIVATE) }
    private val holder by lazy { mutableStateOf(read()) }

    val snapshot: ManagedSnapshot get() = holder.value

    val endpoint: ManagedEndpoint? get() = prefs.getString("endpoint", null)?.let { ManagedEndpoint.parse(it) }
    val profileId: Long get() = prefs.getLong("profile", -1L)
    val revision: String get() = prefs.getString("revision", "").orEmpty()
    val configRevision: String get() = prefs.getString("config_revision", "").orEmpty()
    /** The [ManagedConfig.TEMPLATE] the stored configuration was built with; 1 before it was recorded. */
    val template: Int get() = prefs.getInt("template", 1)
    val configName: String get() = prefs.getString("config_name", "").orEmpty()
    val bound: Boolean get() = prefs.getString("credential", null) != null
    val diagnosticsEnabled: Boolean get() = prefs.getBoolean("diagnostics", false)

    fun credential(): String? = prefs.getString("credential", null)?.let { ManagedVault.open(it) }

    /** Whether [id] is the node the console manages; such a node is changed only by syncing. */
    fun isManaged(id: Long): Boolean = id != -1L && id == profileId

    private fun read(): ManagedSnapshot = ManagedSnapshot(
        endpoint = prefs.getString("endpoint", null),
        deviceName = prefs.getString("device_name", "").orEmpty(),
        bound = prefs.getString("credential", null) != null,
        profileId = prefs.getLong("profile", -1L),
        configName = prefs.getString("config_name", "").orEmpty(),
        groupName = prefs.getString("group_name", "").orEmpty(),
        policy = prefs.getString("policy", null)?.let { runCatching { ManagedPolicy.from(JSONObject(it)) }.getOrNull() },
        syncedAt = prefs.getLong("synced_at", 0L),
        status = runCatching { ManagedStatus.valueOf(prefs.getString("status", null) ?: "") }.getOrDefault(ManagedStatus.Unbound),
        detail = prefs.getString("detail", null),
        diagnostics = prefs.getBoolean("diagnostics", false),
    )

    private fun edit(change: android.content.SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(change).commit()
        holder.value = read()
    }

    fun bind(endpoint: ManagedEndpoint, deviceName: String, claim: ManagedClaim) = edit {
        clear()
        putString("endpoint", endpoint.base)
        putString("device_name", deviceName)
        putString("device_id", claim.deviceId)
        putString("credential", ManagedVault.seal(claim.credential))
        putString("status", ManagedStatus.Synced.name)
    }

    fun rememberEndpoint(endpoint: ManagedEndpoint) = edit { putString("endpoint", endpoint.base) }

    fun applied(delivery: ManagedDelivery, profileId: Long) = edit {
        putString("revision", delivery.revision)
        putString("config_revision", delivery.configRevision)
        putInt("template", ManagedConfig.TEMPLATE)
        putString("config_name", delivery.configName)
        putString("group_name", delivery.groupName)
        putString("policy", delivery.policy.toJson().toString())
        putLong("profile", profileId)
        putLong("synced_at", System.currentTimeMillis())
        putString("status", ManagedStatus.Synced.name)
        remove("detail")
    }

    fun synced() = edit {
        putLong("synced_at", System.currentTimeMillis())
        putString("status", ManagedStatus.Synced.name)
        remove("detail")
    }

    fun status(status: ManagedStatus, detail: String? = null) = edit {
        putString("status", status.name)
        if (detail != null) putString("detail", detail) else remove("detail")
    }

    fun forgetProfile() = edit { remove("profile"); remove("revision"); remove("config_revision") }

    /** Unbound: the credential and everything delivered are gone; the address stays for next time. */
    fun unbind(status: ManagedStatus = ManagedStatus.Unbound, detail: String? = null) {
        val address = prefs.getString("endpoint", null)
        val diagnostics = prefs.getBoolean("diagnostics", false)
        edit {
            clear()
            if (address != null) putString("endpoint", address)
            putBoolean("diagnostics", diagnostics)
            putString("status", status.name)
            if (detail != null) putString("detail", detail)
        }
        ManagedVault.forget()
    }

    fun setDiagnostics(enabled: Boolean) = edit {
        putBoolean("diagnostics", enabled)
        if (!enabled) remove("events")
    }

    /** Events waiting for the next sync; only kept while the user lets diagnostics go out. */
    fun record(event: String) {
        if (!bound || !diagnosticsEnabled || event !in ManagedReport.EVENTS) return
        val events = pendingEvents()
        if (events.lastOrNull() == event) return
        prefs.edit().putString("events", JSONArray((events + event).takeLast(10)).toString()).apply()
    }

    fun pendingEvents(): List<String> = prefs.getString("events", null)?.let { text ->
        runCatching { JSONArray(text).let { array -> (0 until array.length()).map { array.getString(it) } } }.getOrNull()
    }.orEmpty()

    fun clearEvents() {
        prefs.edit().remove("events").apply()
    }
}
