package io.nekohasekai.sfa.connection

import io.nekohasekai.sfa.selfuse.PairedProfile
import io.nekohasekai.sfa.selfuse.HomeLink
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ConnectionCompatibilityTest {
    private fun source()=PairedProfile.fromLink("vless://00000000-0000-0000-0000-000000000001@node.invalid:443?security=tls&type=tcp",HomeLink.Home(false,emptyList(),"example"))
    @Test fun disabledKeepsExactSourceAndPresetNeverReplacesTheChosenNode() {
        val raw=source();assertEquals(raw,ConnectionCompatibility.apply(raw,CallingOptions()))
        val original=JSONObject(raw);val applied=JSONObject(ConnectionCompatibility.apply(raw,CallingOptions(enabled=true)))
        assertEquals(original.getJSONArray("outbounds").toString(),applied.getJSONArray("outbounds").toString())
        assertEquals(original.getJSONObject("dns").getString("strategy"),applied.getJSONObject("dns").getString("strategy"))
        assertFalse(applied.getJSONArray("inbounds").getJSONObject(0).has("mtu"))
        val rules=applied.getJSONObject("route").getJSONArray("rules")
        val direct=(0 until rules.length()).first { rules.getJSONObject(it).optString("clash_mode")=="Direct" }
        val udp=(0 until rules.length()).first { rules.getJSONObject(it).optString("network")=="udp" }
        assertTrue(udp>direct)
        val privateRoute=(0 until rules.length()).first { rules.getJSONObject(it).optBoolean("ip_is_private") }
        assertTrue(udp>privateRoute);assertEquals("proxy",rules.getJSONObject(udp).getString("outbound"))
        assertEquals("[500,4500]",rules.getJSONObject(udp).getJSONArray("port").toString())
        assertFalse(applied.toString().contains("mcc234"));assertFalse(applied.toString().contains("\u82f1\u56fd"))
    }
    @Test fun administrativeRefusalsAndExistingDnsRulesRemainAheadAndUnedited() {
        val raw=JSONObject(source());val route=raw.getJSONObject("route").getJSONArray("rules");val dns=raw.getJSONObject("dns").getJSONArray("rules")
        route.put(JSONObject().put("action","reject").put("domain_suffix",org.json.JSONArray(listOf("blocked.invalid"))))
        dns.put(JSONObject().put("action","reject").put("domain_suffix",org.json.JSONArray(listOf("blocked.invalid"))))
        val result=JSONObject(ConnectionCompatibility.apply(raw.toString(),CallingOptions(enabled=true)))
        for((before,after) in listOf(route to result.getJSONObject("route").getJSONArray("rules"),dns to result.getJSONObject("dns").getJSONArray("rules"))) {
            assertEquals(before.length()+1,after.length())
            for(i in 0 until before.length()) assertEquals(before.getJSONObject(i).toString(),after.getJSONObject(i).toString())
        }
    }
    @Test fun userCanChooseAddressStrategyAndMtuWithoutAnyCountryOrOperator() {
        val raw=source()
        for(dns in CallingDns.entries) for(mtu in listOf(0,1280,1400,1500)) {
            val result=JSONObject(ConnectionCompatibility.apply(raw,CallingOptions(true,dns,mtu)))
            assertEquals(dns.strategy ?: JSONObject(raw).getJSONObject("dns").getString("strategy"),result.getJSONObject("dns").getString("strategy"))
            if(mtu==0) assertFalse(result.getJSONArray("inbounds").getJSONObject(0).has("mtu"))
            else assertEquals(mtu,result.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
            val dir=System.getProperty("knine.previewDir")
            if(dir!=null) File(dir,"calling-${dns.name}-$mtu.json").writeText(result.toString(2))
        }
    }
    @Test fun homeEndpointsAndHomeRouteStillUseTheSamePrivatePath() {
        val raw=PairedProfile.fromLink("vless://00000000-0000-0000-0000-000000000001@node.invalid:443?security=tls&type=tcp",HomeLink.Home(true,listOf("203.0.113.0/24"),"example"))
        val before=JSONObject(raw);val after=JSONObject(ConnectionCompatibility.apply(raw,CallingOptions(true)))
        assertEquals(before.getJSONArray("endpoints").toString(),after.getJSONArray("endpoints").toString())
        assertEquals(before.getJSONArray("outbounds").toString(),after.getJSONArray("outbounds").toString())
        val rules=before.getJSONObject("route").getJSONArray("rules")
        val applied=after.getJSONObject("route").getJSONArray("rules")
        val originalHome=(0 until rules.length()).map{rules.getJSONObject(it)}.filter{it.optString("outbound")=="home-tailnet"}
        assertTrue(originalHome.isNotEmpty())
        for(rule in originalHome) assertTrue((0 until applied.length()).any{applied.getJSONObject(it).toString()==rule.toString()})
    }
    @Test fun unsupportedCustomConfigsAndUnsafeMtuAreRefusedBeforeSaving() {
        try {ConnectionCompatibility.apply("""{"outbounds":[{"type":"direct","tag":"direct"}]}""",CallingOptions(true));fail()}catch(_:Exception){}
        try {CallingOptions(mtu=600);fail()}catch(_:IllegalArgumentException){}
    }
}
