package io.nekohasekai.sfa.compose.screen.tools

import io.nekohasekai.sfa.udp.RunningUdpProfile
import io.nekohasekai.sfa.udp.UdpTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class NetworkTestTargetTest {
    private val current = RunningUdpProfile(
        7, "Current node",
        """{"outbounds":[{"type":"socks","tag":"proxy"}],"route":{"final":"proxy"}}""",
        3, 40,
    )

    @Test fun unifiedEntryUsesOnlyTheRunningLocalProfile() {
        val profile = udpProfileForTest(null, current, serviceStarted = true, remote = false)
        assertSame(current, profile)
        assertEquals(7L, UdpTargets.resolve(profile!!, emptyMap())?.profileId)
    }

    @Test fun restoredNodeEntryCannotSilentlyFollowAnotherConnectedNode() {
        assertNull(udpProfileForTest(41, current, serviceStarted = true, remote = false))
        assertSame(current, udpProfileForTest(7, current, serviceStarted = true, remote = false))
    }

    @Test fun stoppedRemoteAndMissingRuntimeNeverProvideATarget() {
        assertNull(udpProfileForTest(null, current, serviceStarted = false, remote = false))
        assertNull(udpProfileForTest(null, current, serviceStarted = true, remote = true))
        assertNull(udpProfileForTest(null, null, serviceStarted = true, remote = false))
    }

    @Test fun directRuntimeStillCannotFallBackToDirectUdpTesting() {
        val direct = current.copy(config = """{"outbounds":[{"type":"direct","tag":"direct"}],"route":{"final":"direct"}}""")
        val profile = udpProfileForTest(null, direct, serviceStarted = true, remote = false)
        assertNull(UdpTargets.resolve(profile!!, emptyMap()))
    }
}
