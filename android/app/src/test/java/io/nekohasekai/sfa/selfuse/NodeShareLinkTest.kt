package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.ShareLinks
import cc.txwd.kone.shared.ShareLinkException
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.util.Base64

class NodeShareLinkTest {
    private val uuid = "00000000-0000-0000-0000-000000000001"
    @Test fun portableVlessRealityAndWebsocketRoundTrip() {
        for (uri in listOf(
            "vless://$uuid@a.invalid:8443?security=reality&pbk=${"A".repeat(43)}&sid=ab&fp=chrome&flow=xtls-rprx-vision&type=tcp",
            "vless://$uuid@[2001:db8::1]:443?security=tls&sni=cdn.invalid&type=ws&host=cdn.invalid&path=%2Fchat"
        )) {
            val source = ShareLinks.parse(uri).outbound
            val encoded = NodeShareLink.encode(source, "\u8282\u70b9 / \u6d4b\u8bd5")
            val decoded = ShareLinks.parse(encoded)
            assertEquals("\u8282\u70b9 / \u6d4b\u8bd5", decoded.name)
            assertEquals(source.getString("server"), decoded.outbound.getString("server"))
            assertEquals(source.getInt("server_port"), decoded.outbound.getInt("server_port"))
            assertEquals(source.getString("uuid"), decoded.outbound.getString("uuid"))
            assertEquals(source.getJSONObject("tls").toString(), decoded.outbound.getJSONObject("tls").toString())
            assertEquals(source.optJSONObject("transport")?.toString(), decoded.outbound.optJSONObject("transport")?.toString())
        }
    }
    @Test fun hysteriaPasswordsAndObfuscationSurviveQrExport() {
        val source = ShareLinks.parse("hysteria2://a%3Ab%40c@hy.invalid:443?sni=cdn.invalid&obfs=salamander&obfs-password=x%26y").outbound
        val decoded = ShareLinks.parse(NodeShareLink.encode(source, "\u6d4b\u8bd5")).outbound
        assertEquals(source.getString("password"), decoded.getString("password"))
        assertEquals(source.getJSONObject("obfs").toString(), decoded.getJSONObject("obfs").toString())
    }
    @Test fun vmessUsesPortableJsonAndSsUsesSip002() {
        val vmess = JSONObject("""{"type":"vmess","server":"v.invalid","server_port":443,"uuid":"$uuid","security":"auto","tls":{"enabled":true,"server_name":"cdn.invalid"}}""")
        val link = NodeShareLink.encode(vmess, "\u6d4b\u8bd5")
        assertTrue(link.startsWith("vmess://"))
        val json = JSONObject(String(Base64.getDecoder().decode(link.removePrefix("vmess://"))))
        assertEquals("v.invalid", json.getString("add"))
        assertEquals(uuid, ShareLinks.parse(link).outbound.getString("uuid"))
        val ss = JSONObject("""{"type":"shadowsocks","server":"s.invalid","server_port":8443,"method":"aes-128-gcm","password":"p:a@ss"}""")
        assertEquals("p:a@ss", ShareLinks.parse(NodeShareLink.encode(ss, "\u6d4b\u8bd5")).outbound.getString("password"))
    }
    @Test fun ignoresHomeRoutesAndRejectsUnsupportedParameters() {
        assertTrue(NodeShareLink.nodes("""{"outbounds":[{"type":"direct"},{"type":"tailscale"}]}""").isEmpty())
        val source = ShareLinks.parse("vless://$uuid@a.invalid:443?security=tls").outbound
        source.getJSONObject("tls").put("certificate_path", "/private/file")
        assertTrue(runCatching { NodeShareLink.encode(source,"\u6d4b\u8bd5") }.exceptionOrNull() is ShareLinkException)
    }
}
