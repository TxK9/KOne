package cc.txwd.kone.app

/**
 * "Make this site work": a site tried straight out and through a few nodes, each with how long it
 * took to answer, and which way serves it best. The answer is a list entry the person can keep
 * (Direct sites or Proxied sites on Rules), so the rule comes from evidence rather than syntax.
 *
 * The tries run in a core of KOne's own, never the running one: the core keeps each test's result
 * for its Automatic group, and a slow site must not make Automatic move.
 */
object SiteCheck {
    /** The way to keep for a site, from what the tries found. */
    enum class Way { Direct, Proxy, Either, Neither }

    /** One try: the way ("direct" or a node's tag), what to call it, and its time, or null for no answer. */
    data class Try(val tag: String, val title: String, val millis: Int?)

    /** The address tried: the site's front page over HTTPS (the core only tests HTTPS addresses). */
    fun url(site: String) = "https://$site/"

    /**
     * Which way serves the site: one that answers when the other does not; when both answer,
     * the one clearly faster (a fifth faster and by 50 ms or more), otherwise either.
     */
    fun way(direct: Int?, nodes: List<Int?>): Way {
        val best = nodes.filterNotNull().minOrNull()
        return when {
            direct == null && best == null -> Way.Neither
            direct == null -> Way.Proxy
            best == null -> Way.Direct
            direct <= best * 0.8 && best - direct >= 50 -> Way.Direct
            best <= direct * 0.8 && direct - best >= 50 -> Way.Proxy
            else -> Way.Either
        }
    }

    /** The tries as rows: each way, whether it answered, and how fast. */
    fun rows(tries: List<Try>): List<Check> = tries.map { one ->
        when (val millis = one.millis) {
            null -> Check(one.title, Outcome.Fail, "No answer")
            else -> Check(one.title, if (millis >= 3_000) Outcome.Warn else Outcome.Pass, "$millis ms")
        }
    }

    /** What the tries add up to, in a sentence. */
    fun verdict(site: String, way: Way): String = when (way) {
        Way.Direct -> "$site works best straight out"
        Way.Proxy -> "$site works best through a node"
        Way.Either -> "$site works both ways at about the same speed; nothing to change"
        Way.Neither -> "$site does not answer from here, straight out or through these nodes"
    }
}
