package cc.txwd.kone.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class NodeIdentityTest {
    private val id = "00000000-0000-0000-0000-000000000001"

    /** A subscription's body read as both Apps read it: tagged, unique within the list. */
    private fun read(vararg links: String) = SubscriptionContent.read(links.joinToString("\n")).nodes

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    private fun tags(nodes: List<JsonObject>) = nodes.map { it.text("tag") }

    private fun serverOf(tag: String?, nodes: List<JsonObject>) = nodes.first { it.text("tag") == tag }.text("server")

    @Test
    fun aNodeIsTheSameWhateverItsNameOrFieldOrder() {
        val first = Json.parseToJsonElement("""{"type":"trojan","tag":"A","server":"203.0.113.1","server_port":443,"password":"pw","tls":{"enabled":true,"server_name":"t.invalid"}}""").jsonObject
        val again = Json.parseToJsonElement("""{"tls":{"server_name":"t.invalid","enabled":true},"password":"pw","server_port":443,"server":"203.0.113.1","tag":"B","type":"trojan"}""").jsonObject
        assertEquals(NodeIdentity.key(first), NodeIdentity.key(again))
        assertNotEquals(NodeIdentity.key(first), NodeIdentity.key(JsonObject(first + ("password" to JsonPrimitive("other")))))
    }

    @Test
    fun aNodeListedTwiceIsUsedOnce() {
        val nodes = read("trojan://pw@203.0.113.1:443#HK 01", "trojan://pw@203.0.113.2:443#JP 01", "trojan://pw@203.0.113.1:443#HK 01 backup", "trojan://pw@203.0.113.1:443?security=tls#HK 01")
        // Written another way, the same node: share links alone tell only identical lines apart.
        assertEquals(listOf("HK 01", "JP 01", "HK 01 backup", "HK 01 2"), tags(nodes))
        val distinct = NodeIdentity.distinct(nodes)
        assertEquals(listOf("HK 01", "JP 01"), tags(distinct.nodes))
        assertEquals(mapOf("HK 01 backup" to "HK 01", "HK 01 2" to "HK 01"), distinct.twins)
        // Nothing listed twice: the list as it was.
        assertEquals(distinct.nodes, NodeIdentity.distinct(distinct.nodes).nodes)
        assertEquals(emptyMap(), NodeIdentity.distinct(distinct.nodes).twins)
    }

    @Test
    fun aChainThroughATwinGoesThroughTheTwinKept() {
        val body = """
            {"outbounds": [
              {"type": "shadowsocks", "tag": "relay", "server": "203.0.113.6", "server_port": 8388, "method": "aes-128-gcm", "password": "x"},
              {"type": "shadowsocks", "tag": "relay copy", "server": "203.0.113.6", "server_port": 8388, "method": "aes-128-gcm", "password": "x"},
              {"type": "trojan", "tag": "exit", "server": "203.0.113.7", "server_port": 443, "password": "y", "detour": "relay"},
              {"type": "trojan", "tag": "exit again", "server": "203.0.113.7", "server_port": 443, "password": "y", "detour": "relay copy"},
              {"type": "trojan", "tag": "further", "server": "203.0.113.8", "server_port": 443, "password": "z", "detour": "exit again"}
            ]}
        """.trimIndent()
        val distinct = NodeIdentity.distinct(SubscriptionContent.read(body).nodes)
        // The second exit is the first's twin once its relay is the relay kept; a chain through it follows.
        assertEquals(listOf("relay", "exit", "further"), tags(distinct.nodes))
        assertEquals("relay", distinct.nodes[1].text("detour"))
        assertEquals("exit", distinct.nodes[2].text("detour"))
        assertEquals(mapOf("relay copy" to "relay", "exit again" to "exit"), distinct.twins)
    }

    @Test
    fun aNoticeCopiedFromARealNodeNeverStandsInForIt() {
        // Panels often write their notices as copies of the first node under another name.
        val nodes = read("trojan://pw@203.0.113.1:443#剩余流量：100 GB", "trojan://pw@203.0.113.1:443#套餐到期：2026-12-31", "trojan://pw@203.0.113.1:443#HK 01", "trojan://pw@203.0.113.2:443#JP 01")
        val kept = NodeIdentity.distinct(NodeFilter.apply(nodes, "").nodes)
        assertEquals(listOf("HK 01", "JP 01"), tags(kept.nodes))
    }

    @Test
    fun theChoiceFollowsItsServerWhenAProviderReordersNamesakes() {
        val before = read("trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo")
        assertEquals("203.0.113.2", serverOf("Tokyo 2", before))
        val after = read("trojan://pw@203.0.113.2:443#Tokyo", "trojan://pw@203.0.113.1:443#Tokyo")
        // Kept by its tag, the choice would now be the other server.
        assertEquals("203.0.113.1", serverOf("Tokyo 2", after))
        assertEquals("Tokyo", NodeIdentity.follow("Tokyo 2", before, after))
        assertEquals(mapOf("Tokyo" to "Tokyo 2", "Tokyo 2" to "Tokyo"), NodeIdentity.renamed(before, after))
    }

    @Test
    fun theChoiceStaysOnANamesakeWhoseTwinWasRemoved() {
        val before = read("trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo")
        val after = read("trojan://pw@203.0.113.2:443#Tokyo")
        assertEquals("Tokyo", NodeIdentity.follow("Tokyo 2", before, after))
        assertNull(NodeIdentity.follow("Tokyo", before, after))
    }

    @Test
    fun aRenamedNodeIsFollowed() {
        val before = read("trojan://pw@203.0.113.1:443#JP 01 | 1.0x", "trojan://pw@203.0.113.2:443#US 01")
        val after = read("trojan://pw@203.0.113.2:443#US 01", "trojan://pw@203.0.113.1:443#JP 01 | 1.5x")
        assertEquals("JP 01 | 1.5x", NodeIdentity.follow("JP 01 | 1.0x", before, after))
        assertEquals("US 01", NodeIdentity.follow("US 01", before, after))
        assertEquals(mapOf("JP 01 | 1.0x" to "JP 01 | 1.5x"), NodeIdentity.renamed(before, after))
    }

    @Test
    fun aNodeWithNewSettingsIsFollowedByItsServerAndAccount() {
        val before = read("vless://$id@203.0.113.1:443?security=tls&sni=a.invalid&type=ws&path=%2Fa#Osaka", "trojan://pw@203.0.113.2:443#Seoul")
        val after = read("trojan://pw@203.0.113.2:443#Seoul", "vless://$id@203.0.113.1:443?security=tls&sni=b.invalid&type=ws&path=%2Fb#Osaka IPLC")
        assertEquals("Osaka IPLC", NodeIdentity.follow("Osaka", before, after))
        // Another account on the same server is another node.
        val other = read("vless://00000000-0000-0000-0000-000000000002@203.0.113.1:443?security=tls&sni=b.invalid#Osaka IPLC")
        assertNull(NodeIdentity.follow("Osaka", before, other))
    }

    @Test
    fun aNodeMovedToANewAddressKeepsItsName() {
        val before = read("trojan://pw@203.0.113.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin")
        val after = read("trojan://pw@198.51.100.1:443#Paris", "trojan://pw@203.0.113.2:443#Berlin")
        assertEquals("Paris", NodeIdentity.follow("Paris", before, after))
        assertEquals(emptyMap(), NodeIdentity.renamed(before, after))
    }

    @Test
    fun aNameNowCarriedByAnotherServerIsNotFollowed() {
        // The chosen node is gone and its tag now names a node that was the other namesake.
        val before = read("trojan://pw@203.0.113.1:443#Tokyo", "trojan://pw@203.0.113.2:443#Tokyo")
        val after = read("trojan://pw@198.51.100.9:443#Tokyo", "trojan://pw@203.0.113.1:443#Tokyo")
        assertEquals("203.0.113.1", serverOf("Tokyo 2", after))
        assertNull(NodeIdentity.follow("Tokyo 2", before, after))
        assertEquals(mapOf("Tokyo" to "Tokyo 2"), NodeIdentity.renamed(before, after))
    }

    @Test
    fun nodesSharingAnAddressAreNotMistakenForOneAnother() {
        // A CDN in front: one address and one account for every node, told apart by their names on TLS.
        fun cdn(sni: String, path: String, name: String) = "vless://$id@203.0.113.50:443?security=tls&sni=$sni&type=ws&host=$sni&path=%2F$path#$name"
        val before = read(cdn("us.invalid", "a", "US"), cdn("jp.invalid", "a", "JP"))
        val after = read(cdn("us.invalid", "b", "US"))
        assertNull(NodeIdentity.follow("JP", before, after))
        assertEquals("US", NodeIdentity.follow("US", before, after))
        // Changed in place and kept by name, it is still followed.
        assertEquals("JP", NodeIdentity.follow("JP", before, read(cdn("us.invalid", "a", "US"), cdn("jp.invalid", "b", "JP"))))
    }

    @Test
    fun aChoiceOfATwinFollowsTheTwinKept() {
        val nodes = read("trojan://pw@203.0.113.1:443#HK 01", "trojan://pw@203.0.113.1:443#HK 01 Netflix")
        val kept = NodeIdentity.distinct(nodes).nodes
        assertEquals("HK 01", NodeIdentity.follow("HK 01 Netflix", nodes, kept))
    }

    @Test
    fun tagsThatAreNotNodesOfTheListComeBackAsTheyAre() {
        val before = read("trojan://pw@203.0.113.1:443#A")
        assertEquals("Automatic", NodeIdentity.follow("Automatic", before, emptyList()))
        assertNull(NodeIdentity.follow("A", before, emptyList()))
    }
}
