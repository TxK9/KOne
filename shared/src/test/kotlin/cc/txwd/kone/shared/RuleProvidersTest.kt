package cc.txwd.kone.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RuleProvidersTest {
    @Test
    fun aListsAddressIsHttpsWithNoSignInInIt() {
        assertEquals("https://lists.example.com/rules/Video.yaml", RuleProviders.clean(" https://lists.example.com/rules/Video.yaml "))
        assertNull(RuleProviders.clean("http://lists.example.com/rules/Video.yaml"))
        assertNull(RuleProviders.clean("https://user:secret@lists.example.com/list.yaml"))
        assertNull(RuleProviders.clean("https://lists.example.com/list.yaml#part"))
        assertNull(RuleProviders.clean("https://lists.example.com/a list.yaml"))
        assertNull(RuleProviders.clean("file:///data/list.srs"))
        assertEquals("Video", RuleProviders.title("https://lists.example.com/rules/Video.yaml?token=x"))
        assertEquals("lists.example.com", RuleProviders.title("https://lists.example.com/"))
        assertTrue(RuleProviders.isTag(RuleProviders.tag("https://lists.example.com/rules/Video.yaml")))
        assertFalse(Rules.isSetName(RuleProviders.tag("https://lists.example.com/rules/Video.yaml")))
    }

    @Test
    fun aClashListBecomesASingBoxSourceList() {
        val payload = """
            payload:
              - DOMAIN-SUFFIX,example.com
              - 'DOMAIN,www.example.net'
              - DOMAIN-KEYWORD,tracker
              - IP-CIDR,192.0.2.0/24,no-resolve
              - IP-CIDR6,2001:db8::/32
              - PROCESS-NAME,example.exe
              - "+.example.org"
              - "*.example.edu"
        """.trimIndent()
        val list = RuleProviders.clash(payload)!!
        assertEquals(2, list["version"]!!.jsonPrimitive.int)
        val rules = list["rules"] as JsonArray
        val names = rules[0] as JsonObject
        assertEquals("[\"www.example.net\"]", names["domain"].toString())
        assertEquals("[\"example.com\",\"example.org\"]", names["domain_suffix"].toString())
        assertEquals("[\"tracker\"]", names["domain_keyword"].toString())
        assertEquals("[\"^[^.]+\\\\.example\\\\.edu\$\"]", names["domain_regex"].toString())
        assertEquals("[\"192.0.2.0/24\",\"2001:db8::/32\"]", (rules[1] as JsonObject)["ip_cidr"].toString())
        // One entry a line, as plain lists give them; nothing taken is no list.
        assertEquals("[\"example.com\"]", ((RuleProviders.clash(".example.com\n198.51.100.7\n")!!["rules"] as JsonArray)[0] as JsonObject)["domain_suffix"].toString())
        assertNull(RuleProviders.clash("payload:\n  - USER-AGENT,Example*\n"))
    }

    @Test
    fun aListIsReadAsBinarySourceOrClash() {
        assertEquals(RuleProviders.BINARY, RuleProviders.read(byteArrayOf('S'.code.toByte(), 'R'.code.toByte(), 'S'.code.toByte(), 1, 0))!!.format)
        assertEquals(RuleProviders.SOURCE, RuleProviders.read("""{"version":2,"rules":[{"domain_suffix":["example.com"]}]}""".toByteArray())!!.format)
        assertNull(RuleProviders.read("""{"version":9,"rules":[]}""".toByteArray()))
        val clash = RuleProviders.read("DOMAIN-SUFFIX,example.com\n".toByteArray())!!
        assertEquals(RuleProviders.SOURCE, clash.format)
        assertEquals("json", clash.extension)
        assertNotNull(Json.parseToJsonElement(clash.bytes.toString(Charsets.UTF_8)))
        assertNull(RuleProviders.read("<html>Not found</html>".toByteArray()))
    }

    @Test
    fun aListIsKeptOnlyOnceItIsReadAndAFailureLeavesTheLastGoodCopy() {
        val folder = Files.createTempDirectory("own-lists").toFile()
        val url = "https://lists.example.com/rules/Video.yaml"
        var body: () -> ListDownload = { ListDownload.Got("DOMAIN-SUFFIX,example.com\n".toByteArray(), "\"v1\"") }
        var formats = mutableListOf<String>()
        val lists = OwnLists(folder, { _, _ -> body() }, { _, format -> formats += format; true })
        assertTrue(lists.due(url, 0))
        assertTrue(lists.update(url, 1_000))
        assertEquals(listOf(RuleProviders.SOURCE), formats)
        val kept = lists.file(url)!!
        assertEquals("${RuleProviders.tag(url)}.json", kept.name)
        assertFalse(lists.due(url, 2_000))
        assertTrue(lists.due(url, 1_000 + CategoryLists.DAY))
        // A failed download keeps the copy, says why, and waits an hour.
        body = { throw IOException("Could not reach the list's server") }
        assertFalse(lists.update(url, 1_000 + CategoryLists.DAY))
        assertEquals(kept, lists.file(url))
        assertEquals("Could not reach the list's server", lists.status(url).error)
        assertFalse(lists.due(url, 1_000 + CategoryLists.DAY + 60_000))
        // Something that is not a list is not kept over it.
        body = { ListDownload.Got("<html></html>".toByteArray(), null) }
        assertFalse(lists.update(url, 1_000 + 2 * CategoryLists.DAY))
        assertTrue(lists.status(url).kept)
        // The index is read back as kept; a list no rule names is let go.
        assertEquals(kept, OwnLists(folder, { _, _ -> body() }, { _, _ -> true }).file(url))
        lists.keepOnly(emptySet())
        assertNull(lists.file(url))
        assertFalse(kept.exists())
        folder.deleteRecursively()
    }

    @Test
    fun picksAreKeptWhereTheAppOffersThemAndTheRuleGoesThroughANode() {
        val rules = listOf(
            Rule("suffix", "example.com", "proxy", Policies.FALLBACK),
            Rule("suffix", "example.net", "via:JP", Policies.FASTEST),
            Rule("suffix", "example.org", "direct", Policies.FASTEST),
            Rule("url", "https://lists.example.com/rules/Video.yaml", "via:US", Policies.FALLBACK),
        )
        assertEquals(listOf(Policies.FALLBACK, "", "", Policies.FALLBACK), Rules.clean(rules, Rules.ANDROID, picks = true).map { it.pick })
        // Windows offers neither picks nor own lists yet: its rules are as they always were.
        assertEquals(
            listOf(Rule("suffix", "example.com", "proxy"), Rule("suffix", "example.net", "via:JP"), Rule("suffix", "example.org", "direct")),
            Rules.clean(rules, Rules.WINDOWS),
        )
        assertEquals("""{"kind":"suffix","value":"example.com","action":"proxy"}""", Rule("suffix", "example.com", "proxy").toJson().toString())
        assertEquals(rules[0], Rule.fromJson(rules[0].toJson()))
        assertEquals(Rules.MOST_URLS, Rules.clean((0 until 40).map { Rule("url", "https://lists.example.com/$it.yaml", "proxy") }, Rules.ANDROID).size)
    }

    @Test
    fun aGroupPicksAsItsPickSays() {
        val fastest = Policies.group("Fastest", listOf("a", "b"), Policies.FASTEST)
        assertEquals(50, fastest["tolerance"]!!.jsonPrimitive.int)
        val first = Policies.group("First that answers", listOf("a", "b"), Policies.FALLBACK)
        assertEquals(Policies.FALLBACK_TOLERANCE, first["tolerance"]!!.jsonPrimitive.int)
        assertTrue(Policies.FALLBACK_TOLERANCE + 15_000 <= 65_535)
        assertEquals("Through Japan · first that answers", Policies.countryTag("Through Japan", Policies.FALLBACK))
        assertEquals("Through Japan", Policies.countryTag("Through Japan", Policies.FASTEST))
        assertTrue(Policies.isGroup("Through Japan · first that answers"))
        assertFalse(Policies.isGroup("Through Japan"))
    }
}
