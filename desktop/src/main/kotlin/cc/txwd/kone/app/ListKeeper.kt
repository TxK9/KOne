package cc.txwd.kone.app

import cc.txwd.kone.core.ServiceLink
import cc.txwd.kone.shared.CategoryLists
import cc.txwd.kone.core.UserRule
import cc.txwd.kone.core.UserRules
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The category lists the person's rules name, from download to the core that reads them: kept and
 * checked by [CategoryLists], handed to the service (which checks and keeps its own copy) or, in a
 * development run, copied into the App's rules folder. What it needs of the rest of the App is [Host].
 */
class ListKeeper(private val scope: CoroutineScope, private val host: Host) {
    interface Host {
        /** The person's rules as kept. */
        val rules: List<UserRule>
        /** The rules folder the App's own cores read (the service's when installed). */
        val folder: String
        /** The pipe of the running connection, when KOne is connected through the service. */
        val service: ServiceLink?
        /** KOne's local port while it is the system proxy, for downloads. */
        val proxied: Int?
        val online: Boolean
        /** Starts a running connection afresh. */
        fun restart()
        /** The Rules page shows the lists' state again. */
        fun changed()
    }

    /**
     * The lists, kept and checked by the App. sing-box reads each the way the template will before
     * it is kept; without an installed core only its header is checked.
     */
    val lists = CategoryLists(
        File(Places.data, "rule-sets"),
        fetch = { url, etag -> CategoryDownload.download(url, etag, host.proxied) },
        parses = { file -> Places.core?.let { CategoryDownload.singBoxReads(it, file) } ?: true },
    )

    var busy = false
        private set

    /** The lists the running connection was started with; one that becomes ready later starts it afresh. */
    var running: Set<String> = emptySet()

    private var updating: Job? = null

    /** The lists the person's rules name, each once. */
    fun named() = UserRules.sets(UserRules.clean(host.rules))

    /**
     * Looks at the lists the rules name: each due (or every one, when [force]) is downloaded if it
     * changed and checked; then the service is handed what it lacks. A list that is ready for the
     * first time starts a running connection afresh, so its rules take effect; an update to a
     * list already in use is read by the running core itself as the file is replaced (sing-box
     * watches its local lists), so no connection is cut for it.
     */
    fun update(force: Boolean = false) {
        if (updating?.isActive == true) return
        val names = named()
        updating = scope.launch {
            busy = true
            host.changed()
            withContext(Dispatchers.IO) {
                lists.keepOnly(names.toSet())
                val now = System.currentTimeMillis()
                names.filter { force || lists.due(it, now) }.forEach { lists.update(it, now) }
            }
            val ready = withContext(Dispatchers.IO) { ready() }
            busy = false
            host.changed()
            if (host.online && !running.containsAll(ready)) host.restart()
        }
    }

    /**
     * Makes the kept lists available to the core that will run, and says which are: handed to the
     * service over [link] (it checks each again, and keeps only what passes), or, with no service
     * (a development run), copied into the App's own rules folder.
     */
    fun deliver(link: ServiceLink?): Set<String> {
        val names = named()
        if (link != null) {
            val kept = link.ruleSets() ?: return emptySet()
            return names.filter { name ->
                val sha = lists.digest(name) ?: return@filter kept.containsKey(name)
                kept[name] == sha || (lists.bytes(name)?.let { link.putRuleSet(name, it, sha) } ?: false) || kept.containsKey(name)
            }.toSet()
        }
        val folder = File(host.folder, "sets")
        return names.filter { name ->
            val sha = lists.digest(name)
            val target = File(folder, "$name.srs")
            if (sha != null && (!target.isFile || CategoryLists.sha256(target.readBytes()) != sha)) {
                // Beside it, then into place: a running core reloads the list as it changes.
                runCatching {
                    folder.mkdirs()
                    val fresh = File(folder, "$name.srs.new")
                    lists.bytes(name)?.let(fresh::writeBytes)
                    java.nio.file.Files.move(fresh.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            target.isFile
        }.toSet()
    }

    /**
     * Hands the kept lists to whoever keeps them for the core, and says which the core can read:
     * the service (over the connection's pipe, or one opened for this) when KOne is installed, as
     * its core and the App's own both read the service's rules folder; the App's own rules folder
     * in a development run. Blocks; not on the main thread.
     */
    fun ready(): Set<String> {
        if (Places.rules == null) return deliver(null)
        host.service?.let { return deliver(it) }
        val opened = runCatching { ServiceLink.open(Places.service) }.getOrNull()
        return opened?.use { deliver(it) } ?: named().filter { File(host.folder, "sets/$it.srs").isFile }.toSet()
    }

    /** The rules for a core the App runs itself: those naming a list not in [ready] are left out, as the service does. */
    fun routesWith(ready: Set<String>): List<UserRule> =
        host.rules.filter { rule -> rule.kind != "set" || UserRules.value("set", rule.value) in ready }
}
