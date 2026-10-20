package cc.txwd.kone.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class RuleExportTest {
    @Test
    fun rulesWrittenOutComeBackTheSame() {
        val rules = listOf(
            Rule("suffix", "example.com", "via:JP"), Rule("domain", "www.example.org", "direct"), Rule("keyword", "tracker", "block"),
            Rule("cidr", "203.0.113.0/24", "direct"), Rule("cidr", "2001:db8::/32", "proxy"), Rule("program", "My Game.exe", "via:US"),
            Rule("port", "1000-2000", "direct"), Rule("port", "443", "proxy"), Rule("set", "geosite-netflix", "via:US"), Rule("set", "geoip-jp", "direct"),
        )
        val text = RuleExport.write(rules)
        assertEquals("DOMAIN-SUFFIX,example.com,Japan", text.lines().first())
        assertEquals("IP-CIDR,203.0.113.0/24,DIRECT,no-resolve", text.lines()[3])
        assertEquals(RuleImport.Read(rules, 0), RuleImport.read(text, Rules.WINDOWS))
        // On Android an app is its package, and comes back as one.
        val phone = listOf(Rule("app", "com.example.video", "via:GB"), Rule("suffix", "example.com", "proxy"))
        assertEquals(phone, RuleImport.read(RuleExport.write(phone), Rules.ANDROID).rules)
    }

    @Test
    fun everyCountryComesBackAsItself() {
        for (country in Countries.all) {
            val rule = listOf(Rule("suffix", "example.com", Rules.through(country.code)))
            assertEquals(rule, RuleImport.read(RuleExport.write(rule), Rules.WINDOWS).rules, country.en)
        }
    }
}
