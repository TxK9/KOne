package cc.txwd.kone.shared

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
        val read = RuleImport.read(text, Rules.WINDOWS)
        assertEquals(
            listOf(
                Rule("suffix", "example.com", "proxy"), Rule("domain", "www.example.org", "direct"), Rule("keyword", "tracker", "block"),
                Rule("cidr", "203.0.113.0/24", "direct"), Rule("cidr", "2001:db8::/32", "proxy"), Rule("program", "Steam.exe", "direct"),
                Rule("port", "6881", "block"), Rule("set", "geosite-netflix", "proxy"), Rule("suffix", "example.net", "proxy"),
            ),
            read.rules,
        )
        // RULE-SET, the bad domain and the template's own China lists (GEOIP,CN and GEOSITE,cn) are left out; MATCH is not a rule.
        assertEquals(4, read.left)
    }

    @Test
    fun aGroupNamedForACountryGoesThroughThatCountry() {
        val text = """
            rules:
              - DOMAIN-SUFFIX,example.com,🇯🇵 Japan
              - DOMAIN-SUFFIX,example.org,🇺🇸 United States
              - GEOSITE,netflix,US Streaming
              - DOMAIN-SUFFIX,example.net,🚀 Proxy
              - DOMAIN-SUFFIX,example.edu,Japan and Singapore
              - DOMAIN-SUFFIX,example.cn,🎯 Global Direct
              - DOMAIN-KEYWORD,tracker,🛑 AdBlock
            """.trimIndent()
        assertEquals(
            listOf(
                Rule("suffix", "example.com", "via:JP"), Rule("suffix", "example.org", "via:US"), Rule("set", "geosite-netflix", "via:US"),
                // A group naming no country, or two, goes through the node in use; names saying direct or blocked do so.
                Rule("suffix", "example.net", "proxy"), Rule("suffix", "example.edu", "proxy"),
                Rule("suffix", "example.cn", "direct"), Rule("keyword", "tracker", "block"),
            ),
            RuleImport.read(text, Rules.WINDOWS).rules,
        )
    }

    @Test
    fun aWholeProfilesGroupsAreFollowedToTheirFirstChoice() {
        val text = """
            proxies:
              - {name: Tokyo 01, type: ss, server: 192.0.2.1, port: 8388, cipher: aes-128-gcm, password: example}
              - {name: Seattle 01, type: ss, server: 192.0.2.2, port: 8388, cipher: aes-128-gcm, password: example}
            proxy-groups:
              - {name: Select, type: select, proxies: [Japan, Seattle 01, DIRECT]}
              - {name: Japan, type: url-test, proxies: [Tokyo 01]}
              - {name: Home sites, type: select, proxies: [Stay, Select]}
              - {name: Stay, type: select, proxies: [DIRECT]}
              - {name: Ads, type: select, proxies: [REJECT, DIRECT]}
              - {name: Direct first in name, type: select, proxies: [Select, DIRECT]}
            rules:
              - DOMAIN-SUFFIX,example.com,Japan
              - DOMAIN-SUFFIX,example.org,Select
              - DOMAIN-SUFFIX,example.cn,Home sites
              - DOMAIN-KEYWORD,tracker,Ads
              - DOMAIN-SUFFIX,example.net,Direct first in name
              - DOMAIN,www.example.com,Seattle 01   # a node, named for its city
              - MATCH,Select
            """.trimIndent()
        val read = RuleImport.read(text, Rules.WINDOWS)
        assertEquals(
            listOf(
                Rule("suffix", "example.com", "via:JP"), Rule("suffix", "example.org", "proxy"), Rule("suffix", "example.cn", "direct"),
                Rule("keyword", "tracker", "block"),
                // The profile's own group is read by its first choice, not its name.
                Rule("suffix", "example.net", "proxy"),
                Rule("domain", "www.example.com", "via:US"),
            ),
            read.rules,
        )
        // The proxies and groups are not rules left out.
        assertEquals(0, read.left)
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
        val read = RuleImport.read(text, Rules.WINDOWS)
        assertEquals(
            listOf(
                Rule("keyword", "tracker", "block"),
                Rule("suffix", "example.cn", "direct"), Rule("domain", "www.example.org", "direct"), Rule("cidr", "203.0.113.0/24", "direct"),
                Rule("set", "geosite-netflix", "proxy"), Rule("port", "443", "proxy"), Rule("port", "8443", "proxy"),
                Rule("program", "Steam.exe", "direct"),
            ),
            read.rules,
        )
        // The ad list is Block ads, not a rule; regexp:, the template's own China lists, the local
        // network and the protocol-only rule are left out; the rule turned off is passed over.
        assertTrue(read.blockAds)
        assertEquals(5, read.left)
        // An Xray configuration's routing.rules reads the same.
        assertEquals(listOf(Rule("suffix", "example.com", "proxy")), RuleImport.read("""{"routing":{"rules":[{"type":"field","outboundTag":"node","domain":["domain:example.com"]}]}}""", Rules.WINDOWS).rules)
        // An outbound named for a country goes through it.
        assertEquals(listOf(Rule("suffix", "example.com", "via:JP")), RuleImport.read("""[{"outboundTag":"jp-out","domain":["domain:example.com"]}]""", Rules.WINDOWS).rules)
    }
}
