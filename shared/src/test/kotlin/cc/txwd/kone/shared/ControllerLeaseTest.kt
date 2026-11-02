package cc.txwd.kone.shared

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ControllerLeaseTest {
    private val expected = ControllerLease.Context("device", "a".repeat(64), 4, "enabled", setOf("entry", "relay"))
    private val nonce = "q".repeat(43)
    private fun reply(request: ControllerLease.Request) = ControllerLease.Reply(1, "device", "a".repeat(64), 4, "enabled", true, request.nonce, 1000.0, 1060.0, 60, expected.nodeIds)

    @Test fun `fresh lease expires including time spent waiting for HTTP`() {
        var now = 1000L
        val lease = ControllerLease { now }
        assertFalse(lease.authorized(expected))
        val request = assertNotNull(lease.begin(expected, nonce))
        now += 10_000
        assertTrue(lease.accept(request, reply(request)))
        now = 60_999
        assertTrue(lease.authorized(expected))
        now = 61_000
        assertFalse(lease.authorized(expected))
    }

    @Test fun `wrong authority context and unbounded lifetimes never authorize`() {
        for (mutate in listOf<(ControllerLease.Reply) -> ControllerLease.Reply>(
            { it.copy(nonce = "x".repeat(43)) }, { it.copy(deviceId = "other") },
            { it.copy(configRevision = "b".repeat(64)) }, { it.copy(authGeneration = 3) },
            { it.copy(nodeIds = setOf("entry")) }, { it.copy(ttlSeconds = 61) },
            { it.copy(ttlSeconds = 0) }, { it.copy(required = false) },
            { it.copy(notAfter = Double.NaN) }, { it.copy(serverTime = Double.POSITIVE_INFINITY) },
        )) {
            val lease = ControllerLease { 1000 }
            val request = assertNotNull(lease.begin(expected, nonce))
            assertFalse(lease.accept(request, mutate(reply(request))))
            assertFalse(lease.authorized(expected))
        }
    }

    @Test fun `late responses after stop or account change cannot authorize or erase a new lease`() {
        val lease = ControllerLease { 1000 }
        val old = assertNotNull(lease.begin(expected, nonce))
        lease.invalidate()
        val fresh = assertNotNull(lease.begin(expected, "r".repeat(43)))
        assertTrue(lease.accept(fresh, reply(fresh)))
        assertFalse(lease.accept(old, reply(old)))
        assertTrue(lease.authorized(expected))
        assertFalse(lease.authorized(expected.copy(deviceId = "other")))
    }

    @Test fun `sleep and clock rollback do not extend a lease`() {
        var now = 10_000L
        val lease = ControllerLease { now }
        val request = assertNotNull(lease.begin(expected, nonce))
        assertTrue(lease.accept(request, reply(request)))
        now = 9_999
        assertFalse(lease.authorized(expected))
        now = 10_000
        assertFalse(lease.authorized(expected))
        val fresh = assertNotNull(lease.begin(expected, nonce))
        now += 60_000
        assertFalse(lease.accept(fresh, reply(fresh)))
    }

    @Test fun `a shorter server interval always shortens the local deadline`() {
        var now = 1000L
        val lease = ControllerLease { now }
        val request = assertNotNull(lease.begin(expected, nonce))
        assertTrue(lease.accept(request, reply(request).copy(notAfter = 1030.0)))
        now += 30_000
        assertFalse(lease.authorized(expected))
    }

    @Test fun `missing cached controller metadata cannot manufacture authorization`() {
        val lease = ControllerLease { 1000 }
        assertNull(lease.begin(expected.copy(configRevision = ""), nonce))
        assertNull(lease.begin(expected.copy(nodeIds = emptySet()), nonce))
        assertNull(lease.begin(expected, "short"))
        assertFalse(lease.authorized(expected))
    }
}
