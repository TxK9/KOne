package cc.txwd.kone.node

/**
 * A Clash (mihomo) profile as a subscription: the shared core reads its proxies
 * ([cc.txwd.kone.shared.ClashProxies]), and each is tagged here as a subscription's nodes are.
 */
object ClashProxies {
    /** Whether [text] reads as a Clash profile rather than share links. */
    fun looksLikeClash(text: String): Boolean = cc.txwd.kone.shared.ClashProxies.looksLikeClash(text)

    fun read(text: String): SubscriptionContent.Read {
        val read = cc.txwd.kone.shared.ClashProxies.read(text)
        // A subscription's own words for one with nothing in it.
        val refusal = if (read.named.isEmpty() && read.refused == 0 && read.refusal == "No nodes found in the profile") "No nodes found in the subscription" else read.refusal
        return SubscriptionContent.Read(SubscriptionContent.tagged(read.named), read.refused, refusal)
    }
}
