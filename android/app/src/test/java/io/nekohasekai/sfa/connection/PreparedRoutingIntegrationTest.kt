package io.nekohasekai.sfa.connection

import io.nekohasekai.sfa.selfuse.HomeLink
import io.nekohasekai.sfa.selfuse.PairedProfile
import io.nekohasekai.sfa.selfuse.Routing
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreparedRoutingIntegrationTest {
    private fun source() = PairedProfile.fromLink(
        "vless://00000000-0000-0000-0000-000000000001@node.invalid:443?security=tls&type=tcp", HomeLink.Home(false, emptyList(), "example"))
    private fun objects(array: JSONArray) = (0 until array.length()).map(array::getJSONObject)
    private fun runtime(choices: Routing.Choices, raw: String = source()) = JSONObject(RuntimeConnectionConfig.prepare(
        Routing.apply(raw, choices), CallingOptions(), AppPolicySnapshot()))

    @Test fun explicitDirectSitesSurviveTheFinalGlobalGuard() {
        val config = runtime(Routing.Choices(direct=listOf("example.com")))
        val routes = objects(config.getJSONObject("route").getJSONArray("rules"))
        val dns = objects(config.getJSONObject("dns").getJSONArray("rules"))
        val direct = routes.indexOfFirst { it.optString("outbound") == "direct" && it.optJSONArray("domain_suffix")?.toString()?.contains("example.com") == true }
        assertTrue(direct >= 0 && direct < routes.indexOfFirst { it.optString("clash_mode") == "Global" })
        val directDns = dns.indexOfFirst { it.optString("server") == "local-dns" && it.optJSONArray("domain_suffix")?.toString()?.contains("example.com") == true }
        assertTrue(directDns >= 0 && directDns < dns.indexOfFirst { it.optString("clash_mode") == "Global" })
    }

    @Test fun fakeIpAddressQueriesStillPrecedeGlobalRemoteLookups() {
        val config = runtime(Routing.Choices(fakeIp=true))
        val rules = objects(config.getJSONObject("dns").getJSONArray("rules"))
        val fake = rules.indexOfFirst { it.optString("clash_mode") == "Global" && it.optString("server") == "fake-dns" }
        val remote = rules.indexOfFirst { it.optString("clash_mode") == "Global" && it.optString("server") == "proxy-dns" && !it.has("domain_suffix") }
        assertTrue(fake >= 0 && fake < remote)
        assertTrue(rules[fake].getJSONArray("query_type").toString().contains("A"))
    }

    @Test fun managedRefusalsStillWinOverAnExplicitDirectSite() {
        val raw = JSONObject(source())
        raw.getJSONObject("route").getJSONArray("rules").put(JSONObject().put("action","reject").put("domain_suffix",JSONArray(listOf("blocked.invalid"))))
        val config = runtime(Routing.Choices(direct=listOf("blocked.invalid")), raw.toString())
        for ((section, field) in listOf("route" to "outbound", "dns" to "server")) {
            val rules=objects(config.getJSONObject(section).getJSONArray("rules"))
            val blocked=rules.indexOfFirst { it.optString("action")=="reject" && it.optJSONArray("domain_suffix")?.toString()?.contains("blocked.invalid")==true }
            val direct=rules.indexOfFirst { it.has(field) && it.optJSONArray("domain_suffix")?.toString()?.contains("blocked.invalid")==true }
            assertTrue(blocked>=0 && (direct<0 || blocked<direct))
        }
    }
}
