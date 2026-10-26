package io.nekohasekai.sfa.compose.screen.configuration

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class ImportInputTest {
    private val links = listOf(
        "vless://00000000-0000-4000-8000-000000000001@node1.invalid:443?security=tls&sni=example.org#Tokyo",
        "trojan://example-password@node2.invalid:443?sni=example.org#Frankfurt",
        "ss://YWVzLTEyOC1nY206ZXhhbXBsZS1wYXNzd29yZA@node3.invalid:8388#Paris",
    )

    @Test
    fun aFileOfNodesIsCountedHoweverItIsWritten() {
        assertEquals(3, NodeText.count(links.joinToString("\n")))
        assertEquals(3, NodeText.count("\uFEFF\r\n" + links.joinToString("\r\n") + "\n\n"))
        assertEquals(2, NodeText.count(Base64.getEncoder().encodeToString(links.take(2).joinToString("\n").toByteArray())))
        // The same link twice is one node.
        assertEquals(1, NodeText.count(links[1] + "\n" + links[1]))
        val clash = """
            proxies:
              - { name: "Tokyo", type: trojan, server: node1.invalid, port: 443, password: example-password }
              - { name: Old, type: ssr, server: node2.invalid, port: 443 }
        """.trimIndent()
        // Those sing-box refuses still count: the clipboard import says which were left out.
        assertEquals(2, NodeText.count(clash))
    }

    @Test
    fun profilesAndOtherTextAreNotNodeLists() {
        assertEquals(0, NodeText.count("""{"outbounds":[{"type":"direct","tag":"direct"}]}"""))
        assertEquals(0, NodeText.count("  [1, 2]"))
        assertEquals(0, NodeText.count("https://sub.example.com/link"))
        assertEquals(0, NodeText.count("a short note"))
        assertEquals(0, NodeText.count(""))
    }

    @Test
    fun aPastedSubscriptionIsTrimmedAndOpenedFromAnotherClientsLink() {
        assertEquals("https://sub.example.com/s?token=example", subscriptionLink("  https://sub.example.com/s?token=example\n"))
        assertEquals(
            "https://sub.example.com/s?token=example",
            subscriptionLink("clash://install-config?url=https%3A%2F%2Fsub.example.com%2Fs%3Ftoken%3Dexample&name=Example"),
        )
        assertEquals(
            "https://sub.example.com/s",
            subscriptionLink("sing-box://import-remote-profile?url=https%3A%2F%2Fsub.example.com%2Fs#Example"),
        )
        // KOne still lets a person type a plain http link; it is kept as typed.
        assertEquals("http://sub.example.com/s", subscriptionLink(" http://sub.example.com/s "))
        assertEquals("", subscriptionLink("   "))
    }

    @Test
    fun theRefreshIntervalCanBeTypedFreely() {
        assertEquals(30, refreshMinutes("30"))
        assertEquals(15, refreshMinutes("3"))
        assertEquals(15, refreshMinutes("0"))
        assertEquals(60, refreshMinutes(""))
        assertEquals(1440, refreshMinutes(" 1440 "))
    }
}
