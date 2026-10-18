package cc.txwd.kone.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RuleImportTest {
    @Test
    fun aClashProfilesRulesComeOverAsKOnesOwn() {
        val text = """
            proxies: []
            rules:
              - DOMAIN-SUFFIX,example.com,Proxy
              - DOMAIN,www.Example.org,DIRECT
              - DOMAIN-KEYWORD,tracker,REJECT
              - IP-CIDR,203.0.113.0/24,DIRECT,no-resolve
              - IP-CIDR6,2001:db8::/32,Proxy
              - PROCESS-NAME,Steam.exe,DIRECT
              - DST-PORT,6881,REJECT
              - GEOSITE,netflix,Streaming
              - GEOIP,CN,DIRECT
              - 'DOMAIN-SUFFIX,example.net,🚀 Proxy'   # quoted, with a comment
              - RULE-SET,custom,Proxy
              - DOMAIN-SUFFIX,not a domain,Proxy
              - GEOSITE,cn,DIRECT
              - MATCH,Proxy
            """.trimIndent()
        val read = RuleImport.read(text)
        assertEquals(
            listOf(
                UserRule("suffix", "example.com", "proxy"), UserRule("domain", "www.example.org", "direct"), UserRule("keyword", "tracker", "block"),
                UserRule("cidr", "203.0.113.0/24", "direct"), UserRule("cidr", "2001:db8::/32", "proxy"), UserRule("program", "Steam.exe", "direct"),
                UserRule("port", "6881", "block"), UserRule("set", "geosite-netflix", "proxy"), UserRule("suffix", "example.net", "proxy"),
            ),
            read.rules,
        )
        // RULE-SET, the bad domain and the template's own China lists (GEOIP,CN and GEOSITE,cn) are left out; MATCH is not a rule.
        assertEquals(4, read.left)
    }
}
