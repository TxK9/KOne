package cc.txwd.kone.app

import cc.txwd.kone.core.CoreConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectionWatchTest {
    private fun c(id: String, target: String, via: String, down: Long, up: Long) = CoreConnection(id, "chrome.exe", target, via, down, up)

    @Test
    fun aSiteThatKeepsFailingStraightOutIsAdvisedAndOneThatWorksIsNot() {
        val watch = ConnectionWatch()
        // Three tries at a site straight out, each sending and getting nothing back.
        watch.sample(listOf(c("1", "blocked.example.com:443", "direct", 0, 517), c("2", "ok.example.cn:443", "direct", 4_096, 600)), now = 0)
        watch.sample(listOf(c("3", "blocked.example.com:443", "direct", 0, 517)), now = 2_000) // 1 closed with nothing
        watch.sample(listOf(c("3", "blocked.example.com:443", "direct", 0, 517), c("4", "blocked.example.com:443", "direct", 0, 517)), now = 10_500) // 3 stalled
        assertEquals(emptyList(), watch.advice())
        watch.sample(emptyList(), now = 12_000) // 4 closed with nothing
        assertEquals(listOf(ConnectionWatch.Advice("blocked.example.com", 3)), watch.advice())
        // Once it has worked straight out, it is no longer advised.
        watch.sample(listOf(c("5", "blocked.example.com:443", "direct", 9_000, 600)), now = 13_000)
        assertEquals(emptyList(), watch.advice())
    }

    @Test
    fun addressesNodesAndDismissedSitesAreNotAdvised() {
        val watch = ConnectionWatch()
        val tries = (1..4).map { c("a$it", "203.0.113.9:443", "direct", 0, 300) } + (1..4).map { c("n$it", "slow.example.com:443", "Japan", 0, 300) } +
            (1..4).map { c("d$it", "nope.example.com:443", "direct", 0, 300) }
        watch.sample(tries, now = 0)
        watch.sample(emptyList(), now = 1_000)
        assertEquals(listOf("nope.example.com"), watch.advice().map { it.site })
        watch.dismiss("nope.example.com")
        assertEquals(emptyList(), watch.advice())
        // Nothing sent, nothing to judge: an idle connection is not a failure.
        val idle = ConnectionWatch()
        idle.sample((1..5).map { c("i$it", "idle.example.com:443", "direct", 0, 0) }, now = 0)
        idle.sample(emptyList(), now = 20_000)
        assertEquals(emptyList(), idle.advice())
    }

    @Test
    fun aNodeWhoseConnectionsStallIsSaidToBeStalling() {
        val watch = ConnectionWatch()
        watch.sample((1..3).map { c("s$it", "www.example.com:443", "Japan", 0, 400) }, now = 0)
        assertFalse(watch.stalling("Japan", now = 0))
        watch.sample((1..3).map { c("s$it", "www.example.com:443", "Japan", 0, 400) }, now = 9_000)
        assertTrue(watch.stalling("Japan", now = 9_000))
        assertFalse(watch.stalling("United States", now = 9_000))
        // One answer through it, and it is not stalling; and old outcomes fall away.
        watch.sample(listOf(c("ok", "www.example.org:443", "Japan", 2_048, 400)), now = 10_000)
        assertFalse(watch.stalling("Japan", now = 10_000))
        val later = ConnectionWatch()
        later.sample((1..3).map { c("s$it", "www.example.com:443", "Japan", 0, 400) }, now = 0)
        later.sample(emptyList(), now = 1_000)
        assertTrue(later.stalling("Japan", now = 1_000))
        assertFalse(later.stalling("Japan", now = 1_000 + ConnectionWatch.STALL_WINDOW + 1))
    }
}
