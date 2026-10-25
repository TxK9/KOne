package io.nekohasekai.sfa.connection

import cc.txwd.kone.shared.ConfigurationContent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class VideoCompatibilityTest {
    private val source = """{"outbounds":[{"tag":"proxy","type":"selector"},{"tag":"direct","type":"direct"}],"route":{"final":"proxy","rules":[{"action":"reject","domain_suffix":["blocked.invalid"]},{"ip_is_private":true,"outbound":"direct"},{"package_name":["org.example.direct"],"outbound":"direct"},{"domain_suffix":["example.com"],"outbound":"proxy"}]}}"""

    @Test fun disabledKeepsTheOriginalAndDoesNotDependOnCalling() {
        assertEquals(source, VideoCompatibility.apply(source, false))
        assertFalse(CallingOptions(videoTcp=true).enabled)
    }
    @Test fun preservesDirectAndAdministrativeRulesBeforeFallback() {
        val before=JSONObject(source).getJSONObject("route").getJSONArray("rules")
        val after=JSONObject(VideoCompatibility.apply(source,true)).getJSONObject("route").getJSONArray("rules")
        for(i in 0..2) assertEquals(before.getJSONObject(i).toString(),after.getJSONObject(i).toString())
        val guard=after.getJSONObject(3)
        assertEquals("and",guard.getString("mode"))
        assertEquals("udp",guard.getJSONArray("rules").getJSONObject(1).getString("network"))
        assertEquals(443,guard.getJSONArray("rules").getJSONObject(1).getInt("port"))
        assertEquals("default",guard.getString("method"))
        assertTrue(guard.getBoolean("no_drop"))
        assertFalse(guard.getJSONArray("rules").getJSONObject(0).has("outbound"))
        assertEquals(before.getJSONObject(3).toString(),after.getJSONObject(4).toString())
    }
    @Test fun repeatedApplicationDoesNotGrowRules() {
        val once=VideoCompatibility.apply(source,true)
        assertTrue(ConfigurationContent.same(once,VideoCompatibility.apply(once,true)))
    }
    @Test fun aDirectFinalNeverGetsTheCatchAllRejection() {
        val config=JSONObject(source)
        config.getJSONObject("route").put("final","direct")
        val rules=JSONObject(VideoCompatibility.apply(config.toString(),true)).getJSONObject("route").getJSONArray("rules")
        assertEquals("proxy",rules.getJSONObject(rules.length()-1).getString("outbound"))
    }
}
