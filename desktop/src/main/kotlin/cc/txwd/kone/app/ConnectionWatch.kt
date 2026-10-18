package cc.txwd.kone.app

import cc.txwd.kone.core.CoreConnection
import cc.txwd.kone.core.KoneConfig

/**
 * What real traffic says, read from the core's connection list as KOne samples it (every few
 * seconds while connected, each second while Tools is open), with no probing of its own:
 *
 * - **Sites that fail straight out.** A connection that sent and got nothing back for a while,
 *   or closed that way, counts against its site and its way (straight out or a node). A site
 *   that keeps failing straight out and has not once worked that way is a suggestion: send it
 *   through the node.
 * - **A node that stalls.** Several connections through the node in use failing at once, and
 *   none working, is reason to test it now rather than at its next turn.
 *
 * Only what a connection's bytes show is used: a connection sees no content, and nothing is kept
 * beyond a few minutes in memory.
 */
class ConnectionWatch(private val window: Long = WINDOW) {
    /** One outcome: a site, the way it went ("direct" or a node), whether it worked, when. */
    private data class Outcome(val site: String, val via: String, val worked: Boolean, val at: Long)

    private data class Seen(val site: String?, val via: String, val first: Long, var down: Long, var up: Long, var judged: Boolean)

    private val open = HashMap<String, Seen>()
    private val outcomes = ArrayDeque<Outcome>()
    private val dismissed = HashSet<String>()

    /** A site to send through the node: how many tries failed straight out lately. */
    data class Advice(val site: String, val failures: Int)

    @Synchronized
    fun sample(connections: List<CoreConnection>, now: Long) {
        val listed = HashSet<String>(connections.size)
        for (connection in connections) {
            listed += connection.id
            val seen = open.getOrPut(connection.id) { Seen(site(connection.target), connection.via, now, 0, 0, false) }
            seen.down = connection.down
            seen.up = connection.up
            // Answered: it worked, once; still waiting for its first byte back after a while: it failed, once.
            if (!seen.judged && seen.down >= ANSWERED) judge(seen, true, now)
            else if (!seen.judged && seen.up > 0 && seen.down == 0L && now - seen.first >= STALL) judge(seen, false, now)
        }
        // Closed since the last look: judged by what it got, if not already.
        val closed = open.keys - listed
        for (id in closed) {
            val seen = open.remove(id) ?: continue
            if (!seen.judged && seen.up > 0) judge(seen, seen.down >= ANSWERED, now)
        }
        while (outcomes.isNotEmpty() && now - outcomes.first().at > window) outcomes.removeFirst()
    }

    /** Sites that failed straight out at least [FAILURES] times lately and never worked straight out, most failures first. */
    @Synchronized
    fun advice(): List<Advice> = outcomes.filter { it.via == DIRECT && it.site.isNotEmpty() }.groupBy { it.site }
        .filter { (site, list) -> site !in dismissed && list.none { it.worked } && list.size >= FAILURES }
        .map { (site, list) -> Advice(site, list.size) }
        .sortedByDescending { it.failures }

    /** Whether connections through [via] are stalling: several failed lately and none worked. */
    @Synchronized
    fun stalling(via: String, now: Long): Boolean {
        val recent = outcomes.filter { it.via == via && now - it.at <= STALL_WINDOW }
        return recent.count { !it.worked } >= STALLS && recent.none { it.worked }
    }

    /** The person said no to this advice; it is not given again this session. */
    @Synchronized
    fun dismiss(site: String) {
        dismissed += site
    }

    /** The site of a connection the advice may name: its domain (not an address), else null. */
    private fun site(target: String): String? {
        if (target.startsWith("[")) return null
        return target.substringBeforeLast(':', target).lowercase().takeIf { KoneConfig.isSite(it) }
    }

    private fun judge(seen: Seen, worked: Boolean, now: Long) {
        seen.judged = true
        outcomes.addLast(Outcome(seen.site ?: "", seen.via, worked, now))
        while (outcomes.size > MOST) outcomes.removeFirst()
    }

    companion object {
        const val DIRECT = "direct"
        /** Bytes back that count as an answer (a TLS server's first flight is larger). */
        const val ANSWERED = 512L
        /** How long a connection may wait for its first byte back before it counts as failed. */
        const val STALL = 8_000L
        /** How long outcomes are kept. */
        const val WINDOW = 5 * 60_000L
        const val FAILURES = 3
        const val STALLS = 3
        const val STALL_WINDOW = 30_000L
        const val MOST = 2_000
    }
}
