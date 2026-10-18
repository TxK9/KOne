package cc.txwd.kone.console

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConsoleApiTest {
    private val console = FakeConsole(FakeConsole.source(blockClaude = true))

    @AfterTest
    fun stop() = console.close()

    @Test
    fun claimsOnceWithAnInviteCode() {
        console.invites += "invite-0001"
        val claim = console.api().claim("invite-0001", "KOne · Test computer")
        assertTrue(claim.credential.startsWith("cred-"))
        assertEquals("KOne · Test computer", console.devices[claim.credential])

        val again = assertFailsWith<ConsoleException> { console.api().claim("invite-0001", "KOne · Test computer") }
        assertEquals(ConsoleFailure.InviteInvalid, again.failure)
        assertEquals("Invitation code expired", again.shown)
    }

    @Test
    fun aFullAccountRefusesAnotherDevice() {
        console.deviceLimit = 0
        console.invites += "invite-0002"
        assertEquals(ConsoleFailure.DeviceLimit, assertFailsWith<ConsoleException> { console.api().claim("invite-0002", "Computer") }.failure)
    }

    @Test
    fun deviceNamesAreCutToSixtyCharacters() {
        console.invites += "invite-0003"
        val claim = console.api().claim("invite-0003", "X".repeat(80))
        assertEquals(60, console.devices[claim.credential]!!.length)
    }

    @Test
    fun fetchesTheSourceAndItsTerms() {
        val credential = bind()
        val delivery = console.api().config(credential)
        assertEquals("r1", delivery.revision)
        assertEquals("c1", delivery.configRevision)
        assertEquals("United States", delivery.configName)
        assertEquals(3, delivery.config["outbounds"]!!.jsonArray.size)
        assertEquals("month", delivery.policy.period)
    }

    @Test
    fun theConsoleCanTakeTheServiceBackPauseItOrHaveNothingYet() {
        val credential = bind()
        console.waiting = true
        assertEquals(ConsoleFailure.Waiting, assertFailsWith<ConsoleException> { console.api().config(credential) }.failure)
        console.waiting = false
        console.refused = true
        assertEquals(ConsoleFailure.Refused, assertFailsWith<ConsoleException> { console.api().config(credential) }.failure)
        console.refused = false
        console.revoked += credential
        assertEquals(ConsoleFailure.Revoked, assertFailsWith<ConsoleException> { console.api().config(credential) }.failure)
    }

    @Test
    fun acknowledgesAndShrugsOffAStaleRevision() {
        val credential = bind()
        console.api().ack(credential, "r1", Report.RECEIVED)
        console.api().ack(credential, "r1", Report.APPLIED)
        console.revision = "r2"
        console.api().ack(credential, "r1", Report.APPLIED) // 409: the App fetches again later, no error
        assertEquals(listOf("received", "applied"), console.acks.map { it["status"]!!.jsonPrimitive.content })
        assertFailsWith<IllegalArgumentException> { console.api().ack(credential, "r2", "done") }
    }

    @Test
    fun diagnosticsCarryOnlyKnownEvents() {
        val credential = bind()
        console.api().diagnostics(credential, "1.0.0 (desktop)", "wifi", listOf("app_started", "claude_blocked", "connection_ok"))
        val sent = console.diagnostics.single()
        assertEquals(listOf("app_started", "connection_ok"), sent["events"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("1.0.0desktop", sent["appVersion"]!!.jsonPrimitive.content)
        // A second report within two seconds is refused by the console.
        val busy = assertFailsWith<ConsoleException> { console.api().diagnostics(credential, "1.0.0", "wifi", listOf("retrying")) }
        assertEquals(ConsoleFailure.Server, busy.failure)
    }

    @Test
    fun theCredentialOnlyTravelsInTheHeader() {
        val credential = bind()
        console.api().config(credential)
        assertTrue(console.seen.none { (path, _) -> credential in path })
        assertTrue(console.seen.any { (_, auth) -> auth == "Bearer $credential" })
    }

    @Test
    fun anUnreachableConsoleIsOffline() {
        val endpoint = assertNotNull(ConsoleEndpoint.parse("https://127.0.0.1:1"))
        val failure = assertFailsWith<ConsoleException> { ConsoleApi(endpoint, timeoutMillis = 2000).claim("invite-0004", "Computer") }
        assertEquals(ConsoleFailure.Offline, failure.failure)
    }

    @Test
    fun readsClaimLinksAndBareCodes() {
        val link = assertNotNull(ClaimLink.read("  ${console.link("abcDEF-123_x")}\n", builtIn = null))
        assertEquals(console.endpoint, link.endpoint)
        assertEquals("abcDEF-123_x", link.code)
        // A link inside a pasted message is found on its own line.
        assertNotNull(ClaimLink.read("Your invitation code：\n${console.link("abcDEF-123_x")}\n15 minutes remaining", builtIn = null))
        // A bare code needs a console built into the App.
        assertNull(ClaimLink.read("abcDEF-123_x", builtIn = null))
        assertEquals(console.endpoint, ClaimLink.read("abcDEF-123_x", builtIn = console.endpoint)?.endpoint)
        assertNull(ClaimLink.read("x", builtIn = console.endpoint))
        assertNull(ClaimLink.read("kone://claim?server=https://203.0.113.5&code=has space", builtIn = null))
    }

    @Test
    fun onlyHttpsConsolesAreAccepted() {
        assertNull(ClaimLink.parse("kone://claim?server=http://203.0.113.5&code=abcdefgh"))
        assertNull(ConsoleEndpoint.parse("http://127.0.0.1:8768"))
        assertNull(ConsoleEndpoint.parse("https://user@203.0.113.5"))
        assertNull(ConsoleEndpoint.parse("https://203.0.113.5/?next=1"))
        assertEquals("https://console.example/kone", ConsoleEndpoint.parse("console.example/kone/")?.base)
        assertFalse(ClaimLink.isInviteCode("abc"))
    }

    private fun bind(): String {
        console.invites += "invite-bind"
        return console.api().claim("invite-bind", "Computer").credential
    }
}
