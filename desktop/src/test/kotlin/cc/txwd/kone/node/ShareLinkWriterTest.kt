package cc.txwd.kone.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ShareLinkWriterTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val realityKey = "A".repeat(43)
    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
    private fun base64Url(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    private fun encoded(text: String) = URLEncoder.encode(text, "UTF-8")
    private fun node(link: String) = Json.parseToJsonElement(ShareLinks.parse(link).outbound.toString()) as JsonObject

    /** Every kind KOne reads comes back as the same node after being written as a link. */
    @Test
    fun linksReadBackAsTheSameNode() {
        val links = listOf(
            "vless://$id@203.0.113.1:8443?security=tls&sni=cdn.example.com&type=ws&host=cdn.example.com&path=${encoded("/ws?ed=2048")}#WS",
            "vless://$id@203.0.113.2?security=reality&pbk=$realityKey&sid=ab&fp=safari&type=grpc&serviceName=svc&flow=xtls-rprx-vision",
            "vmess://" + base64("""{"v":"2","ps":"HK 01","add":"203.0.113.3","port":"443","id":"$id","aid":"0","scy":"auto","net":"ws","type":"none","host":"h.example.com","path":"/p","tls":"tls","sni":"s.example.com"}"""),
            "vmess://" + base64("""{"add":"203.0.113.4","port":443,"id":"$id","net":"tcp","tls":""}"""),
            "trojan://p%40ss@203.0.113.5:443?peer=sni.example.com&allowInsecure=1#T",
            "trojan://pw@203.0.113.6:443?type=httpupgrade&host=u.example.com&path=%2Fup",
            "ss://${base64Url("aes-256-gcm:secret")}@203.0.113.7:8388#SS",
            "ss://2022-blake3-aes-128-gcm:${encoded("a+b/c==")}@203.0.113.8:8388",
            "ss://${base64Url("aes-128-gcm:pw")}@203.0.113.9:80/?plugin=${encoded("obfs-local;obfs=http;obfs-host=x.example.com")}",
            "hy2://pw@203.0.113.10:443,20000-30000/?sni=h.example.com&obfs=salamander&obfs-password=ob&insecure=1#H",
            "hysteria2://pw@203.0.113.11:8443?upmbps=30&downmbps=100",
            "tuic://$id:pw@203.0.113.12:443?congestion_control=bbr&alpn=h3&udp_relay_mode=native",
            "anytls://pw@203.0.113.13:443?sni=a.example.com&insecure=1",
            "vless://$id@[2001:db8::1]:443?security=tls&type=tcp",
        )
        for (link in links) {
            val first = node(link)
            val written = ShareLinkWriter.write(first, "Name")!!
            assertEquals(JsonObject(first - "tag"), JsonObject(node(written) - "tag"), "$link\n→ $written")
            assertEquals("Name", ShareLinks.parse(written).name)
        }
    }

    @Test
    fun whatNoLinkCanCarryGivesNothing() {
        val chained = Json.parseToJsonElement("""{"type": "trojan", "server": "203.0.113.1", "server_port": 443, "password": "x", "detour": "relay"}""") as JsonObject
        assertNull(ShareLinkWriter.write(chained, "a"))
        val socks = Json.parseToJsonElement("""{"type": "socks", "server": "203.0.113.1", "server_port": 1080}""") as JsonObject
        assertNull(ShareLinkWriter.write(socks, "b"))
        val plainVless = Json.parseToJsonElement("""{"type": "vless", "server": "203.0.113.1", "server_port": 443, "uuid": "$id"}""") as JsonObject
        assertNull(ShareLinkWriter.write(plainVless, "c"), "VLESS without TLS is never written, as it is never read")
    }
}
