package cc.txwd.kone.node

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClashProxiesTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val realityKey = "A".repeat(43)

    /** A mihomo profile as providers send one: every kind KOne reads, and two it does not. */
    val profile = """
        mixed-port: 7890
        proxies:
          - {name: "HK ss obfs", type: ss, server: 203.0.113.1, port: 8388, cipher: aes-128-gcm, password: pw, plugin: obfs, plugin-opts: {mode: http, host: x.invalid}}
          - name: JP vmess ws
            type: vmess
            server: 203.0.113.2
            port: 443
            uuid: $id
            alterId: 0
            cipher: auto
            tls: true
            servername: cdn.invalid
            network: ws
            ws-opts:
              path: /ws
              headers:
                Host: cdn.invalid
              max-early-data: 2048
          - name: US vless reality
            type: vless
            server: 203.0.113.3
            port: 443
            uuid: $id
            flow: xtls-rprx-vision
            tls: true
            servername: www.example.com
            client-fingerprint: safari
            reality-opts: {public-key: $realityKey, short-id: ab}
            network: tcp
          - {name: SG trojan grpc, type: trojan, server: 203.0.113.4, port: 443, password: pw, sni: t.invalid, network: grpc, grpc-opts: {grpc-service-name: svc}, skip-cert-verify: true}
          - {name: DE hy2, type: hysteria2, server: 203.0.113.5, ports: "443,20000-30000", password: pw, sni: h.invalid, obfs: salamander, obfs-password: ob, up: "30 Mbps", down: 100}
          - {name: FR tuic, type: tuic, server: 203.0.113.6, port: 443, uuid: $id, password: pw, congestion-controller: bbr, udp-relay-mode: native, alpn: [h3], reduce-rtt: true}
          - {name: NL anytls, type: anytls, server: 203.0.113.7, port: 443, password: pw, sni: a.invalid, client-fingerprint: chrome}
          - {name: socks, type: socks5, server: 203.0.113.8, port: 1080, username: u, password: p}
          - {name: http tls, type: http, server: 203.0.113.9, port: 443, tls: true, sni: p.invalid}
          - {name: legacy, type: ssr, server: 203.0.113.10, port: 1, cipher: none, password: secret-token}
          - {name: wg, type: wireguard, server: 203.0.113.11, port: 51820}
        proxy-groups:
          - {name: PROXY, type: select, proxies: [JP vmess ws]}
        rules:
          - MATCH,PROXY
    """.trimIndent()

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content
    private fun JsonObject.obj(key: String) = this[key]!!.jsonObject

    @Test
    fun everyKindKOneReadsComesOutAsTheSameNode() {
        assertTrue(ClashProxies.looksLikeClash(profile))
        val read = SubscriptionContent.read(profile)
        val byTag = read.nodes.associateBy { it.text("tag") }
        assertEquals(listOf("HK ss obfs", "JP vmess ws", "US vless reality", "SG trojan grpc", "DE hy2", "FR tuic", "NL anytls", "socks", "http tls"), read.nodes.map { it.text("tag") })
        assertEquals(2, read.refused)

        val ss = byTag.getValue("HK ss obfs")
        assertEquals("shadowsocks", ss.text("type"))
        assertEquals("obfs-local", ss.text("plugin"))
        assertEquals("obfs=http;obfs-host=x.invalid", ss.text("plugin_opts"))

        val vmess = byTag.getValue("JP vmess ws")
        assertEquals("cdn.invalid", vmess.obj("tls").text("server_name"))
        assertEquals("/ws", vmess.obj("transport").text("path"))
        assertEquals("cdn.invalid", vmess.obj("transport").obj("headers").text("Host"))
        assertEquals("2048", vmess.obj("transport").text("max_early_data"))
        assertNull(vmess["alter_id"])

        val vless = byTag.getValue("US vless reality")
        assertEquals("xtls-rprx-vision", vless.text("flow"))
        assertEquals("ab", vless.obj("tls").obj("reality").text("short_id"))
        assertEquals("safari", vless.obj("tls").obj("utls").text("fingerprint"))
        assertNull(vless["transport"])

        val trojan = byTag.getValue("SG trojan grpc")
        assertEquals("svc", trojan.obj("transport").text("service_name"))
        assertEquals("true", trojan.obj("tls").text("insecure"))

        val hy2 = byTag.getValue("DE hy2")
        assertEquals(listOf("443:443", "20000:30000"), (hy2["server_ports"] as JsonArray).map { it.jsonPrimitive.content })
        assertNull(hy2["server_port"])
        assertEquals("ob", hy2.obj("obfs").text("password"))
        assertEquals("30", hy2.text("up_mbps"))
        assertEquals("100", hy2.text("down_mbps"))

        val tuic = byTag.getValue("FR tuic")
        assertEquals("bbr", tuic.text("congestion_control"))
        assertEquals("h3", tuic.obj("tls")["alpn"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("true", tuic.text("zero_rtt_handshake"))

        assertEquals("chrome", byTag.getValue("NL anytls").obj("tls").obj("utls").text("fingerprint"))
        assertEquals("socks", byTag.getValue("socks").text("type"))
        assertEquals("p.invalid", byTag.getValue("http tls").obj("tls").text("server_name"))
    }

    @Test
    fun refusalsNeverRepeatTheProxy() {
        val read = SubscriptionContent.read(profile)
        assertFalse(read.refusal!!.contains("secret-token"))
        assertFalse(read.refusal!!.contains("203.0.113"))
    }

    @Test
    fun aProfileOfAliasesCannotBlowUp() {
        // A "billion laughs" profile: the safe loader stops at its alias limit instead of filling memory.
        val bomb = buildString {
            append("a: &a [x, x, x, x, x, x, x, x, x]\n")
            for (level in 1..9) append("${'a' + level}: &${'a' + level} [*${'a' + level - 1}, *${'a' + level - 1}, *${'a' + level - 1}, *${'a' + level - 1}, *${'a' + level - 1}, *${'a' + level - 1}, *${'a' + level - 1}, *${'a' + level - 1}, *${'a' + level - 1}]\n")
            append("proxies: [*j]\n")
        }
        val read = ClashProxies.read(bomb)
        assertTrue(read.nodes.isEmpty())
    }
}
