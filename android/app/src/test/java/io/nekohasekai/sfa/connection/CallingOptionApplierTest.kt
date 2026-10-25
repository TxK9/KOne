package io.nekohasekai.sfa.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class CallingOptionApplierTest {
    private val old = CallingOptions()
    private val next = CallingOptions(enabled = true)
    private val other = CallingOptions(enabled = true, mtu = 1280)

    private inner class Fake : CallingOptionApplier.Backend {
        var options = old
        var selected = 7L
        var runtime: CallingRuntimeToken? = CallingRuntimeToken(7, 10, 3, "old")
        var writes = 0
        var reloads = 0
        var failRollback = false
        var onPrepare: suspend () -> Unit = {}
        var onWrite: suspend () -> Unit = {}
        var onReload: suspend () -> Unit = {
            runtime = runtime?.copy(epoch = 11, contentHash = "final")
        }
        override suspend fun read(profileId: Long) = options
        override suspend fun compareAndSet(profileId: Long, expected: CallingOptions, replacement: CallingOptions): Boolean {
            if (replacement == old && failRollback) error("synthetic storage failure")
            if (options != expected) return false
            options = replacement
            writes++
            if (replacement == next) onWrite()
            return true
        }
        override suspend fun prepareAndValidate(profileId: Long, options: CallingOptions): PreparedCallingConfig {
            onPrepare()
            return PreparedCallingConfig("final")
        }
        override fun selectedProfileId() = selected
        override fun acceptedRuntime() = runtime
        override suspend fun reload() { reloads++; onReload() }
        fun applier() = CallingOptionApplier(this)
    }

    @Test fun acceptedNewEpochAndExactFinalContentAreRequiredForApplied() = runBlocking {
        val backend = Fake()
        assertEquals(CallingApplyResult.Applied, backend.applier().apply(7, old, next))
        assertEquals(next, backend.options)
        assertEquals(1, backend.reloads)
    }

    @Test fun finalValidationFailureDoesNotWriteOrReload() = runBlocking {
        val backend = Fake().apply { onPrepare = { error("synthetic config containing secrets") } }
        assertEquals(CallingApplyResult.Rejected(CallingApplyFailure.ValidationFailed), backend.applier().apply(7, old, next))
        assertEquals(old, backend.options)
        assertEquals(0, backend.writes)
        assertEquals(0, backend.reloads)
    }

    @Test fun rpcSuccessWithoutRuntimeAcknowledgementRestoresSettings() = runBlocking {
        val backend = Fake().apply { onReload = { runtime = null } }
        assertEquals(CallingApplyResult.Failed(CallingApplyFailure.ConnectionNotConfirmed, CallingSettingsRecovery.Restored),
            backend.applier().apply(7, old, next))
        assertEquals(old, backend.options)
        assertEquals(1, backend.reloads)
        assertNull(backend.runtime) // Cleanup does not reconnect a stopped VPN.
    }

    @Test fun oldEpochWrongNetworkAndWrongFinalHashCannotBeAcknowledgements() = runBlocking {
        val invalid = listOf(
            CallingRuntimeToken(7, 10, 3, "final"),
            CallingRuntimeToken(7, 11, 4, "final"),
            CallingRuntimeToken(7, 11, 3, "intermediate"),
            CallingRuntimeToken(8, 11, 3, "final"),
        )
        for (token in invalid) {
            val backend = Fake().apply { onReload = { runtime = token } }
            assertEquals(CallingApplyResult.Failed(CallingApplyFailure.ConnectionNotConfirmed, CallingSettingsRecovery.Restored),
                backend.applier().apply(7, old, next))
            assertEquals(old, backend.options)
        }
    }

    @Test fun reloadExceptionRestoresOnlySettingAndNeverRetriesConnection() = runBlocking {
        val backend = Fake().apply { onReload = { runtime = null; error("synthetic RPC failure") } }
        assertEquals(CallingApplyResult.Failed(CallingApplyFailure.ReloadFailed, CallingSettingsRecovery.Restored),
            backend.applier().apply(7, old, next))
        assertEquals(old, backend.options)
        assertEquals(1, backend.reloads)
        assertNull(backend.runtime)
    }

    @Test fun cancellationAfterWritePerformsNonCancellableCleanup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val backend = Fake().apply { onReload = { entered.complete(Unit); awaitCancellation() } }
        val cleanup = CompletableDeferred<CallingSettingsRecovery>()
        val job = launch {
            try { backend.applier().apply(7, old, next); fail("must cancel") }
            catch (e: CallingApplyCancelledException) { cleanup.complete(e.recovery); throw e }
        }
        withTimeout(3000) { entered.await() }
        job.cancelAndJoin()
        assertEquals(CallingSettingsRecovery.Restored, cleanup.await())
        assertEquals(old, backend.options)
        assertEquals(1, backend.reloads)
    }

    @Test fun selectedProfileAndNetworkChangesDuringValidationPreventCommit() = runBlocking {
        val selectedChanged = Fake().apply { onPrepare = { selected = 8 } }
        assertEquals(CallingApplyResult.Rejected(CallingApplyFailure.ProfileChanged), selectedChanged.applier().apply(7, old, next))
        assertEquals(0, selectedChanged.writes)
        val networkChanged = Fake().apply { onPrepare = { runtime = runtime?.copy(epoch = 11, network = 4) } }
        assertEquals(CallingApplyResult.Rejected(CallingApplyFailure.ConnectionChanged), networkChanged.applier().apply(7, old, next))
        assertEquals(0, networkChanged.writes)
    }

    @Test fun disconnectBetweenCommitAndReloadRestoresWithoutRestarting() = runBlocking {
        val backend = Fake().apply { onWrite = { runtime = null } }
        assertEquals(CallingApplyResult.Failed(CallingApplyFailure.ConnectionChanged, CallingSettingsRecovery.Restored),
            backend.applier().apply(7, old, next))
        assertEquals(old, backend.options)
        assertEquals(0, backend.reloads)
        assertNull(backend.runtime)
    }

    @Test fun rollbackCannotOverwriteALaterEdit() = runBlocking {
        val backend = Fake().apply { onReload = { options = other; runtime = null } }
        assertEquals(CallingApplyResult.Failed(CallingApplyFailure.ConnectionNotConfirmed, CallingSettingsRecovery.NewerEditPreserved),
            backend.applier().apply(7, old, next))
        assertEquals(other, backend.options)
    }

    @Test fun failedRollbackIsReportedWithoutRawFailureText() = runBlocking {
        val backend = Fake().apply { failRollback = true; onReload = { error("secret raw JSON") } }
        val result = backend.applier().apply(7, old, next)
        assertEquals(CallingApplyResult.Failed(CallingApplyFailure.ReloadFailed, CallingSettingsRecovery.RestoreFailed), result)
        assertFalse(result.toString().contains("secret"))
        assertEquals(next, backend.options)
    }

    @Test fun disconnectedSaveNeverStartsVpnAndStaleEditorCannotOverwrite() = runBlocking {
        val backend = Fake().apply { runtime = null }
        assertEquals(CallingApplyResult.Saved, backend.applier().apply(7, old, next))
        assertEquals(0, backend.reloads)
        assertEquals(CallingApplyResult.Rejected(CallingApplyFailure.SettingsChanged), backend.applier().apply(7, old, other))
        assertEquals(next, backend.options)
    }

    @Test fun editDuringPreparationIsProtectedByCommitCas() = runBlocking {
        val backend = Fake().apply { onPrepare = { options = other } }
        assertEquals(CallingApplyResult.Rejected(CallingApplyFailure.SettingsChanged), backend.applier().apply(7, old, next))
        assertEquals(other, backend.options)
        assertEquals(0, backend.writes)
        assertEquals(0, backend.reloads)
    }

    @Test fun separateApplierInstancesShareOneSerializationLock() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = Fake().apply { onReload = { entered.complete(Unit); release.await(); runtime = runtime?.copy(epoch = 11, contentHash = "final") } }
        val first = async(start = CoroutineStart.UNDISPATCHED) { backend.applier().apply(7, old, next) }
        withTimeout(3000) { entered.await() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { backend.applier().apply(7, old, other) }
        assertFalse(second.isCompleted)
        release.complete(Unit)
        assertEquals(CallingApplyResult.Applied, first.await())
        assertEquals(CallingApplyResult.Rejected(CallingApplyFailure.SettingsChanged), second.await())
        assertEquals(1, backend.reloads)
    }
}
