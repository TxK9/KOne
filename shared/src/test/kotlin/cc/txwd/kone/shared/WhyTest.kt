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
        // The console's Claude block, and any other refusal, is said as what happened.
        assertEquals("Blocked", Why.explain("domain_suffix=[claude.ai] => reject", lists))
        // Program names may hold spaces; the rule's text is read against the person's list whole.
        val spaced = Why.Lists(listOf("My App.exe", "WeChat.exe"), emptyList(), emptyList())
        assertEquals("Your direct apps", Why.explain("process_name=[My App.exe WeChat.exe] => route(direct)", spaced))
        assertEquals("Your direct apps", Why.explain("process_name=My App.exe => route(direct)", spaced))
        assertEquals("Your rule", Why.explain("process_name=[My App.exe Other.exe] => route(direct)", spaced))
        assertEquals(null, Why.explain("", lists))
    }
}
