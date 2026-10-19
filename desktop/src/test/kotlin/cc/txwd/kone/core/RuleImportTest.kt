package cc.txwd.kone.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun v2rayNsRoutingRulesComeOverToo() {
        val text = """
            [
              {"remarks": "Block ads", "outboundTag": "block", "domain": ["geosite:category-ads-all", "keyword:tracker", "regexp:^ad\\."]},
              {"outboundTag": "direct", "domain": ["domain:example.cn", "full:www.example.org", "geosite:cn"], "ip": ["geoip:cn", "203.0.113.0/24", "geoip:private"]},
              {"outboundTag": "proxy", "domain": ["geosite:netflix"], "port": "443,8443"},
              {"outboundTag": "direct", "process": ["C:\\Games\\Steam.exe"]},
              {"outboundTag": "proxy", "enabled": false, "domain": ["domain:example.net"]},
              {"outboundTag": "proxy", "protocol": ["bittorrent"]}
            ]
        """.trimIndent()
        val read = RuleImport.read(text)
        assertEquals(
            listOf(
                UserRule("keyword", "tracker", "block"),
                UserRule("suffix", "example.cn", "direct"), UserRule("domain", "www.example.org", "direct"), UserRule("cidr", "203.0.113.0/24", "direct"),
                UserRule("set", "geosite-netflix", "proxy"), UserRule("port", "443", "proxy"), UserRule("port", "8443", "proxy"),
                UserRule("program", "Steam.exe", "direct"),
            ),
            read.rules,
        )
        // The ad list is Block ads, not a rule; regexp:, the template's own China lists, the local
        // network and the protocol-only rule are left out; the rule turned off is passed over.
        assertTrue(read.blockAds)
        assertEquals(5, read.left)
        // An Xray configuration's routing.rules reads the same.
        assertEquals(listOf(UserRule("suffix", "example.com", "proxy")), RuleImport.read("""{"routing":{"rules":[{"type":"field","outboundTag":"node","domain":["domain:example.com"]}]}}""").rules)
    }
}
