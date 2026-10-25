package io.nekohasekai.sfa.udp

import org.junit.Assert.*
import org.junit.Test

class UdpTargetsTest {
    private fun profile(nodes: String, final: String = "proxy") = RunningUdpProfile(7,"\u6d4b\u8bd5\u8282\u70b9", """{"route":{"final":"$final"},"outbounds":[$nodes]}""",12,40)
    @Test fun selectedProxyUsesActualChoiceAndNeverFallsBackToDirectOrSavedDefault() {
        val p=profile("""{"type":"selector","tag":"proxy","default":"a","outbounds":["a","b","direct"]},{"type":"vless","tag":"a","transport":{"type":"ws"}},{"type":"hysteria2","tag":"b"},{"type":"direct","tag":"direct"}""")
        assertNull(UdpTargets.resolve(p,emptyMap()))
        assertEquals("b",UdpTargets.resolve(p,mapOf("proxy" to "b"))!!.tag)
        assertNull(UdpTargets.resolve(p,mapOf("proxy" to "direct")))
        assertNull(UdpTargets.resolve(p,mapOf("proxy" to "missing")))
        assertFalse(UdpTargets.resolve(p,mapOf("proxy" to "a"))!!.tcpOnly)
    }
    @Test fun explicitTcpOnlyAndNestedSelectorsAreHandledWithoutChangingConfig() {
        val p=profile("""{"type":"selector","tag":"proxy","outbounds":["nested"]},{"type":"selector","tag":"nested","outbounds":["a"]},{"type":"vless","tag":"a","network":"tcp","detour":"relay"},{"type":"vless","tag":"relay"}""")
        val target=UdpTargets.resolve(p,mapOf("proxy" to "nested","nested" to "a"))!!
        assertEquals("a",target.tag);assertTrue(target.tcpOnly)
        assertEquals(7,target.profileId);assertEquals(12,target.epoch);assertEquals(40,target.network)
        assertTrue(p.config.contains("relay"))
    }
    @Test fun noDefaultProxyAndCyclesFailClosed() {
        assertNull(UdpTargets.resolve(profile("""{"type":"direct","tag":"direct"},{"type":"vless","tag":"unused"}""","direct"),emptyMap()))
        assertNull(UdpTargets.resolve(profile("""{"type":"selector","tag":"proxy","outbounds":["proxy"]}"""),mapOf("proxy" to "proxy")))
        val p=profile("""{"type":"vless","tag":"a","network":["tcp","udp"]}""","a")
        assertFalse(UdpTargets.resolve(p,emptyMap())!!.tcpOnly)
    }
    @Test fun reloadAndPhysicalNetworkChangesInvalidateTheRunningIdentity() {
        try {
            UdpRuntime.changing();UdpRuntime.started(7,"Node","{}")
            val before=UdpRuntime.active.value!!
            UdpRuntime.networkChanged(before.network+1)
            assertNotEquals(before.epoch,UdpRuntime.active.value!!.epoch)
            UdpRuntime.changing();assertNull(UdpRuntime.active.value)
            UdpRuntime.started(7,"Node","{}")
            assertNotEquals(before.epoch,UdpRuntime.active.value!!.epoch)
        } finally { UdpRuntime.changing() }
    }
}
