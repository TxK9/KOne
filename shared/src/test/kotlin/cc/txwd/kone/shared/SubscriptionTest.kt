package cc.txwd.kone.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SubscriptionTest {
    private val id = "00000000-0000-0000-0000-000000000001"

    private fun JsonObject.text(key: String) = this[key]?.jsonPrimitive?.content

    @Test
    fun userInfoIsReadAsProvidersWriteIt() {
        val info = UserInfo.parse("upload=455727941; download=6174315083; total=1073741824000; expire=1671815872")!!
        assertEquals(455727941L + 6174315083L, info.used)
        assertEquals(1073741824000L, info.total)
        assertEquals(1671815872L, info.expire)
        assertEquals(UserInfo(0, 10, 100, null), UserInfo.parse("download=10.0, total=100; expire=0"))
        assertNull(UserInfo.parse("nothing here"))
        assertNull(UserInfo.parse(null))
    }

    @Test
    fun onlyPlainHttpsLinksAreFetched() {
        assertTrue(SubscriptionContent.acceptable("https://example.com/api/v1/client/subscribe?token=abc"))
        assertFalse(SubscriptionContent.acceptable("http://example.com/sub"))
        assertFalse(SubscriptionContent.acceptable("https://user:pw@example.com/sub"))
        assertFalse(SubscriptionContent.acceptable("file:///C:/sub.txt"))
        assertFalse(SubscriptionContent.acceptable("not a link"))
    }

    @Test
    fun aSubscriptionIsNamedByItsProviderElseItsLink() {
        assertEquals("Example Cloud", SubscriptionContent.name("https://example.com/s?t=1", "attachment; filename*=UTF-8''Example%20Cloud.yaml"))
        assertEquals("plain", SubscriptionContent.name("https://example.com/s", "attachment; filename=\"plain.txt\""))
        assertEquals("Mine", SubscriptionContent.name("https://example.com/s#Mine", null))
        assertEquals("example.com", SubscriptionContent.name("https://example.com/s?token=secret", null))
    }

    @Test
    fun shareLinksAndBase64BlocksBecomeUniquelyTaggedNodes() {
        val lines = "trojan://pw@203.0.113.1:443#Tokyo\ntrojan://pw@203.0.113.2:443#Tokyo\nhy2://pw@203.0.113.3:443\nvless://$id@203.0.113.4:443?type=tcp"
        for (body in listOf(lines, Base64.getEncoder().encodeToString(lines.toByteArray()))) {
            val read = SubscriptionContent.read(body)
            assertEquals(listOf("Tokyo", "Tokyo 2", "Node 3"), read.nodes.map { it.text("tag") })
            assertEquals(1, read.refused, "a VLESS link without TLS is refused")
            assertEquals("VLESS links require TLS or Reality encryption.", read.refusal)
        }
        assertEquals("No nodes found in the subscription", SubscriptionContent.read("<html>login</html>").refusal)
    }

    @Test
    fun singBoxSubscriptionsKeepNodesAndTheirChains() {
        val body = """
            {"outbounds": [
              {"type": "selector", "tag": "select", "outbounds": ["a", "b"]},
              {"type": "trojan", "tag": "a", "server": "203.0.113.5", "server_port": 443, "password": "x", "detour": "relay"},
              {"type": "shadowsocks", "tag": "relay", "server": "203.0.113.6", "server_port": 8388, "method": "aes-128-gcm", "password": "x"},
              {"type": "trojan", "tag": "relay", "server": "203.0.113.7", "server_port": 443, "password": "y"},
              {"type": "trojan", "tag": "lost", "server": "203.0.113.8", "server_port": 443, "password": "x", "detour": "nowhere"},
              {"type": "trojan", "tag": "file", "server": "203.0.113.9", "server_port": 443, "password": "x", "tls": {"enabled": true, "certificate_path": "C:/x.pem"}},
              {"type": "direct", "tag": "direct"}
            ]}
        """.trimIndent()
        val read = SubscriptionContent.read(body)
        assertEquals(listOf("a", "relay", "relay 2"), read.nodes.map { it.text("tag") })
        assertEquals("relay", read.nodes[0].text("detour"))
        assertEquals(2, read.refused, "the broken chain and the node naming a file")
    }

    @Test
    fun noMoreThanTheLimitIsKept() {
        val body = (1..600).joinToString("\n") { "trojan://pw@203.0.113.1:$it#n$it" }
        assertEquals(SubscriptionContent.MOST, SubscriptionContent.read(body).nodes.size)
    }

    @Test
    fun anUnlimitedTotalIsNotAWarning() {
        // Some panels send a total near Long.MAX_VALUE for "unlimited"; products would overflow.
        val unlimited = UserInfo(upload = 1L shl 40, download = 1L shl 40, total = Long.MAX_VALUE, expire = null)
        kotlin.test.assertNull(unlimited.warning("Example Cloud", 0))
        kotlin.test.assertEquals("Example Cloud has used 95% of its data", UserInfo(0, 95, 100, null).warning("Example Cloud", 0))
        kotlin.test.assertEquals(Long.MAX_VALUE, UserInfo(Long.MAX_VALUE, Long.MAX_VALUE, 0, null).used)
        // An end sent in milliseconds is read as the same moment.
        kotlin.test.assertEquals(1_800_000_000L, UserInfo.parse("upload=0; download=0; total=1; expire=1800000000000")?.expire)
    }

    @Test
    fun aSubscriptionComesInAnotherClientsImportLinkToo() {
        assertEquals("https://example.com/sub?token=abc", SubscriptionContent.linkOf(" https://example.com/sub?token=abc "))
        assertEquals("https://example.com/sub?token=abc&flag=clash", SubscriptionContent.linkOf("clash://install-config?url=https%3A%2F%2Fexample.com%2Fsub%3Ftoken%3Dabc%26flag%3Dclash&name=Example"))
        assertEquals("https://example.com/s", SubscriptionContent.linkOf("sing-box://import-remote-profile?url=https%3A%2F%2Fexample.com%2Fs#Example"))
        assertEquals("https://example.com/s?a=1", SubscriptionContent.linkOf("hiddify://import/https://example.com/s?a=1#Example"))
        // Only what KOne fetches: https, with no password in it.
        assertNull(SubscriptionContent.linkOf("http://example.com/sub"))
        assertNull(SubscriptionContent.linkOf("clash://install-config?url=http%3A%2F%2Fexample.com%2Fsub"))
        assertNull(SubscriptionContent.linkOf("https://user:pass@example.com/sub"))
        assertNull(SubscriptionContent.linkOf("vless://00000000-0000-0001-0000-000000000001@203.0.113.1:443#node"))
        assertNull(SubscriptionContent.linkOf("other://install-config?url=https%3A%2F%2Fexample.com%2Fs"))
    }
}
