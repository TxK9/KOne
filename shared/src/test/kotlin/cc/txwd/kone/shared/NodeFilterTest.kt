package cc.txwd.kone.shared

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NodeFilterTest {
    private fun node(tag: String, detour: String? = null) = JsonObject(
        buildMap {
            put("type", JsonPrimitive("shadowsocks"))
            put("tag", JsonPrimitive(tag))
            put("server", JsonPrimitive("203.0.113.10"))
            detour?.let { put("detour", JsonPrimitive(it)) }
        },
    )

    private fun tags(kept: NodeFilter.Kept) = kept.nodes.map { (it["tag"] as JsonPrimitive).content }

    @Test
    fun aProvidersNoticesAreNotNodes() {
        listOf("剩余流量：100.5 GB", "套餐到期：2026-12-31", "官网 example.com", "距离下次重置剩余：12 天", "Expire: 2026-12-31", "Traffic left 20 GB").forEach {
            assertTrue(NodeFilter.isNotice(it), it)
        }
        // Real nodes, however they are named.
        listOf("香港 01", "Hong Kong 02 | 1.5x", "Japan Premium Traffic", "日本 IPLC 专线", "US Website Unlock").forEach {
            assertFalse(NodeFilter.isNotice(it), it)
        }
    }

    @Test
    fun noticesAndTheWordsGivenLeaveNodesOut() {
        val nodes = listOf(node("剩余流量：100 GB"), node("Hong Kong 01"), node("Hong Kong 0.1x"), node("Japan TEST"), node("Japan 02"))
        val kept = NodeFilter.apply(nodes, "0.1x | test")
        assertEquals(listOf("Hong Kong 01", "Japan 02"), tags(kept))
        assertEquals(3, kept.left)
        assertEquals(nodes.drop(1), NodeFilter.apply(nodes.drop(1), "").nodes)
    }

    @Test
    fun aChainThroughANodeLeftOutGoesWithIt() {
        val nodes = listOf(node("Relay test"), node("Exit", detour = "Relay test"), node("Further", detour = "Exit"), node("Alone"))
        val kept = NodeFilter.apply(nodes, "test")
        assertEquals(listOf("Alone"), tags(kept))
        assertEquals(3, kept.left)
    }

    @Test
    fun wordsAreKeptTidy() {
        assertEquals("0.1x | Test | 测试", NodeFilter.clean(" 0.1x ,Test|| 测试，0.1x "))
        assertEquals("", NodeFilter.clean("  "))
    }
}
