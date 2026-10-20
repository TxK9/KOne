package cc.txwd.kone.app

import cc.txwd.kone.core.ServiceLink
import cc.txwd.kone.core.UserRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuleBookTest {
    private val folder = Files.createTempDirectory("kone-rules").toFile()
    private val preferences = Preferences(folder)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var restarts = 0
    private var bypassSaid = 0
    private val keeper = ListKeeper(scope, object : ListKeeper.Host {
        override val rules get() = preferences.rules
        override val folder get() = this@RuleBookTest.folder.path
        override val service: ServiceLink? get() = null
        override val proxied: Int? get() = null
        override val online get() = false
        override fun restart() {}
        override fun changed() {}
    })
    private val book = RuleBook(scope, preferences, keeper, object : RuleBook.Host {
        override fun restart() {
            restarts++
        }
        override fun bypassChanged() {
            bypassSaid++
        }
    })

    @Test
    fun aRuleCanGoThroughACountryTheNodesSay() {
        book.countriesChanged(listOf("US", "JP"))
        assertEquals(listOf("US", "JP"), book.page.countries)
        assertTrue(book.add(UserRule("suffix", "example.com", "via:JP")))
        // A country no node says yet is kept too: it goes through the node in use until one does.
        assertTrue(book.add(UserRule("suffix", "example.org", "via:SG")))
        assertFalse(book.add(UserRule("suffix", "example.net", "via:Japan")))
        assertEquals(listOf(UserRule("suffix", "example.com", "via:JP"), UserRule("suffix", "example.org", "via:SG")), preferences.rules)
        assertEquals(2, restarts)
        // The same countries again change nothing.
        val shown = book.page
        book.countriesChanged(listOf("US", "JP"))
        assertTrue(shown === book.page)
    }

    @Test
    fun aRuleChangesItsWayWhereItStandsAndRulesCopyAsClashLines() {
        assertTrue(book.add(UserRule("suffix", "example.com", "proxy")))
        assertTrue(book.add(UserRule("keyword", "tracker", "block")))
        val before = restarts
        book.setAction(0, "via:JP")
        assertEquals(listOf(UserRule("suffix", "example.com", "via:JP"), UserRule("keyword", "tracker", "block")), preferences.rules)
        assertEquals(before + 1, restarts)
        // The same way, a way that is not one, a rule that is not there: nothing changes.
        book.setAction(0, "via:JP")
        book.setAction(1, "via:jp")
        book.setAction(5, "direct")
        assertEquals(before + 1, restarts)
        var copied: String? = null
        book.copy { copied = it }
        assertEquals("DOMAIN-SUFFIX,example.com,Japan\nDOMAIN-KEYWORD,tracker,REJECT", copied)
        assertEquals("2 rules copied, as Clash rules", book.page.notice)
    }

    @Test
    fun eachChangeIsKeptShownAndTakenByTheConnection() {
        assertTrue(book.addSite("https://www.example.com/path", direct = true))
        assertEquals(listOf("www.example.com"), book.page.direct)
        // Moved to the other list, not doubled.
        assertTrue(book.addSite("www.example.com", direct = false))
        assertEquals(emptyList(), book.page.direct)
        assertEquals(listOf("www.example.com"), preferences.proxied)
        assertFalse(book.addSite("not a site", direct = true))
        book.setBypass("Steam.exe", straight = true)
        book.setBypass("steam.exe", straight = true) // already there: nothing changes
        assertEquals(1, bypassSaid)
        assertEquals(3, restarts)

        assertTrue(book.add(UserRule("suffix", "Example.ORG", "direct")))
        assertTrue(book.add(UserRule("port", "443", "block")))
        assertFalse(book.add(UserRule("port", "not a port", "block")))
        book.move(1, up = true)
        assertEquals(listOf(UserRule("port", "443", "block"), UserRule("suffix", "example.org", "direct")), book.page.rules)
        book.remove(0)
        assertEquals(listOf(UserRule("suffix", "example.org", "direct")), preferences.rules)
        assertEquals(7, restarts)
    }

    @Test
    fun anImportIsOneReconnectionAndSaysWhatItDid() {
        val said = book.import("- DOMAIN-SUFFIX,example.com,Proxy\n- DOMAIN-KEYWORD,tracker,REJECT\n- RULE-SET,custom,Proxy")
        assertEquals("2 rules added; 1 left out", said)
        assertEquals(1, restarts)
        assertEquals("Block ads turned on", book.import("""[{"outboundTag":"block","domain":["geosite:category-ads-all"]}]"""))
        assertTrue(book.page.blockAds)
        assertEquals(2, restarts)
    }
}
