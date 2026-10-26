package io.nekohasekai.sfa.compose.screen.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ExitIpTest {
    @Test fun acceptsOnlyACompletePublicIpLiteral() {
        assertEquals("203.0.113.12", parseExitIp(" 203.0.113.12\n"))
        assertEquals("2001:db8::12", parseExitIp("2001:db8::12"))
    }

    @Test fun failuresAndHostnamesHaveNoDisplayValue() {
        for (value in listOf("", "Not tested", "example.com", "IP: 8.8.8.8", "{\"ip\":\"8.8.8.8\"}", "8.8.8.8\n1.1.1.1", "a".repeat(129))) {
            assertNull(value, parseExitIp(value))
        }
    }

    @Test fun rejectsInvalidAmbiguousAndPrivateAddresses() {
        for (value in listOf("256.1.2.3", "1.2.3", "01.2.3.4", ":::1", "fe80::1%wlan0", "0.0.0.0", "127.0.0.1", "10.0.0.1", "192.168.1.1", "169.254.1.1", "100.64.0.1", "224.0.0.1", "::1", "fc00::1", "fe80::1")) {
            assertNull(value, parseExitIp(value))
        }
    }

    @Test fun aFirstAnswerNeedsNoPause() = runBlocking {
        val pauses = mutableListOf<Long>()
        assertEquals("203.0.113.12", detectWithRetry(pause = { pauses += it }) { "203.0.113.12" })
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test fun failedAttemptsRetryAfterEachDelayUntilAnAnswer() = runBlocking {
        val pauses = mutableListOf<Long>()
        var attempts = 0
        val result = detectWithRetry(longArrayOf(10, 20, 30), pause = { pauses += it }) {
            attempts++
            when (attempts) {
                1 -> null
                2 -> throw java.io.IOException("tunnel not ready")
                else -> "2001:db8::12"
            }
        }
        assertEquals("2001:db8::12", result)
        assertEquals(3, attempts)
        assertEquals(listOf(10L, 20L), pauses)
    }

    @Test fun retriesAreBounded() = runBlocking {
        val pauses = mutableListOf<Long>()
        var attempts = 0
        assertNull(detectWithRetry(longArrayOf(10, 20), pause = { pauses += it }) { attempts++; null })
        assertEquals(3, attempts)
        assertEquals(listOf(10L, 20L), pauses)
    }

    @Test fun cancellationIsNotSwallowed() {
        var attempts = 0
        assertThrows(CancellationException::class.java) {
            runBlocking {
                detectWithRetry(longArrayOf(10), pause = {}) { attempts++; throw CancellationException("left") }
            }
        }
        assertEquals(1, attempts)
    }
}
