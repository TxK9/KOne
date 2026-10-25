package io.nekohasekai.sfa.compose.screen.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExitIpRetryTest {
    @Test fun immediateSuccessPreservesExitMetadataWithoutWaiting() = runBlocking {
        val info = ExitInfo("203.0.113.7", "JP")
        assertEquals(info, detectWithRetry(pause = { error("Unexpected retry") }) { info })
    }

    @Test fun exceptionsAndEmptyResponsesRetryWithinTheBound() = runBlocking {
        val waits = mutableListOf<Long>()
        var attempts = 0
        val info = ExitInfo("203.0.113.7", "JP")
        assertEquals(info, detectWithRetry(pause = { waits += it }) {
            when (++attempts) { 1 -> error("Tunnel not ready"); 2 -> null; else -> info }
        })
        assertEquals(3, attempts)
        assertEquals(listOf(1500L, 4000L), waits)
    }

    @Test fun anUnavailableExitRemainsHiddenAfterThreeAttempts() = runBlocking {
        var attempts = 0
        assertNull(detectWithRetry<String>(pause = {}) { attempts++; null })
        assertEquals(3, attempts)
    }

    @Test(expected = CancellationException::class)
    fun cancelledNetworkChangeDoesNotRetry() = runBlocking {
        detectWithRetry<String>(pause = { error("Cancellation retried") }) { throw CancellationException() }
        Unit
    }
}
