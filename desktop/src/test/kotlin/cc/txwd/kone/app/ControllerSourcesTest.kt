package cc.txwd.kone.app

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControllerSourcesTest {
    private val source = Json.parseToJsonElement("""{"outbounds":[
      {"type":"socks","tag":"managed","server":"192.0.2.1","server_port":443},
      {"type":"hysteria2","tag":"relay","server":"192.0.2.2","server_port":443,"password":"synthetic"},
      {"type":"socks","tag":"own","server":"192.0.2.3","server_port":1080},
      {"type":"socks","tag":"chained-own","server":"192.0.2.4","server_port":1080,"detour":"relay"}
    ]}""") as JsonObject
    private val own = setOf("own", "chained-own")

    @Test fun `automatic and managed dependencies always require a guarded session`() {
        assertTrue(ControllerSources.choose(source, own, "auto").managed)
        assertTrue(ControllerSources.choose(source, own, "managed").managed)
        assertTrue(ControllerSources.choose(source, own, "chained-own").managed)
    }

    @Test fun `an independent local plan contains no managed credentials or fallback nodes`() {
        val plan = ControllerSources.choose(source, own, "own")
        assertFalse(plan.managed)
        val nodes = (plan.source["outbounds"] as JsonArray).toString()
        assertTrue("192.0.2.3" in nodes)
        assertFalse("192.0.2.1" in nodes)
        assertFalse("192.0.2.2" in nodes)
        assertFalse("192.0.2.4" in nodes)
        assertFalse("synthetic" in nodes)
    }

    @Test fun `cycles and missing relay references cannot become unguarded local routes`() {
        val value = Json.parseToJsonElement("""{"outbounds":[
          {"type":"socks","tag":"a","detour":"b"},
          {"type":"socks","tag":"b","detour":"a"},
          {"type":"socks","tag":"missing","detour":"absent"}
        ]}""") as JsonObject
        assertTrue(ControllerSources.localOnly(value, setOf("a", "b", "missing")) == null)
    }
}
