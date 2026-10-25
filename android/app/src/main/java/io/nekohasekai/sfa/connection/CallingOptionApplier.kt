package io.nekohasekai.sfa.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** An in-memory identity of a configuration accepted by the running core, never UI output. */
internal data class CallingRuntimeToken(
    val profileId: Long,
    val epoch: Long,
    val network: Long,
    val contentHash: String,
)

/** The hash must describe the final runtime configuration, after every transformation. */
internal data class PreparedCallingConfig(val expectedContentHash: String)

internal enum class CallingApplyFailure {
    SettingsChanged, ProfileChanged, ConnectionChanged, ValidationFailed, StorageFailed,
    ReloadFailed, ConnectionNotConfirmed,
}

/** This describes saved settings only; recovery never restarts or reconnects the VPN. */
internal enum class CallingSettingsRecovery { Restored, NewerEditPreserved, RestoreFailed }

internal sealed interface CallingApplyResult {
    data object Unchanged : CallingApplyResult
    data object Saved : CallingApplyResult
    data object Applied : CallingApplyResult
    data class Rejected(val reason: CallingApplyFailure) : CallingApplyResult
    data class Failed(val reason: CallingApplyFailure, val recovery: CallingSettingsRecovery) : CallingApplyResult
}

/** Keeps structured cancellation while allowing the owner to report cleanup without raw errors. */
internal class CallingApplyCancelledException(val recovery: CallingSettingsRecovery) :
    CancellationException("Calling option application cancelled")

/**
 * Coordinates one setting change without treating an RPC return as proof of an accepted reload.
 * All instances share a lock. Other editors still require the backend's atomic compare-and-set.
 *
 * The caller should use an application scope: a reload can dispose the originating UI. Cancellation
 * restores this operation's setting only, under NonCancellable, and never reconnects a stopped VPN.
 * The timeout is cooperative; it cannot interrupt a blocking native/JNI reload. Once that call
 * returns, cancellation still performs cleanup and cannot produce an Applied result.
 */
internal class CallingOptionApplier(
    private val backend: Backend,
    private val mutex: Mutex = sharedMutex,
    private val reloadTimeoutMillis: Long = 30_000,
) {
    internal interface Backend {
        suspend fun read(profileId: Long): CallingOptions
        suspend fun compareAndSet(profileId: Long, expected: CallingOptions, replacement: CallingOptions): Boolean
        suspend fun prepareAndValidate(profileId: Long, options: CallingOptions): PreparedCallingConfig
        fun selectedProfileId(): Long
        fun acceptedRuntime(): CallingRuntimeToken?
        suspend fun reload()
    }

    init { require(reloadTimeoutMillis > 0) }

    suspend fun apply(profileId: Long, expected: CallingOptions, next: CallingOptions): CallingApplyResult = mutex.withLock {
        require(profileId > 0)
        currentCoroutineContext().ensureActive()
        if (next == expected) return@withLock CallingApplyResult.Unchanged
        val stored = try { backend.read(profileId) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return@withLock CallingApplyResult.Rejected(CallingApplyFailure.StorageFailed) }
        if (stored != expected) return@withLock CallingApplyResult.Rejected(CallingApplyFailure.SettingsChanged)
        if (backend.selectedProfileId() != profileId) {
            return@withLock CallingApplyResult.Rejected(CallingApplyFailure.ProfileChanged)
        }
        val initialRuntime = backend.acceptedRuntime()
        if (initialRuntime != null && initialRuntime.profileId != profileId) {
            return@withLock CallingApplyResult.Rejected(CallingApplyFailure.ConnectionChanged)
        }
        val prepared = try { backend.prepareAndValidate(profileId, next) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { return@withLock CallingApplyResult.Rejected(CallingApplyFailure.ValidationFailed) }
        currentCoroutineContext().ensureActive()
        val beforeCommit = changedSince(profileId, initialRuntime)
        if (beforeCommit != null) return@withLock CallingApplyResult.Rejected(beforeCommit)

        // A throwing storage implementation may have committed before throwing. Cleanup uses CAS,
        // so it cannot overwrite a different edit made after this operation's write attempt.
        var writeAttempted = false
        try {
            writeAttempted = true
            if (!backend.compareAndSet(profileId, expected, next)) {
                writeAttempted = false
                return@withLock CallingApplyResult.Rejected(CallingApplyFailure.SettingsChanged)
            }
            currentCoroutineContext().ensureActive()
            val beforeReload = changedSince(profileId, initialRuntime)
            if (beforeReload != null) {
                return@withLock CallingApplyResult.Failed(beforeReload, recover(profileId, expected, next))
            }
            if (initialRuntime == null) {
                if (backend.read(profileId) != next) {
                    return@withLock CallingApplyResult.Failed(
                        CallingApplyFailure.SettingsChanged, CallingSettingsRecovery.NewerEditPreserved,
                    )
                }
                val beforeSaved = changedSince(profileId, null)
                if (beforeSaved != null) {
                    return@withLock CallingApplyResult.Failed(beforeSaved, recover(profileId, expected, next))
                }
                return@withLock CallingApplyResult.Saved
            }

            try {
                withTimeout(reloadTimeoutMillis) { backend.reload() }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                return@withLock CallingApplyResult.Failed(CallingApplyFailure.ReloadFailed, recover(profileId, expected, next))
            }
            currentCoroutineContext().ensureActive()
            val accepted = backend.acceptedRuntime()
            val confirmed = backend.selectedProfileId() == profileId && accepted != null &&
                accepted.profileId == profileId && accepted.epoch > initialRuntime.epoch &&
                accepted.network == initialRuntime.network && accepted.contentHash == prepared.expectedContentHash
            if (!confirmed) {
                return@withLock CallingApplyResult.Failed(CallingApplyFailure.ConnectionNotConfirmed, recover(profileId, expected, next))
            }
            // A simultaneous editor may replace our options while the service reload is in flight.
            if (backend.read(profileId) != next) {
                return@withLock CallingApplyResult.Failed(
                    CallingApplyFailure.SettingsChanged, CallingSettingsRecovery.NewerEditPreserved,
                )
            }
            // read() may suspend; do not report an acknowledgement invalidated during that read.
            if (backend.selectedProfileId() != profileId || backend.acceptedRuntime() != accepted) {
                return@withLock CallingApplyResult.Failed(
                    CallingApplyFailure.ConnectionNotConfirmed, recover(profileId, expected, next),
                )
            }
            CallingApplyResult.Applied
        } catch (e: CancellationException) {
            if (!writeAttempted) throw e
            throw CallingApplyCancelledException(recover(profileId, expected, next))
        } catch (_: Exception) {
            if (!writeAttempted) CallingApplyResult.Rejected(CallingApplyFailure.StorageFailed)
            else CallingApplyResult.Failed(CallingApplyFailure.StorageFailed, recover(profileId, expected, next))
        }
    }

    private fun changedSince(profileId: Long, runtime: CallingRuntimeToken?): CallingApplyFailure? = when {
        backend.selectedProfileId() != profileId -> CallingApplyFailure.ProfileChanged
        backend.acceptedRuntime() != runtime -> CallingApplyFailure.ConnectionChanged
        else -> null
    }

    private suspend fun recover(profileId: Long, old: CallingOptions, next: CallingOptions): CallingSettingsRecovery =
        withContext(NonCancellable) {
            try {
                if (backend.compareAndSet(profileId, next, old)) CallingSettingsRecovery.Restored
                else if (backend.read(profileId) == old) CallingSettingsRecovery.Restored
                else CallingSettingsRecovery.NewerEditPreserved
            } catch (_: Exception) { CallingSettingsRecovery.RestoreFailed }
        }

    private companion object { val sharedMutex = Mutex() }
}
