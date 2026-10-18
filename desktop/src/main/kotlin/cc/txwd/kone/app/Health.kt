package cc.txwd.kone.app

/**
 * Each node's recent tests, and what KOne decides from them. A single fast sample does not make
 * a node the best, nor one slow sample the worst: a node is scored by the median of its recent
 * delays, how much they vary, and how often it did not answer at all.
 */
class Health(private val keep: Int = 10) {
    private val history = HashMap<String, ArrayDeque<Int?>>()

    /** A test of [tag]: its delay in milliseconds, or null when it did not answer. */
    @Synchronized
    fun record(tag: String, delay: Int?) {
        val tests = history.getOrPut(tag) { ArrayDeque() }
        tests.addLast(delay?.takeIf { it > 0 })
        while (tests.size > keep) tests.removeFirst()
    }

    @Synchronized
    fun forget(keepOnly: Set<String>) {
        history.keys.retainAll(keepOnly)
    }

    /** The recent tests of [tag], oldest first: a delay, or null for no answer. */
    @Synchronized
    fun samples(tag: String): List<Int?> = history[tag]?.toList().orEmpty()

    /** The share of recent tests [tag] did not answer, 0 to 1; null before any test. */
    @Synchronized
    fun loss(tag: String): Double? {
        val tests = history[tag]?.takeIf { it.isNotEmpty() } ?: return null
        return tests.count { it == null }.toDouble() / tests.size
    }

    /** How many of the latest tests in a row [tag] did not answer. */
    @Synchronized
    fun failing(tag: String): Int = history[tag]?.reversed()?.takeWhile { it == null }?.size ?: 0

    /** How many of the latest tests in a row [tag] answered. */
    @Synchronized
    fun answering(tag: String): Int = history[tag]?.reversed()?.takeWhile { it != null }?.size ?: 0

    /**
     * Lower is better: the median delay, plus half the spread between its quartiles, plus a
     * second for each tenth of tests lost. Null when the node has not answered lately.
     */
    @Synchronized
    fun score(tag: String): Double? {
        val tests = history[tag] ?: return null
        if (tests.isEmpty() || tests.last() == null) return null
        val delays = tests.filterNotNull().sorted()
        if (delays.isEmpty()) return null
        fun at(fraction: Double) = delays[((delays.size - 1) * fraction).toInt()].toDouble()
        val lost = tests.count { it == null }.toDouble() / tests.size
        return at(0.5) + (at(0.75) - at(0.25)) / 2 + lost * 10_000
    }

    /** Whether [tag] has been tested at all. */
    @Synchronized
    fun tested(tag: String): Boolean = history[tag]?.isNotEmpty() == true

    /** The best scored of [tags], or null when none has answered lately. */
    fun best(tags: Collection<String>): String? = tags.mapNotNull { tag -> score(tag)?.let { tag to it } }.minByOrNull { it.second }?.first

    /**
     * Whether to move from [current] to [candidate]: the current one has stopped answering
     * ([FAILED] tests in a row), or the candidate is clearly better, by a fifth and 30 ms.
     */
    fun better(current: String?, candidate: String?): Boolean {
        if (candidate == null || candidate == current) return false
        if (current == null || failing(current) >= FAILED) return score(candidate) != null
        val now = score(current) ?: return score(candidate) != null
        val other = score(candidate) ?: return false
        return other < now * 0.8 && now - other >= 30
    }

    companion object {
        /** Tests in a row a node must fail before KOne leaves it. */
        const val FAILED = 3

        /** Tests in a row a node must answer before KOne goes back to it. */
        const val RECOVERED = 2
    }
}

/**
 * Picks the node for Automatic, with patience: a node that stops answering is left once it has
 * failed [Health.FAILED] tests in a row, and a better one is moved to only when it stays better
 * for two rounds, so a single lucky test does not make the connection hop.
 */
class Steer(private val health: Health) {
    private var waiting: String? = null
    private var rounds = 0

    /** The node to move to from [current] among [tags] after a round of tests, or null to stay. */
    fun next(current: String?, tags: Collection<String>): String? {
        val best = health.best(tags) ?: return null
        val stopped = current == null || current !in tags || !health.tested(current) || health.failing(current) >= Health.FAILED
        if (stopped) {
            waiting = null
            rounds = 0
            return best.takeIf { it != current }
        }
        if (!health.better(current, best)) {
            waiting = null
            rounds = 0
            return null
        }
        if (waiting == best) rounds++ else {
            waiting = best
            rounds = 1
        }
        if (rounds < 2) return null
        waiting = null
        rounds = 0
        return best
    }
}
