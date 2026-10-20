package cc.txwd.kone.shared

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Fake-IP DNS, an option (off unless chosen): the names that would be answered through the node
 * get an address from a reserved range at once instead, and sing-box gives the connection its
 * name back, so the node resolves it where it is. The first connection to a site skips a lookup
 * round trip, and those names are never asked of any resolver on this network. Every other
 * answer is as it was: the Claude block, fixed answers, the person's own servers, the direct
 * names and Direct mode keep real addresses.
 *
 * The cost, as in every client with fake IP: a connection to a faked name carries the name, not
 * an address, so rules on address ranges and country address lists (geoip-) do not see it; rules
 * on names still do. In China, a Chinese site missing from the name list (geosite-cn) then goes
 * through the node rather than straight out.
 *
 * The Windows service builds the same (desktop/service/fakeip.go); the consistency test holds the
 * two together.
 */
object FakeIp {
    const val TAG = "fake-dns"
    const val INET4 = "198.18.0.0/15"
    const val INET6 = "fc00::/18"
    private const val REMOTE = "proxy-dns"

    /**
     * Names that must keep real answers even through the node: Windows checks the Internet by
     * looking one up (dns.msftncsi.com must be 131.107.255.255), and says "No Internet" otherwise.
     */
    val REAL = listOf("msftncsi.com", "msftconnecttest.com")

    /** Rule keys that already name what a rule matches by name; such a rule is not one these names meet. */
    private val NAMED = setOf("domain", "domain_suffix", "domain_keyword", "domain_regex", "rule_set")

    /** Whether [address] is one of the fake ones ([INET4] or [INET6]), as a lookup through KOne returns them. */
    fun isFake(address: String): Boolean {
        val text = address.trim().lowercase()
        if (':' !in text) {
            val parts = text.split('.').mapNotNull { it.toIntOrNull() }
            return parts.size == 4 && parts[0] == 198 && parts[1] in 18..19
        }
        // fc00::/18: the first group fc00, and the top two bits of the second clear.
        val groups = text.split(':')
        if (groups.firstOrNull() != "fc00") return false
        val second = groups.getOrNull(1)?.takeIf { it.isNotEmpty() }?.toIntOrNull(16) ?: 0
        return second < 0x4000
    }

    /** Whether a rule with these [domainSuffix] names and this [server] is one [rules] added for the [REAL] names. */
    fun isRealNames(domainSuffix: List<String>?, server: String?): Boolean = throughNode(server) && domainSuffix == REAL

    /** Whether [server] is asked through a node: the node's DNS ([REMOTE]), or a country's, for the rules through it ([Rules.dnsThrough]). */
    fun throughNode(server: String?): Boolean = server == REMOTE || server?.startsWith(Rules.DNS_THROUGH) == true

    /** The fake-IP server, with an IPv6 range only when names are answered with IPv6 too. */
    fun server(ipv6: Boolean): JsonObject = JsonObject(
        buildMap {
            put("type", JsonPrimitive("fakeip"))
            put("tag", JsonPrimitive(TAG))
            put("inet4_range", JsonPrimitive(INET4))
            if (ipv6) put("inet6_range", JsonPrimitive(INET6))
        },
    )

    /**
     * [rules] with each rule that asks through a node ([throughNode]) preceded by its twin asking
     * [TAG] for address queries (A, and AAAA with [ipv6]), and one more at the end for what no
     * rule answers (the final server is the node's). Other query types still go to the node's DNS.
     * A name faked for a rule through a country is then looked up by that country's node.
     */
    fun rules(rules: List<JsonObject>, ipv6: Boolean): List<JsonObject> {
        val types = JsonArray(listOfNotNull(JsonPrimitive("A"), JsonPrimitive("AAAA").takeIf { ipv6 }))
        val real = JsonArray(REAL.map(::JsonPrimitive))
        val out = ArrayList<JsonObject>()
        for (rule in rules) {
            if (throughNode((rule["server"] as? JsonPrimitive)?.content)) {
                // The [REAL] names, under the same conditions, keep the real answer first.
                if (rule.keys.none { it in NAMED }) out += JsonObject(rule + ("domain_suffix" to real))
                out += JsonObject(rule + mapOf("query_type" to types, "server" to JsonPrimitive(TAG)))
            }
            out += rule
        }
        out += JsonObject(mapOf("domain_suffix" to real, "server" to JsonPrimitive(REMOTE)))
        out += JsonObject(mapOf("query_type" to types, "server" to JsonPrimitive(TAG)))
        return out
    }
}
