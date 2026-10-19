package cc.txwd.kone.shared

import java.net.URLEncoder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShareLinksTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val realityKey = "A".repeat(43)

    private fun base64(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())
    private fun base64Url(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())
    private fun encoded(text: String) = URLEncoder.encode(text, "UTF-8")

    @Test
    fun vlessOverWebSocketKeepsPathHostAndEarlyData() {
        val node = ShareLinks.parse("vless://$id@a.invalid:8443?security=tls&sni=cdn.invalid&type=ws&host=cdn.invalid&path=${encoded("/ws?ed=2048")}#WS").outbound
        assertEquals("vless", node.getString("type"))
        assertEquals(8443, node.getInt("server_port"))
        val transport = node.getJSONObject("transport")
        assertEquals("ws", transport.getString("type"))
        assertEquals("/ws", transport.getString("path"))
        assertEquals(2048, transport.getInt("max_early_data"))
        assertEquals("cdn.invalid", transport.getJSONObject("headers").getString("Host"))
        assertEquals("cdn.invalid", node.getJSONObject("tls").getString("server_name"))
    }

    @Test
    fun vlessRealityOverGrpc() {
        val node = ShareLinks.parse("vless://$id@a.invalid?security=reality&pbk=$realityKey&sid=ab&fp=safari&type=grpc&serviceName=svc").outbound
        assertEquals(443, node.getInt("server_port"))
        assertEquals("svc", node.getJSONObject("transport").getString("service_name"))
        val tls = node.getJSONObject("tls")
        assertEquals("ab", tls.getJSONObject("reality").getString("short_id"))
        assertEquals("safari", tls.getJSONObject("utls").getString("fingerprint"))
    }

    @Test
    fun vmessReadsTheV2rayNJson() {
        val json = """{"v":"2","ps":"\u9999\u6e2f 01","add":"v.invalid","port":"443","id":"$id","aid":"0","scy":"auto","net":"ws","type":"none","host":"h.invalid","path":"/p","tls":"tls","sni":"s.invalid"}"""
        val shared = ShareLinks.parse("vmess://" + base64(json))
        val node = shared.outbound
        assertEquals("\u9999\u6e2f 01", shared.name)
        assertEquals("vmess", node.getString("type"))
        assertEquals("auto", node.getString("security"))
        assertFalse(node.has("alter_id"))
        assertEquals("s.invalid", node.getJSONObject("tls").getString("server_name"))
        assertEquals("/p", node.getJSONObject("transport").getString("path"))
        assertEquals("h.invalid", node.getJSONObject("transport").getJSONObject("headers").getString("Host"))
    }

    @Test
    fun trojanDefaultsToTlsAndReadsPeerAndInsecure() {
        val node = ShareLinks.parse("trojan://p%40ss@t.invalid:443?peer=sni.invalid&allowInsecure=1#T").outbound
        assertEquals("p@ss", node.getString("password"))
        val tls = node.getJSONObject("tls")
        assertEquals("sni.invalid", tls.getString("server_name"))
        assertTrue(tls.getBoolean("insecure"))
    }

    @Test
    fun shadowsocksReadsEveryForm() {
        val sip002 = ShareLinks.parse("ss://${base64Url("aes-256-gcm:secret")}@s.invalid:8388#SS").outbound
        assertEquals("aes-256-gcm", sip002.getString("method"))
        assertEquals("secret", sip002.getString("password"))
        val written = ShareLinks.parse("ss://2022-blake3-aes-128-gcm:${encoded("a+b/c==")}@s.invalid:8388").outbound
        assertEquals("2022-blake3-aes-128-gcm", written.getString("method"))
        assertEquals("a+b/c==", written.getString("password"))
        val legacy = ShareLinks.parse("ss://${base64("chacha20-ietf-poly1305:pw@l.invalid:8389")}#Old").outbound
        assertEquals("l.invalid", legacy.getString("server"))
        assertEquals(8389, legacy.getInt("server_port"))
        val plugin = ShareLinks.parse("ss://${base64Url("aes-128-gcm:pw")}@s.invalid:80/?plugin=${encoded("obfs-local;obfs=http;obfs-host=x.invalid")}").outbound
        assertEquals("obfs-local", plugin.getString("plugin"))
        assertEquals("obfs=http;obfs-host=x.invalid", plugin.getString("plugin_opts"))
    }

    @Test
    fun hysteria2ReadsObfuscationAndPortHopping() {
        val node = ShareLinks.parse("hy2://pw@h.invalid:443,20000-30000/?sni=s.invalid&obfs=salamander&obfs-password=ob&insecure=1#H").outbound
        assertEquals("hysteria2", node.getString("type"))
        // A hopping node lists its port among the ranges; sing-box refuses both fields at once.
        assertFalse(node.has("server_port"))
        assertEquals(listOf("443:443", "20000:30000"), (0 until 2).map { node.getJSONArray("server_ports").getString(it) })
        assertEquals("ob", node.getJSONObject("obfs").getString("password"))
        assertTrue(node.getJSONObject("tls").getBoolean("insecure"))
        val hopping = ShareLinks.parse("hysteria2://pw@h.invalid:443?mport=1000-2000").outbound
        assertEquals("1000:2000", hopping.getJSONArray("server_ports").getString(1))
        val single = ShareLinks.parse("hysteria2://pw@h.invalid:8443").outbound
        assertEquals(8443, single.getInt("server_port"))
        assertFalse(single.has("server_ports"))
    }

    @Test
    fun tuicAndAnyTls() {
        val tuic = ShareLinks.parse("tuic://$id:pw@u.invalid:443?congestion_control=bbr&alpn=h3&udp_relay_mode=native").outbound
        assertEquals(id, tuic.getString("uuid"))
        assertEquals("bbr", tuic.getString("congestion_control"))
        assertEquals("h3", tuic.getJSONObject("tls").getJSONArray("alpn").getString(0))
        val anyTls = ShareLinks.parse("anytls://pw@a.invalid:443?sni=s.invalid").outbound
        assertEquals("anytls", anyTls.getString("type"))
        assertEquals("s.invalid", anyTls.getJSONObject("tls").getString("server_name"))
    }

    @Test
    fun findsTheFirstLinkAmongOtherText() {
        val text = "\u770b\u770b\u8fd9\u4e2a\nhttps://example.invalid\n  trojan://pw@t.invalid:443#A  \nvless://x"
        assertEquals("trojan://pw@t.invalid:443#A", ShareLinks.find(text))
        assertNull(ShareLinks.find("https://example.invalid/sub"))
        assertFalse(ShareLinks.isShareLink("http://pw@h.invalid:80"))
    }

    @Test
    fun findsEveryLinkAndOpensSubscriptionBlocks() {
        val lines = "trojan://pw@a.invalid:443#A\nnot a link\nhy2://pw@b.invalid:443#B\ntrojan://pw@a.invalid:443#A"
        assertEquals(listOf("trojan://pw@a.invalid:443#A", "hy2://pw@b.invalid:443#B"), ShareLinks.findAll(lines))
        assertEquals(listOf("trojan://pw@a.invalid:443#A", "hy2://pw@b.invalid:443#B"), ShareLinks.findAll(base64(lines)))
        assertTrue(ShareLinks.findAll("\u53ea\u662f\u4e00\u6bb5\u666e\u901a\u7684\u6587\u5b57\uff0c\u6ca1\u6709\u94fe\u63a5").isEmpty())
    }

    @Test
    fun refusalsNeverRepeatTheLink() {
        val secrets = listOf("hidden.invalid", "topsecret")
        listOf(
            "vless://$id@hidden.invalid:443?security=tls&type=xhttp&path=topsecret",
            "vless://$id@hidden.invalid:443?type=tcp",
            "trojan://@hidden.invalid:443",
            "ss://${base64Url("aes-128-gcm:topsecret")}@hidden.invalid:80/?plugin=kcptun",
            "hy2://topsecret@hidden.invalid:443?obfs=other",
            "vmess://not-base64-topsecret",
            "socks://topsecret@hidden.invalid:1080",
        ).forEach { link ->
            val failure = runCatching { ShareLinks.parse(link) }.exceptionOrNull()
            assertTrue(failure is ShareLinkException)
            secrets.forEach { assertFalse(failure!!.message!!.contains(it)) }
        }
    }
}
