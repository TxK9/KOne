package cc.txwd.kone.app

/**
 * How often KOne tests every node. Each test is a real connection to the node, so KOne tests
 * often only while someone may be looking at the delays: once a minute with the window in sight,
 * every ten minutes when it is minimised or in the tray, and less often still for long lists.
 * The node in use is watched separately (every 15 seconds while connected); when it stops
 * answering, or the window comes back to delays gone stale, every node is tested at once.
 */
internal object Sweeps {
    /** Up to this many nodes, a sweep is cheap enough to make each minute in sight. */
    private const val FEW = 30

    /** The time between sweeps of [nodes] nodes, with the window [inSight] or not. */
    fun every(inSight: Boolean, nodes: Int): Long = when {
        inSight && nodes <= FEW -> 60_000
        inSight || nodes <= FEW -> 600_000
        else -> 1_800_000
    }

    /** Whether the delays of a sweep [age] milliseconds old are stale to someone looking at them. */
    fun stale(age: Long, nodes: Int): Boolean = age >= every(inSight = true, nodes)

    /**
     * Whether the node in use not answering [failing] tests in a row calls for every node to be
     * tested now, the last sweep being [age] milliseconds old: so that a failover picks a node
     * that answers now, not one that answered ten minutes ago.
     */
    fun afterFailure(failing: Int, age: Long): Boolean = failing >= 1 && age >= 60_000

    /**
     * How long the core that tests the nodes while KOne is disconnected lives on once the window
     * is out of sight: long enough for a quick look elsewhere, not all day.
     */
    const val PROBE_REST = 120_000L
}
