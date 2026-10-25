package io.nekohasekai.sfa.compose.screen.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeHealthRulesTest {
    private fun judge(vararg ms: Int?) = NodeHealthRules.judge(
        ms.map { if (it == null) LatencySample(null, "Timed out") else LatencySample(it) },
        NodeHealthRules.TCP_SLOW_MS,
        NodeHealthRules.TCP_JITTER_MS,
    )

    @Test
    fun badgesShowTheWeatherAndLatencyButNoReason() {
        // The seal picture beside it shows the weather, so the words carry no seal letter.
        assertEquals("245 ms", NodeHealthRules.badge(NodeReading("245 ms", NodeHealth.Good)))
        assertEquals("Slow · 1203 ms", NodeHealthRules.badge(NodeReading("1203 ms", NodeHealth.Slow)))
        assertEquals("Slow", NodeHealthRules.badge(NodeReading("Proxy unreachable", NodeHealth.Slow, failed = true)))
        assertEquals("Unreachable", NodeHealthRules.badge(NodeReading("Proxy unreachable", NodeHealth.Down, failed = true)))
    }

    @Test
    fun healthMapsToItsWeather() {
        assertEquals(NodeWeather.Clear, NodeWeather.of(NodeHealth.Good))
        assertEquals(NodeWeather.Mist, NodeWeather.of(NodeHealth.Slow))
        assertEquals(NodeWeather.Cut, NodeWeather.of(NodeHealth.Down))
        assertEquals(listOf("S", "M", "X", "Z"), NodeWeather.entries.map { it.char })
    }

    @Test
    fun steadyFastRepliesAreGood() {
        assertEquals(NodeReading("193 ms", NodeHealth.Good), judge(190, 201, 188, 193))
        assertNull(judge())
    }

    @Test
    fun slowOrJumpyRepliesAreSlow() {
        assertEquals(NodeHealth.Slow, judge(180, 450)!!.health)
        assertEquals(NodeHealth.Good, judge(330, 329, 338)!!.health)
        assertEquals(NodeHealth.Slow, judge(120, 290, 130)!!.health)
        // A recent failure keeps the warning until it leaves the window.
        assertEquals(NodeHealth.Slow, judge(null, 190, 200)!!.health)
    }

    @Test
    fun consecutiveFailuresAreDownAndSayWhy() {
        assertEquals(NodeReading("Timed out", NodeHealth.Slow, failed = true), judge(190, null))
        assertEquals(NodeReading("Timed out", NodeHealth.Down, failed = true), judge(190, null, null))
        val refused = NodeHealthRules.judge(
            listOf(LatencySample(null, "Connection refused"), LatencySample(null, "Connection refused")),
            NodeHealthRules.TCP_SLOW_MS,
            NodeHealthRules.TCP_JITTER_MS,
        )
        assertEquals(NodeReading("Connection refused", NodeHealth.Down, failed = true), refused)
    }

    @Test
    fun proxyTestUsesWiderLimits() {
        val reading = NodeHealthRules.judge(
            listOf(LatencySample(620), LatencySample(700), LatencySample(650)),
            NodeHealthRules.PROXY_SLOW_MS,
            NodeHealthRules.PROXY_JITTER_MS,
        )
        assertEquals(NodeReading("650 ms", NodeHealth.Good), reading)
    }
}
