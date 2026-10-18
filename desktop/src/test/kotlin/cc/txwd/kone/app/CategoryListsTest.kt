package cc.txwd.kone.app

import cc.txwd.kone.core.CoreApi
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.RuleSets
import cc.txwd.kone.core.UserRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CategoryListsTest {
    private val first = "SRS\u0001first list".toByteArray()
    private val second = "SRS\u0002second list".toByteArray()

    /** A server that answers what [next] says, and remembers what it was asked. */
    private class FakeServer {
        var next: (String?) -> ListDownload = { ListDownload.Same }
        val asked = mutableListOf<Pair<String, String?>>()
        fun fetch(url: String, etag: String?): ListDownload {
            asked += url to etag
            return next(etag)
        }
    }

    @Test
    fun keepsAListOnlyOnceItPassesEveryCheck() {
        val folder = Files.createTempDirectory("kone").toFile()
        val server = FakeServer()
        var reads = true
        val lists = CategoryLists(folder, server::fetch) { file -> file.name.endsWith(".new") && reads }
        val name = "geosite-netflix"
        assertTrue(lists.due(name, now = 0))
        server.next = { ListDownload.Got(first, "\"v1\"") }
        assertTrue(lists.update(name, now = 1_000))
        assertEquals("https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/geosite-netflix.srs" to null, server.asked.last())
        assertContentEquals(first, lists.bytes(name))
        assertEquals(ListStatus(kept = true, checked = 1_000, error = null), lists.status(name))
        assertFalse(lists.due(name, now = 1_000 + CategoryLists.DAY - 1))
        assertTrue(lists.due(name, now = 1_000 + CategoryLists.DAY))

        // Unchanged: asked with the ETag, nothing written, the time it was checked moves on.
        server.next = { ListDownload.Same }
        assertFalse(lists.update(name, now = 2_000))
        assertEquals("\"v1\"", server.asked.last().second)
        assertEquals(ListStatus(true, 2_000, null), lists.status(name))

        // Not a rule set, and then one sing-box cannot read: the copy before stays, the failure is shown.
        server.next = { ListDownload.Got("<html>blocked</html>".toByteArray(), null) }
        assertFalse(lists.update(name, now = 3_000))
        assertEquals(ListStatus(true, 2_000, "Not a rule set"), lists.status(name))
        reads = false
        server.next = { ListDownload.Got(second, "\"v2\"") }
        assertFalse(lists.update(name, now = 4_000))
        assertContentEquals(first, lists.bytes(name))
        assertFalse(File(folder, "$name.srs.new").exists())
        assertEquals("sing-box cannot read this list", lists.status(name).error)
        // A failure is tried again after an hour, not at once.
        assertFalse(lists.due(name, now = 4_000 + CategoryLists.RETRY - 1))
        assertTrue(lists.due(name, now = 4_000 + CategoryLists.DAY))

        // The server unreachable: the copy before stays.
        server.next = { throw IOException("Could not reach the list's server") }
        assertFalse(lists.update(name, now = 5_000))
        assertEquals(ListStatus(true, 2_000, "Could not reach the list's server"), lists.status(name))

        reads = true
        server.next = { ListDownload.Got(second, "\"v2\"") }
        assertTrue(lists.update(name, now = 6_000))
        assertContentEquals(second, lists.bytes(name))
        assertEquals(ListStatus(true, 6_000, null), lists.status(name))

        // Read again from its folder, as after a restart.
        val again = CategoryLists(folder, server::fetch) { true }
        assertEquals(CategoryLists.sha256(second), again.digest(name))
    }

    @Test
    fun aDamagedCopyIsNotHandedOn() {
        val folder = Files.createTempDirectory("kone").toFile()
        val lists = CategoryLists(folder, { _, _ -> ListDownload.Got(first, null) }) { true }
        assertTrue(lists.update("geoip-jp", now = 0))
        assertEquals("https://raw.githubusercontent.com/SagerNet/sing-geoip/rule-set/geoip-jp.srs", cc.txwd.kone.core.Categories.url("geoip-jp"))
        File(folder, "geoip-jp.srs").writeBytes(second)
        assertNull(lists.digest("geoip-jp"))
        assertNull(lists.bytes("geoip-jp"))
        assertTrue(lists.due("geoip-jp", now = 1))
    }

    @Test
    fun onlyListNamesAreEverAskedFor() {
        val server = FakeServer()
        val lists = CategoryLists(Files.createTempDirectory("kone").toFile(), server::fetch) { true }
        for (name in listOf("geosite-cn", "../geosite-x", "geosite-a/b", "netflix", "https://example.com/x")) assertFalse(lists.update(name, now = 0))
        assertTrue(server.asked.isEmpty())
        assertFailsWith<IllegalArgumentException> { CategoryLists.download("http://example.com/geosite-x.srs", null, null) }
    }

    @Test
    fun listsNoRuleNamesAreLetGo() {
        val folder = Files.createTempDirectory("kone").toFile()
        val lists = CategoryLists(folder, { _, _ -> ListDownload.Got(first, null) }) { true }
        lists.update("geosite-netflix", now = 0)
        lists.update("geoip-jp", now = 0)
        lists.keepOnly(setOf("geoip-jp"))
        assertFalse(File(folder, "geosite-netflix.srs").exists())
        assertNull(lists.digest("geosite-netflix"))
        assertNotNull(lists.digest("geoip-jp"))
    }

    @Test
    fun theHeaderIsCheckedAsTheServiceChecksIt() {
        assertTrue(CategoryLists.looksLikeAList(first))
        assertTrue(CategoryLists.looksLikeAList("SRS\u0005x".toByteArray()))
        assertFalse(CategoryLists.looksLikeAList("SRS\u0006x".toByteArray()))
        assertFalse(CategoryLists.looksLikeAList("SRS\u0000x".toByteArray()))
        assertFalse(CategoryLists.looksLikeAList("SRS\u0001".toByteArray()))
        assertFalse(CategoryLists.looksLikeAList(ByteArray(CategoryLists.MOST_BYTES + 1).also { "SRS\u0001".toByteArray().copyInto(it) }))
    }

    /** With a real sing-box: it reads a real list, refuses a broken one, and runs the template with a category rule. */
    @Test
    fun singBoxReadsTheListsAndTheTemplateUsesThem() {
        val core = System.getProperty("kone.singbox")?.let(::File)?.takeIf { it.canExecute() }
        assumeTrue(core != null, "no sing-box binary here (-Pkone.singbox=…, or the workflow's)")
        val rules = Files.createTempDirectory("kone").toFile()
        val path = RuleSets.ensure(rules)
        val real = File(rules, "geosite-cn.srs").readBytes()
        val sets = File(rules, "sets").apply { mkdirs() }
        File(sets, "geosite-netflix.srs").writeBytes(real)
        assertTrue(CategoryLists.singBoxReads(core!!, File(sets, "geosite-netflix.srs")))
        File(sets, "broken.srs").writeBytes(first)
        assertFalse(CategoryLists.singBoxReads(core, File(sets, "broken.srs")))

        val source = Json.parseToJsonElement("""{"outbounds": [{"type": "socks", "tag": "Example", "server": "203.0.113.1", "server_port": 1080}]}""") as JsonObject
        val routes = listOf(UserRule("set", "geosite-netflix", "proxy"), UserRule("set", "geosite-netflix", "block"))
        val config = KoneConfig.build(source, path, CoreApi(9090, "check"), tun = false, routes = routes).config.toString()
        val process = ProcessBuilder(core.path, "check", "-c", "stdin").redirectErrorStream(true).start()
        process.outputStream.use { it.write(config.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(20, TimeUnit.SECONDS))
        assertEquals(0, process.exitValue(), output)
    }
}
