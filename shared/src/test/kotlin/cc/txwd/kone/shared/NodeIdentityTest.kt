package cc.txwd.kone.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeIdentityTest {
    private val id = "00000000-0000-0000-0000-000000000001"

    /** A subscription's body read as both Apps read it: tagged, unique within the list. */
    private fun read(vararg links: String) = SubscriptionContent.read(links.joinToString("\n")).nodes

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    private fun tags(nodes: List<JsonObject>) = nodes.map { it.text("tag") }

    private fun serverOf(tag: String?, nodes: List<JsonObject>) = nodes.first { it.text("tag") == tag }.text("server")

    private fun outbound(json: String) = Json.parseToJsonElement(json).jsonObject

    private val trojan = outbound("""{"type":"trojan","tag":"A","server":"203.0.113.1","server_port":443,"password":"pw","tls":{"enabled":true,"server_name":"t.invalid"}}""")

    @Test
    fun aNodeIsTheSameWhateverItsNameOrFieldOrder() {
        val again = outbound("""{"tls":{"server_name":"t.invalid","enabled":true},"password":"pw","server_port":443,"server":"203.0.113.1","tag":"B","type":"trojan"}""")
        assertEquals(NodeIdentity.key(trojan), NodeIdentity.key(again))
        assertNotEquals(NodeIdentity.key(trojan), NodeIdentity.key(JsonObject(trojan + ("password" to JsonPrimitive("other")))))
    }

    @Test
    fun identitiesAreOpaqueAndTheSameEverywhere() {
        // Computed apart from KOne (Python's hashlib) from the labelled canonical forms:
        // key: kone.node.key.v1 NUL {"password":"pw","server":"203.0.113.1","server_port":443,"tls":{"enabled":true,"server_name":"t.invalid"},"type":"trojan"}
        // endpoint: kone.node.endpoint.v1 NUL trojan, 203.0.113.1, 443, then uuid, username, user, password, auth_str, auth, joined by NUL.
        assertEquals("5e279342fc7634e2fa5c9111badc5394020f9a95251edcfd5019978369a33412", NodeIdentity.key(trojan))
        assertEquals("ab624fbefa61b81d0252ede19822a5592c7201ac8b19ec454f8c0a0d74b1f035", NodeIdentity.endpoint(trojan))
        for (identity in listOf(NodeIdentity.key(trojan), NodeIdentity.endpoint(trojan)!!)) {
            assertTrue(identity.matches(Regex("[0-9a-f]{64}")), identity)
            assertFalse("pw" in identity || "203.0.113" in identity || "trojan" in identity)
        }
    }

    @Test
    fun anEndpointIsTheServerAndAccountWhateverTheSettings() {
        val endpoint = NodeIdentity.endpoint(trojan)
        // Another name, TLS and transport; the address written in another case: the same server and account.
        assertEquals(endpoint, NodeIdentity.endpoint(outbound("""{"type":"Trojan","tag":"C","server":" 203.0.113.1 ","server_port":443,"password":"pw","transport":{"type":"ws","path":"/x"}}""")))
        assertEquals(
            NodeIdentity.endpoint(outbound("""{"type":"vless","server":"[2001:DB8::1]","server_port":443,"uuid":"$id"}""")),
            NodeIdentity.endpoint(outbound("""{"type":"vless","server":"2001:db8::1","server_port":443,"uuid":"$id","flow":"xtls-rprx-vision"}""")),
        )
        assertNotEquals(endpoint, NodeIdentity.endpoint(JsonObject(trojan + ("password" to JsonPrimitive("other")))))
        assertNotEquals(endpoint, NodeIdentity.endpoint(JsonObject(trojan + ("server_port" to JsonPrimitive(8443)))))
        assertNull(NodeIdentity.endpoint(JsonObject(trojan - "server")))
        // Port ranges in any order are the same ranges.
        assertEquals(
            NodeIdentity.endpoint(outbound("""{"type":"hysteria2","server":"203.0.113.3","server_ports":["443:443","20000:30000"],"password":"pw"}""")),
            NodeIdentity.endpoint(outbound("""{"type":"hysteria2","server":"203.0.113.3","server_ports":["20000:30000","443:443"],"password":"pw"}""")),
        )
        // The labels keep the two kinds apart.
        assertNotEquals(NodeIdentity.key(trojan), endpoint)
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
    fun aNodeSplitIntoSeveralOnItsServerIsNotGuessedAt() {
        fun osaka(path: String, name: String) = "vless://$id@203.0.113.1:443?security=tls&sni=o.invalid&type=ws&path=%2F$path#$name"
        val before = read(osaka("a", "Osaka"), "trojan://pw@203.0.113.2:443#Seoul")
        // One old node becomes two new ones on its server and account, neither the same node nor under its tag.
        val split = read("trojan://pw@203.0.113.2:443#Seoul", osaka("b", "Osaka A"), osaka("c", "Osaka B"))
        assertNull(NodeIdentity.follow("Osaka", before, split))
        assertEquals(emptyMap(), NodeIdentity.renamed(before, split))
        // Nor when one of them keeps its tag: the name alone does not settle a split.
        assertNull(NodeIdentity.follow("Osaka", before, read(osaka("b", "Osaka"), osaka("c", "Osaka B"))))
        // The same node among them settles it.
        assertEquals("Osaka classic", NodeIdentity.follow("Osaka", before, read(osaka("c", "Osaka B"), osaka("a", "Osaka classic"))))
        // Twins of one new setting are still one node.
        assertEquals("Osaka IPLC", NodeIdentity.follow("Osaka", before, read(osaka("b", "Osaka IPLC"), osaka("b", "Osaka IPLC copy"))))
        assertEquals("Osaka IPLC copy", NodeIdentity.follow("Osaka", before, read(osaka("b", "Osaka IPLC copy"), osaka("b", "Osaka IPLC"))))
    }

    @Test
    fun tagsThatAreNotNodesOfTheListComeBackAsTheyAre() {
        val before = read("trojan://pw@203.0.113.1:443#A")
        assertEquals("Automatic", NodeIdentity.follow("Automatic", before, emptyList()))
        assertNull(NodeIdentity.follow("A", before, emptyList()))
    }
}
