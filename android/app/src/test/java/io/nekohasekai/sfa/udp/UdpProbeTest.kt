package io.nekohasekai.sfa.udp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class UdpProbeTest {
    private val target=UdpTarget(7,"\u6d4b\u8bd5\u8282\u70b9","actual-node",3,40,false)
    @Test fun exitDisplayKeepsTheAddressAndOmitsOnlyThePort() {
        assertEquals("203.0.113.10",UdpReply("203.0.113.10:50000",1).ip)
        assertEquals("2001:db8::1",UdpReply("[2001:db8::1]:50000",1).ip)
    }
    @Test fun validReplyIsRequiredAndSecondServerCanRecoverFailure() = runBlocking<Unit> {
        val tags=mutableListOf<String>();var calls=0
        val probe=UdpProbe({tag,_-> tags+=tag;calls++;if(calls==1)error("unavailable") else UdpReply("203.0.113.10:1234",210)})
        val r=probe.measure(target)
        assertTrue(r.passed);assertEquals(2,r.attempts);assertEquals(listOf("actual-node","actual-node"),tags)
    }
    @Test fun timeoutIsFailedMeasurementAndNotUnsupportedOrDirectFallback() = runBlocking<Unit> {
        val r=UdpProbe({_,_->delay(1000);UdpReply("203.0.113.10:1234",1)},100,20).measure(target)
        assertFalse(r.passed);assertNull(r.reply);assertEquals(2,r.attempts)
        var calls=0
        val empty=UdpProbe({_,_->calls++;UdpReply("",0)}).measure(target)
        assertFalse(empty.passed);assertEquals(2,calls)
        assertFalse(UdpProbe({_,_->error("must not run")}).measure(target.copy(tcpOnly=true)).passed)
    }
    @Test fun cancelAndNewTargetRejectLateRepliesAndRapidTapsRunOnlyOnce() = runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val probe=UdpProbe({_,_->calls.incrementAndGet();entered.complete(Unit);withContext(NonCancellable){release.await()};UdpReply("203.0.113.10:1234",200)})
        val check=UdpCheckController(scope,probe)
        try {
            check.bind(target);repeat(20){check.start()}
            withTimeout(1000){entered.await()};assertEquals(1,calls.get())
            val switched=target.copy(epoch=4,network=41)
            check.bind(switched);release.complete(Unit);delay(100)
            assertEquals(switched,check.state.value.target);assertEquals(UdpPhase.Idle,check.state.value.phase)
            check.bind(null);check.start();assertNull(check.state.value.target);assertEquals(1,calls.get())
        } finally {scope.cancel()}
    }
    @Test fun successCanBeRetestedAndCancellationNeverLeavesRunningState() = runBlocking<Unit> {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        val check=UdpCheckController(scope,UdpProbe({_,_->UdpReply("203.0.113.10:1234",120)}))
        try {
            check.bind(target);check.start()
            withTimeout(1000){check.state.first{it.phase==UdpPhase.Passed}}
            assertEquals(120,check.state.value.measurement!!.reply!!.latencyMs)
            check.cancel();assertEquals(UdpPhase.Idle,check.state.value.phase)
            check.start();withTimeout(1000){check.state.first{it.phase==UdpPhase.Passed}}
        } finally {scope.cancel()}
    }
}
