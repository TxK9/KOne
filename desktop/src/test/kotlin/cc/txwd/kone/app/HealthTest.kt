package cc.txwd.kone.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HealthTest {
    private fun Health.tests(tag: String, vararg delays: Int?) = delays.forEach { record(tag, it) }

    @Test
    fun testsMoveWithTheirNodesThoughNamesakesTradeTags() {
        val health = Health()
        health.tests("Tokyo", 100, 110)
        health.tests("Tokyo 2", null)
        health.tests("Rome", 300)
        // Tokyo and Tokyo 2 trade tags; Paris takes Rome's, Rome being gone.
        health.rename(mapOf("Tokyo" to "Tokyo 2", "Tokyo 2" to "Tokyo", "Paris" to "Rome"))
        assertEquals(listOf<Int?>(null), health.samples("Tokyo"))
        assertEquals(listOf<Int?>(100, 110), health.samples("Tokyo 2"))
        assertEquals(emptyList(), health.samples("Rome"))
        assertEquals(1, health.failing("Tokyo"))
    }

    @Test
    fun aSteadyNodeBeatsOneWithALuckySample() {
        val health = Health()
        health.tests("steady", 120, 125, 118, 122, 121)
        health.tests("lucky", 400, 60, 380, 420, 50)
        assertEquals("steady", health.best(listOf("steady", "lucky")))
        health.tests("lossy", 80, null, 85, null, 82)
        assertEquals(0.4, health.loss("lossy"))
        assertEquals("steady", health.best(listOf("steady", "lossy")), "losing two tests in five costs more than 40 ms saves")
        assertNull(health.loss("untested"))
    }

    @Test
    fun streaksAndScoresFollowTheLatestTests() {
        val health = Health(keep = 4)
        health.tests("a", 100, null, null, null)
        assertEquals(3, health.failing("a"))
        assertNull(health.score("a"), "a node whose last test failed has no score")
        health.tests("a", 90, 95)
        assertEquals(2, health.answering("a"))
        assertEquals(0, health.failing("a"))
        assertEquals(0.5, health.loss("a"), "only the last four are kept")
    }

    @Test
    fun aClearlyBetterNodeWinsOnlyAfterTwoRounds() {
        val health = Health()
        val steer = Steer(health)
        val tags = listOf("a", "b")
        health.tests("a", 200, 200)
        health.tests("b", 100, 100)
        assertEquals("b", steer.next(null, tags), "nothing chosen yet: the best at once")
        assertNull(steer.next("a", tags), "better once is not enough")
        assertEquals("b", steer.next("a", tags), "better twice in a row")
        health.tests("c", 190)
        assertNull(steer.next("a", listOf("a", "c")), "190 against 200 is not clearly better")
    }

    @Test
    fun aNodeThatStopsAnsweringIsLeftAfterThreeFailures() {
        val health = Health()
        val steer = Steer(health)
        val tags = listOf("a", "b")
        health.tests("a", 100, 100)
        health.tests("b", 150, 150)
        health.record("a", null)
        assertNull(steer.next("a", tags), "one failure is not enough")
        health.record("a", null)
        health.record("a", null)
        assertEquals("b", steer.next("a", tags))
        assertTrue(health.better("a", "b"))
        assertFalse(health.better("b", "a"), "a node that is not answering is never better")
    }
}
