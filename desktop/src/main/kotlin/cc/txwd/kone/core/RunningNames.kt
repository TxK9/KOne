package cc.txwd.kone.core

/**
 * The names a running core knows nodes by, where the App's tags have changed since it started.
 * An update that renames or reorders nodes changes the App's tags, not those of the core already
 * running, and starting it afresh would cut every connection; so the App keeps talking to it in
 * its own names ([ClashApi] does the turning). [core]: the core's name for the App's tag, null
 * when the running core does not carry that node (a node new to the list). [app]: the App's tag
 * for the core's name, null when the App no longer lists that node under any tag.
 */
class RunningNames(private val toCore: Map<String, String?>) {
    private val toApp: Map<String, String> = toCore.entries.mapNotNull { (app, core) -> core?.let { it to app } }.toMap()

    fun core(tag: String): String? = if (tag in toCore) toCore[tag] else tag

    fun app(name: String): String? = toApp[name] ?: name.takeIf { it !in toCore }

    /** Whether nothing has changed since the core started. */
    val same: Boolean get() = toCore.isEmpty()

    override fun equals(other: Any?) = other is RunningNames && other.toCore == toCore

    override fun hashCode() = toCore.hashCode()

    override fun toString() = "RunningNames($toCore)"

    companion object {
        /** A core started from the list as it is: every node by its own tag. */
        val SAME = RunningNames(emptyMap())
    }
}
