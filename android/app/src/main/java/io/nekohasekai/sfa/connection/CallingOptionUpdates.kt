package io.nekohasekai.sfa.connection

import android.content.Context
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.selfuse.HomeSettings
import io.nekohasekai.sfa.udp.UdpRuntime
import io.nekohasekai.sfa.utils.RemoteControlManager
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

internal data class CallingOptionUpdate(
    val busy: Boolean = false,
    val revision: Long = 0,
    val result: CallingApplyResult? = null,
    val confirmedEpoch: Long? = null,
)

/** A reload temporarily removes its editor; the user's operation must survive that disposal. */
internal object CallingOptionUpdates {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val starts = Any()
    private val mutable = MutableStateFlow<Map<Long, CallingOptionUpdate>>(emptyMap())
    val state = mutable.asStateFlow()

    fun request(context: Context, profileId: Long, expected: CallingOptions, next: CallingOptions) = synchronized(starts) {
        if (profileId <= 0 || mutable.value[profileId]?.busy == true || expected == next) return@synchronized
        val application = context.applicationContext
        val previous = mutable.value[profileId] ?: CallingOptionUpdate()
        mutable.update { it + (profileId to previous.copy(busy = true, result = null)) }
        scope.launch {
            val store = CallingPreferences(application)
            var preparedHash: String? = null
            val backend = object : CallingOptionApplier.Backend {
                override suspend fun read(profileId: Long) = store.read(profileId)
                override suspend fun compareAndSet(profileId: Long, expected: CallingOptions, replacement: CallingOptions) =
                    store.compareAndSet(profileId, expected, replacement)

                override suspend fun prepareAndValidate(profileId: Long, options: CallingOptions): PreparedCallingConfig {
                    val profile = ProfileManager.get(profileId) ?: error("Profile unavailable")
                    val chained = io.nekohasekai.sfa.selfuse.NodeChain.applyFor(profile.id, File(profile.typed.path).readText()) { id ->
                        ProfileManager.get(id)?.let { front -> File(front.typed.path).readText().takeIf { it.isNotBlank() }?.let { it to front.name } }
                    }
                    val content = RuntimeConnectionConfig.prepare(
                        HomeSettings.prepare(chained), options,
                        AppPolicySnapshot.current(application.packageName),
                    )
                    Libbox.checkConfig(content)
                    return PreparedCallingConfig(hash(content).also { preparedHash = it })
                }

                override fun selectedProfileId(): Long =
                    if (RemoteControlManager.remoteServer.value == null) Settings.selectedProfile else -1L

                override fun acceptedRuntime(): CallingRuntimeToken? =
                    UdpRuntime.active.value?.let { CallingRuntimeToken(it.id, it.epoch, it.network, hash(it.config)) }

                override suspend fun reload() {
                    val before = acceptedRuntime() ?: error("Connection changed")
                    runInterruptible(Dispatchers.IO) {
                        check(selectedProfileId() == profileId && acceptedRuntime() == before) { "Connection changed" }
                        Libbox.newStandaloneCommandClient().serviceReload()
                    }
                }
            }
            val result = try {
                CallingOptionApplier(backend).apply(profileId, expected, next)
            } catch (cancelled: CallingApplyCancelledException) {
                CallingApplyResult.Failed(CallingApplyFailure.ConnectionNotConfirmed, cancelled.recovery)
            } catch (_: Exception) {
                CallingApplyResult.Rejected(CallingApplyFailure.StorageFailed)
            }
            val confirmedEpoch = if (result == CallingApplyResult.Applied) backend.acceptedRuntime()
                ?.takeIf { it.profileId == profileId && it.contentHash == preparedHash && backend.selectedProfileId() == profileId }
                ?.epoch else null
            mutable.update { states ->
                states + (profileId to CallingOptionUpdate(result = result, revision = previous.revision + 1, confirmedEpoch = confirmedEpoch))
            }
        }
        Unit
    }

    private fun hash(content: String): String = MessageDigest.getInstance("SHA-256")
        .digest(content.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

internal fun callingUpdateMessage(update: CallingOptionUpdate, profileId: Long, runtimeProfileId: Long?, runtimeEpoch: Long?, editable: Boolean): String =
    callingUpdateMessage(update.result, editable && runtimeProfileId == profileId && update.confirmedEpoch != null && update.confirmedEpoch == runtimeEpoch)

internal fun callingUpdateMessage(result: CallingApplyResult?, confirmationCurrent: Boolean = false): String = when (result) {
    null, CallingApplyResult.Unchanged -> ""
    CallingApplyResult.Applied -> if (confirmationCurrent) "Applied to the active connection" else "Settings saved. Active connection unconfirmed."
    CallingApplyResult.Saved -> "Settings saved"
    is CallingApplyResult.Rejected -> when (result.reason) {
        CallingApplyFailure.ValidationFailed -> "These options are unsupported by this profile and were not saved."
        CallingApplyFailure.ProfileChanged, CallingApplyFailure.ConnectionChanged -> "The connection changed. Configure it again."
        CallingApplyFailure.SettingsChanged -> "Settings have changed. Confirm them again."
        else -> "Could not save. Try again."
    }
    is CallingApplyResult.Failed -> when (result.recovery) {
        CallingSettingsRecovery.Restored -> "Settings restored. Connection unconfirmed; reconnect."
        CallingSettingsRecovery.NewerEditPreserved -> "Later changes were preserved. Check the connection again."
        CallingSettingsRecovery.RestoreFailed -> "Settings could not be fully restored. Check them and reconnect."
    }
}
