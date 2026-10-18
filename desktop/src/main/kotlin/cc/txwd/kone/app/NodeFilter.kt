package cc.txwd.kone.app

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Which of a subscription's nodes KOne uses. Providers put notices among their nodes ("剩余流量：
 * 100 GB", "官网 example.com", "Expire: 2026-12-31"): entries named for their text, pointing at no
 * real server, that only spoil Automatic and a test of all. Those are always left out, and so is
 * any node whose name holds one of the words the person gave for that subscription. A node chained
 * through one left out goes with it, so no chain is left pointing at nothing.
 */
object NodeFilter {
    /** What the subscription keeps, and how many entries were left out. */
    class Kept(val nodes: List<JsonObject>, val left: Int)

    /**
     * Words only a notice is named with. Kept narrow on purpose: a word a real node could carry
     * ("Traffic", "Premium") would leave out a node the person paid for.
     */
    private val NOTICES = listOf(
        "剩余流量", "剩余：", "剩余:", "总流量", "已用流量", "流量重置", "重置日", "距离下次重置", "套餐到期", "到期时间", "过期时间", "到期：", "到期:",
        "官网", "官方网址", "网址：", "网址:", "最新网址", "发布页", "客服", "售后", "交流群", "频道：", "频道:",
        "expire:", "expire date", "expires:", "remaining:", "traffic left", "data left", "website:", "official site",
    )

    /** Whether [name] is a provider's notice rather than a node. */
    fun isNotice(name: String): Boolean {
        val lower = name.lowercase()
        return NOTICES.any { it in lower }
    }

    /** The words in [text]: separated by `|`, commas or new lines, 20 at most, 32 characters each. */
    fun words(text: String): List<String> =
        text.split('|', ',', '，', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { it.take(32) }.distinct().take(20)

    /** [text] as it is kept and shown: its words, between bars. */
    fun clean(text: String): String = words(text).joinToString(" | ")

    /** The [nodes] KOne uses: notices, nodes named with any of [leaveOut], and chains through them, left out. */
    fun apply(nodes: List<JsonObject>, leaveOut: String): Kept {
        val words = words(leaveOut)
        fun tag(node: JsonObject) = (node["tag"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun detour(node: JsonObject) = (node["detour"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val gone = nodes.mapNotNullTo(HashSet()) { node ->
            val name = tag(node)
            name.takeIf { isNotice(it) || words.any { word -> it.contains(word, ignoreCase = true) } }
        }
        if (gone.isEmpty()) return Kept(nodes, 0)
        // Chains through a node left out go too, however long they are.
        var kept = nodes.filter { tag(it) !in gone }
        while (true) {
            val next = kept.filter { node -> detour(node).let { it == null || it !in gone } }
            if (next.size == kept.size) break
            gone += kept.filter { it !in next }.map(::tag)
            kept = next
        }
        return Kept(kept, nodes.size - kept.size)
    }
}
