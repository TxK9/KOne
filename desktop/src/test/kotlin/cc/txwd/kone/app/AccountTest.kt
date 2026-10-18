package cc.txwd.kone.app

import cc.txwd.kone.console.ClaimLink
import cc.txwd.kone.console.ConsoleApi
import cc.txwd.kone.console.ConsoleException
import cc.txwd.kone.console.ConsoleFailure
import cc.txwd.kone.console.FakeConsole
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountTest {
    private val console = FakeConsole(FakeConsole.source(blockClaude = true))
    private val folder = Files.createTempDirectory("kone-account").toFile().apply { deleteOnExit() }
    private val vault = Vault.Memory()

    private fun account(accepts: Boolean = true) =
        Account(BindingStore(folder, vault), "KOne · Test computer", api = { ConsoleApi(it, tls = console.tls) }, accepts = { accepts })

    @AfterTest
    fun stop() = console.close()

    @Test
    fun aKeyBindsThisComputerAndBringsItsNodes() {
        console.invites += "invite-1000"
        val account = account()
        assertEquals(Sync.Updated, account.claim(ClaimLink.parse(console.link("invite-1000"))!!))
        val binding = assertNotNull(account.binding)
        assertEquals(console.endpoint.base, binding.server)
        assertEquals("r1", binding.revision)
        assertNotNull(binding.source)
        assertEquals(listOf("received", "applied"), console.acks.map { it["status"]!!.jsonPrimitive.content })

        // On disk the credential and the nodes are sealed, never in the clear.
        val written = File(folder, "binding.json").readText()
        assertFalse(binding.credential in written)
        assertFalse("203.0.113.10" in written)

        // The next start finds the same binding.
        assertEquals(binding, BindingStore(folder, vault).load())
    }

    @Test
    fun aWrongKeyLeavesNothingBehind() {
        val account = account()
        val failure = assertFailsWith<ConsoleException> { account.claim(ClaimLink.parse(console.link("invite-none"))!!) }
        assertEquals(ConsoleFailure.InviteInvalid, failure.failure)
        assertNull(account.binding)
        assertFalse(File(folder, "binding.json").exists())
    }

    @Test
    fun anUnchangedConfigIsLeftAlone() {
        val account = bound()
        assertEquals(Sync.Unchanged, account.sync())
        console.revision = "r2"
        assertEquals(Sync.Updated, account.sync())
        assertEquals("r2", account.binding!!.revision)
    }

    @Test
    fun aSourceTheCoreRefusesIsReportedAndTheOldOneKept() {
        val account = bound()
        val before = account.binding!!
        console.revision = "r2"
        console.configRevision = "c2"
        val refusing = Account(BindingStore(folder, vault), "KOne · Test computer", api = { ConsoleApi(it, tls = console.tls) }, accepts = { false })
        assertEquals(Sync.Failed, refusing.sync())
        assertEquals(before.source, refusing.binding!!.source)
        assertEquals("failed", console.acks.last()["status"]!!.jsonPrimitive.content)
        assertEquals("config_invalid", console.acks.last()["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun whenTheConsoleTakesTheServiceBackTheBindingGoes() {
        val account = bound()
        console.waiting = true
        assertEquals(Sync.Waiting, account.sync())
        assertNotNull(account.binding)
        console.waiting = false
        console.revoked += account.binding!!.credential
        assertEquals(Sync.Revoked, account.sync())
        assertNull(account.binding)
        assertNull(BindingStore(folder, vault).load())
    }

    @Test
    fun aBindingFromAnotherComputerDoesNotOpen() {
        bound()
        assertNull(BindingStore(folder, Vault.Memory()).load())
    }

    @Test
    fun theChosenNodeIsRemembered() {
        val account = bound()
        account.select("\u65e5\u672c")
        assertEquals("\u65e5\u672c", BindingStore(folder, vault).load()!!.selected)
        assertTrue(account.binding!!.selected == "\u65e5\u672c")
    }

    private fun bound(): Account {
        console.invites += "invite-bound"
        return account().also { it.claim(ClaimLink.parse(console.link("invite-bound"))!!) }
    }
}
