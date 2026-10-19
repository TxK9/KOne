package cc.txwd.kone.app

import cc.txwd.kone.console.FakeConsole
import cc.txwd.kone.core.CoreApi
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.shared.ShareLinks
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ImportsTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val folder = Files.createTempDirectory("imports").toFile()
    private val vault = Vault.Memory()
    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
    private fun base64Url(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

    @Test
    fun keptSealedNamedOnceAndTakenOut() {
        val file = File(folder, "imported.bin")
        val imports = Imports(file, vault)
        val links = ShareLinks.findAll("trojan://pw@203.0.113.20:443#Tokyo 1\nhy2://pw@203.0.113.21:443\ntrojan://pw@203.0.113.20:443#Again")
        assertEquals(2, imports.add(links.map(ShareLinks::parse)), "the same node twice is kept once")
        assertEquals(listOf("Tokyo 1", "Imported 2"), imports.all.map { it["tag"]!!.jsonPrimitive.content })
        assertTrue(!file.readText().contains("203.0.113.20"), "nothing in the clear")
        assertEquals(imports.all, Imports(file, vault).all)
        assertTrue(imports.remove("Tokyo 1"))
        assertEquals(listOf("Imported 2"), Imports(file, vault).all.map { it["tag"]!!.jsonPrimitive.content })
    }

    @Test
    fun everyKindJoinsTheConsolesNodesAndTheClaudeBlockStaysFirst() {
        val imports = Imports(File(folder, "every.bin"), vault)
        val links = listOf(
            "vless://$id@203.0.113.30:443?security=tls&type=ws&path=%2Fws#auto",
            "vless://$id@203.0.113.31:443?security=reality&pbk=${"A".repeat(43)}&type=tcp&flow=xtls-rprx-vision",
            "vmess://" + base64("""{"add":"203.0.113.32","port":443,"id":"$id","net":"tcp","tls":""}"""),
            "trojan://pw@203.0.113.33:443#proxy",
            "ss://${base64Url("aes-128-gcm:pw")}@203.0.113.34:8388",
            "hy2://pw@203.0.113.35:443",
            "tuic://$id:pw@203.0.113.36:443",
            "anytls://pw@203.0.113.37:443",
        )
        assertEquals(8, imports.add(links.map(ShareLinks::parse)))
        val source = FakeConsole.source(blockClaude = true)
        val joined = Merge.into(source, listOf(imports.all))
        val merged = joined.source
        val tags = joined.groups.single()
        val own = (source["outbounds"] as JsonArray).map { (it as JsonObject)["tag"]!!.jsonPrimitive.content }
        assertTrue(tags.none { it in own || it in KoneConfig.RESERVED }, "imported tags never take the console's or the template's: $tags")
        assertTrue("proxy 2" in tags, "a name the template uses moves aside")
        val built = KoneConfig.build(merged, "/r", CoreApi(9090, "s"))
        assertTrue(built.nodes.map { it.tag }.containsAll(tags))
        val rules = (built.config["route"] as JsonObject)["rules"] as JsonArray
        assertEquals("reject", (rules[2] as JsonObject)["action"]!!.jsonPrimitive.content, "the Claude block right after sniff and DNS")

        // sing-box itself takes every kind, as the workflow's build of it checks.
        val core = System.getProperty("kone.singbox")?.let(::File)?.takeIf { it.canExecute() } ?: return
        val file = File(folder, "config.json").apply { writeText(built.config.toString()) }
        val process = ProcessBuilder(core.path, "check", "-c", file.path).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "sing-box check did not finish")
        assertEquals(0, process.exitValue(), output)
    }
}
