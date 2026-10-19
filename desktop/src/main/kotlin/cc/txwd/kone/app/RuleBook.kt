package cc.txwd.kone.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.shared.RuleImport
import cc.txwd.kone.shared.Rules
import cc.txwd.kone.core.UserRule
import cc.txwd.kone.core.UserRules
import cc.txwd.kone.ui.RulesState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The person's routing, as the Rules page edits it: programs and sites sent straight out or
 * through the node, their own ordered rules, rules brought over from another client, Block ads,
 * and what nothing matches. Every change is kept in [preferences] at once and starts a running
 * connection afresh, except a new category list, which [keeper] downloads first. What it needs of
 * the rest of the App is [Host].
 */
class RuleBook(
    private val scope: CoroutineScope,
    private val preferences: Preferences,
    private val keeper: ListKeeper,
    private val host: Host,
) {
    interface Host {
        /** Starts a running connection afresh, so it takes the change. */
        fun restart()

        /** The programs sent straight out changed (Tools lists them too). */
        fun bypassChanged()
    }

    /** The Rules page, as kept. */
    var page by mutableStateOf(now())
        private set

    /** One line on the Rules page for a few seconds (what an import did). */
    private var notice: String? = null

    /** Reads the page again from what is kept (after a restore, or a category list's news). */
    fun show() {
        page = now()
    }

    private fun now(): RulesState = RulesState(
        preferences.bypass, preferences.direct, preferences.proxied, preferences.rules, preferences.blockAds,
        lists = keeper.named().associateWith { keeper.lists.status(it) }, listsBusy = keeper.busy, unmatchedDirect = preferences.unmatchedDirect,
        notice = notice,
    )

    private fun changed() {
        show()
        host.restart()
    }

    /** Sends [program] straight out, or back through KOne. */
    fun setBypass(program: String, straight: Boolean) {
        val before = preferences.bypass
        preferences.bypass = if (straight) before + program else before.filterNot { it.equals(program, ignoreCase = true) }
        if (preferences.bypass == before) return
        host.bypassChanged()
        changed()
    }

    /**
     * The site in what the person typed, straight out ([direct]) or through the node in Rule; taken
     * from the other list if it was there. False when it is not a site.
     */
    fun addSite(text: String, direct: Boolean): Boolean {
        val site = KoneConfig.site(text) ?: return false
        val (to, from) = if (direct) preferences.direct to preferences.proxied else preferences.proxied to preferences.direct
        if (site in to) return true
        if (direct) {
            preferences.direct = to + site
            preferences.proxied = from - site
        } else {
            preferences.proxied = to + site
            preferences.direct = from - site
        }
        changed()
        return true
    }

    fun removeSite(site: String) {
        preferences.direct -= site
        preferences.proxied -= site
        changed()
    }

    /** What nothing matched, in Rule mode: straight out ([direct]) or through the node. */
    fun setUnmatched(direct: Boolean) {
        if (preferences.unmatchedDirect == direct) return
        preferences.unmatchedDirect = direct
        changed()
    }

    fun setBlockAds(on: Boolean) {
        preferences.blockAds = on
        changed()
    }

    /** [rule] at the end, its value written as the template writes it; false when it does not fit its kind. */
    fun add(rule: UserRule): Boolean {
        val value = UserRules.value(rule.kind, rule.value) ?: return false
        if (rule.action !in UserRules.ACTIONS) return false
        val before = preferences.rules
        if (before.size >= UserRules.MOST) return false
        val added = UserRule(rule.kind, value, rule.action)
        if (added.kind == "set" && value !in keeper.named() && keeper.named().size >= UserRules.MOST_SETS) return false
        preferences.rules = before + added
        if (preferences.rules != before) {
            show()
            // A list not kept yet is downloaded first; the connection starts afresh once it is ready.
            if (added.kind == "set" && keeper.lists.digest(value) == null) keeper.update() else host.restart()
        }
        return true
    }

    fun remove(index: Int) {
        val rules = preferences.rules
        if (index !in rules.indices) return
        preferences.rules = rules.filterIndexed { i, _ -> i != index }
        changed()
    }

    /** The rule at [index] one place earlier ([up]) or later; order decides which matches first. */
    fun move(index: Int, up: Boolean) {
        val rules = preferences.rules.toMutableList()
        val other = if (up) index - 1 else index + 1
        if (index !in rules.indices || other !in rules.indices) return
        rules[index] = rules[other].also { rules[other] = rules[index] }
        preferences.rules = rules
        changed()
    }

    /**
     * Rules pasted from a Clash profile or v2rayN, added after the person's own in one go (one
     * reconnection, not one a rule), each checked as any rule is; a rule blocking the ad list turns
     * on Block ads. What happened, in a line.
     */
    fun import(text: String): String {
        val read = RuleImport.read(text, Rules.WINDOWS)
        val ads = read.blockAds && !preferences.blockAds
        if (ads) preferences.blockAds = true
        if (read.rules.isEmpty()) {
            if (ads) changed()
            return when {
                ads -> "Block ads turned on" + if (read.left > 0) "; ${read.left} left out" else ""
                read.left > 0 -> "None of these ${read.left} rules is one KOne takes"
                else -> "No rules in the clipboard"
            }
        }
        val before = preferences.rules
        preferences.rules = UserRules.clean(before + read.rules)
        val added = preferences.rules.size - UserRules.clean(before).size
        show()
        if (added > 0 && read.rules.any { it.kind == "set" }) keeper.update()
        // One reconnection for the rules and Block ads; a new category list restarts again once it is ready.
        if (ads || (added > 0 && read.rules.none { it.kind == "set" })) host.restart()
        val skipped = read.rules.size - added + read.left
        return "$added rules added" + (if (ads) ", Block ads turned on" else "") + if (skipped > 0) "; $skipped left out" else ""
    }

    /** [import], said on the Rules page for a few seconds. */
    fun importAndSay(text: String) {
        val said = import(text)
        notice = said
        show()
        scope.launch {
            delay(6_000)
            if (notice == said) {
                notice = null
                show()
            }
        }
    }
}
