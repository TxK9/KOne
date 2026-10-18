package io.nekohasekai.sfa.compose.screen.dashboard

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ProfileSelectionQueueTest {
    @Test fun rapidTapsApplyTheLastChoiceOnly() = runBlocking {
        val applied = mutableListOf<Long>()
        var busy = false
        val queue = ProfileSelectionQueue(this, { busy = it }, { throw it }, { applied += it }, 20)
        repeat(100) { queue.request(it.toLong()) }
        withTimeout(1_000) { while (applied.isEmpty() || busy) delay(5) }
        assertEquals(listOf(99L), applied)
    }

    @Test fun tapsDuringReloadDoNotOverlapAndKeepTheLastPendingChoice() = runBlocking {
        val began = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val applied = mutableListOf<Long>()
        var active = 0
        var peak = 0
        var busy = false
        val queue = ProfileSelectionQueue(this, { busy = it }, { throw it }, {
            active++; peak = maxOf(peak, active); applied += it
            if (it == 1L) { began.complete(Unit); release.await() }
            delay(10)
            active--
        }, 5)
        queue.request(1)
        began.await()
        queue.request(2); queue.request(3); queue.request(4)
        release.complete(Unit)
        withTimeout(1_000) { while (applied.size < 2 || busy) delay(5) }
        assertEquals(listOf(1L, 4L), applied)
        assertEquals(1, peak)
    }

    @Test fun failedReloadReleasesTheWorkerForAnotherTap() = runBlocking {
        var failures = 0
        var busy = false
        val applied = mutableListOf<Long>()
        val queue = ProfileSelectionQueue(this, { busy = it }, { failures++ }, {
            if (it == 1L) error("reload failed")
            applied += it
        }, 5)
        queue.request(1)
        withTimeout(1_000) { while (failures == 0 || busy) delay(5) }
        queue.request(2)
        withTimeout(1_000) { while (applied.isEmpty() || busy) delay(5) }
        assertEquals(listOf(2L), applied)
        assertEquals(1, failures)
    }
}
