package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.Check
import cc.txwd.kone.shared.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneDiagnosisTest {
    private val fine = listOf(
        PhoneDiagnosis.network(PhoneDiagnosis.Network.Open), PhoneDiagnosis.otherVpn(false), PhoneDiagnosis.privateDns(null),
        PhoneDiagnosis.battery(true), PhoneDiagnosis.dataSaver(false), PhoneDiagnosis.names(2, 2),
        PhoneDiagnosis.clock(3)!!, PhoneDiagnosis.sites(listOf("Google" to 80, "GitHub" to 120)),
    )

    private fun with(vararg changed: Check) = fine.map { check -> changed.firstOrNull { it.title == check.title } ?: check }

    @Test
    fun nothingToFixWhenAllIsWell() {
        assertNull(PhoneDiagnosis.advice(fine))
        assertEquals("Google 80 ms · GitHub 120 ms", fine.last().detail)
    }

    @Test
    fun thePhonesOwnCausesComeFirst() {
        // A sign-in page explains everything after it.
        assertEquals(
            "Open the network's sign-in page from Android's notification, then connect again",
            PhoneDiagnosis.advice(with(PhoneDiagnosis.network(PhoneDiagnosis.Network.SignIn), PhoneDiagnosis.sites(listOf("Google" to null)))),
        )
        // The clock before nodes: no node can fix it.
        assertEquals(
            "The phone's clock is 10 min ahead: turn on Set time automatically (Settings → System → Date & time)",
            PhoneDiagnosis.advice(with(PhoneDiagnosis.clock(600)!!, PhoneDiagnosis.sites(listOf("Google" to null)))),
        )
        assertEquals(
            "Turn the other VPN app off: Android lets one VPN run at a time",
            PhoneDiagnosis.advice(with(PhoneDiagnosis.otherVpn(true))),
        )
        // Names failing with Private DNS on: Private DNS is the cause.
        assertEquals(
            "Set Private DNS to Automatic: its provider is not answering through KOne",
            PhoneDiagnosis.advice(with(PhoneDiagnosis.privateDns("dns.example.com"), PhoneDiagnosis.names(0, 2))),
        )
        assertEquals(
            "Let KOne run unrestricted in the background (Settings → Service), so Android does not stop it",
            PhoneDiagnosis.advice(with(PhoneDiagnosis.battery(false), PhoneDiagnosis.dataSaver(true))),
        )
        assertEquals(Outcome.Warn, PhoneDiagnosis.sites(listOf("Google" to 90, "GitHub" to null)).outcome)
    }
}
