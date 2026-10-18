package cc.txwd.kone.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NodeFormTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val realityKey = "A".repeat(43)
    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
    private fun base64Url(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    private fun encoded(text: String) = URLEncoder.encode(text, "UTF-8")
    private fun node(link: String) = Json.parseToJsonElement(ShareLinks.parse(link).outbound.toString()) as JsonObject

    /** Every node a link gives opens in the form and saves back as the same node. */
    @Test
    fun nodesOpenAndSaveUnchanged() {
        val links = listOf(
            "vless://$id@203.0.113.1:8443?security=tls&sni=cdn.example.com&type=ws&host=cdn.example.com&path=${encoded("/ws")}#WS",
            "vless://$id@203.0.113.2?security=reality&pbk=$realityKey&sid=ab&fp=safari&type=grpc&serviceName=svc&flow=xtls-rprx-vision",
            "vmess://" + base64("""{"add":"203.0.113.3","port":"443","id":"$id","aid":"2","scy":"aes-128-gcm","net":"http","host":"a.example.com,b.example.com","path":"/h","tls":"tls"}"""),
            "vmess://" + base64("""{"add":"203.0.113.4","port":443,"id":"$id","net":"tcp","tls":""}"""),
            "trojan://pw@203.0.113.5:443?peer=sni.example.com&allowInsecure=1&alpn=h2,http%2F1.1#T",
            "trojan://pw@203.0.113.6:443?type=httpupgrade&host=u.example.com&path=%2Fup",
            "ss://${base64Url("aes-256-gcm:secret")}@203.0.113.7:8388#SS",
            "ss://${base64Url("aes-128-gcm:pw")}@203.0.113.9:80/?plugin=${encoded("obfs-local;obfs=http;obfs-host=x.example.com")}",
            "hy2://pw@203.0.113.10:443,20000-30000/?sni=h.example.com&obfs=salamander&obfs-password=ob&insecure=1#H",
            "hysteria2://pw@203.0.113.11:8443?upmbps=30&downmbps=100",
            "tuic://$id:pw@203.0.113.12:443?congestion_control=bbr&alpn=h3&udp_relay_mode=native",
            "anytls://pw@203.0.113.13:443?sni=a.example.com&insecure=1",
            "vless://$id@[2001:db8::1]:443?security=tls&type=tcp",
        )
        for (link in links) {
            val original = node(link)
            val draft = assertNotNull(NodeForm.read(original, "Name"), link)
            val saved = NodeForm.build(draft).getOrThrow()
            assertEquals(JsonObject(original - "tag"), JsonObject(saved - "tag"), link)
            assertEquals("Name", saved["tag"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun aNodeWrittenByHandIsCheckedField() {
        val ok = NodeForm.build(NodeDraft(type = "socks", server = "203.0.113.20", port = "1080", username = "u", password = "p")).getOrThrow()
        assertEquals("203.0.113.20", ok["tag"]!!.jsonPrimitive.content, "an unnamed node is named by its server")
        assertNull(ok["tls"])
        val reality = NodeForm.build(NodeDraft(type = "vless", name = "R", server = "203.0.113.21", uuid = id, security = "reality", publicKey = realityKey)).getOrThrow()
        assertEquals("chrome", reality["tls"]!!.jsonObject["utls"]!!.jsonObject["fingerprint"]!!.jsonPrimitive.content)
        fun error(draft: NodeDraft) = NodeForm.build(draft).exceptionOrNull()?.message
        assertEquals("Enter the server", error(NodeDraft(uuid = id)))
        assertEquals("The UUID is not valid", error(NodeDraft(server = "203.0.113.1", uuid = "nope")))
        assertEquals("The port is not 1 to 65535", error(NodeDraft(server = "203.0.113.1", port = "70000", uuid = id)))
        assertEquals("Enter the password", error(NodeDraft(type = "trojan", server = "203.0.113.1")))
        assertEquals("Enter the Reality public key", error(NodeDraft(server = "203.0.113.1", uuid = id, security = "reality")))
        assertEquals("The ports to hop across are not valid", error(NodeDraft(type = "hysteria2", server = "203.0.113.1", password = "p", hops = "30000-20000")))
        assertTrue(error(NodeDraft(type = "shadowsocks", server = "203.0.113.1", method = "aes-128-gcm", password = "p", plugin = "kcptun"))!!.startsWith("Supported"))
    }

    @Test
    fun aChainIsNotOpenedInTheForm() {
        val chained = Json.parseToJsonElement("""{"type": "trojan", "server": "203.0.113.1", "server_port": 443, "password": "x", "detour": "relay"}""") as JsonObject
        assertNull(NodeForm.read(chained, "a"))
    }
}
