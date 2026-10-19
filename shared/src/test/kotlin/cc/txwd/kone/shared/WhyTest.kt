package cc.txwd.kone.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class WhyTest {
    private val lists = Why.Lists(listOf("WeChat.exe"), listOf("example.org"), listOf("example.com"))

    @Test
    fun eachRuleIsSaidInThePersonsTerms() {
        assertEquals("Everything else", Why.explain("final", lists))
        assertEquals("Your direct sites", Why.explain("domain_suffix=[example.org] => route(direct)", lists))
        assertEquals("Your proxied sites", Why.explain("clash_mode=Rule domain_suffix=[example.com] => route(proxy)", lists))
        assertEquals("Category Netflix", Why.explain("rule_set=geosite-netflix => route(proxy)", lists))
        assertEquals("Ads", Why.explain("rule_set=[${Rules.ADS}] => reject", lists))
        assertEquals("Your rule", Why.explain("package_name=[com.example.chat] => route(direct)", lists))
        // Android's Home: its tailnet route, ahead of the local network.
        assertEquals("Home", Why.explain("ip_cidr=[100.64.0.0/10] => route(home-tailnet)", lists, home = "home-tailnet"))
        assertEquals("Local network", Why.explain("ip_is_private=true => route(direct)", lists, home = "home-tailnet"))
        assertEquals(null, Why.explain("", lists))
    }
}
