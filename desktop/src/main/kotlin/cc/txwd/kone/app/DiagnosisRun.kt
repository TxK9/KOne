package cc.txwd.kone.app

import cc.txwd.kone.core.ClashApi
import cc.txwd.kone.core.ExitTrace
import cc.txwd.kone.core.ReachCheck
import cc.txwd.kone.core.ServiceLink
import cc.txwd.kone.ui.ToolsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tools → Diagnose as the Windows App runs it: the way out checked step by step, through the same
 * path as the person's traffic, each shown as it finishes, then one suggestion; and the result as
 * plain text for whoever helps. What each step concludes is [Diagnosis]'s; what it needs of the
 * rest of the App is [Host].
 */
class DiagnosisRun(private val scope: CoroutineScope, private val host: Host) {
    interface Host {
        val tools: ToolsState
        fun update(change: (ToolsState) -> ToolsState)
        /** The network under KOne, as the sign-in check sees it; null when it could not look. */
        suspend fun reach(): ReachCheck?
        /** KOne's local port while it is the system proxy. */
        val proxied: Int?
        /** Whether a key is bound, whether the console has sent its nodes, and how many nodes there are. */
        val bound: Boolean
        val sourceReady: Boolean
        val nodeCount: Int
        /** Whether KOne goes out through its virtual adapter (and so through the service). */
        val throughAdapter: Boolean
        /** Whether the running connection goes through the service now. */
        val serviceConnected: Boolean
        /** A core to test through, running or probing. */
        val api: ClashApi?
        fun inUse(): String?
        fun nameOf(tag: String): String
        /** A delay measured here, kept as any test's is. */
        fun record(tag: String, delay: Int?)
        val region: String
        val mode: String
        val online: Boolean
        fun hint(text: String)
        fun refresh()
    }

    fun diagnose() {
        if (host.tools.checking) return
        scope.launch {
            val checks = mutableListOf<Check>()
            fun show(running: String?) {
                host.update { it.copy(checks = checks.toList() + listOfNotNull(running?.let { name -> Check(name, Outcome.Running) }), checking = running != null, advice = null) }
            }
            show("Network")
            val reached = host.reach()
            checks += reached?.let(Diagnosis::network) ?: Check("Network", Outcome.Warn, "Could not check")
            Diagnosis.clock(reached?.skew)?.let { checks += it }
            show(Conflicts.TITLE)
            checks += withContext(Dispatchers.IO) { Conflicts.check(Conflicts.adapters(), SystemProxy.read(), host.proxied) }
            show("Key")
            checks += when {
                !host.bound -> Check("Key", Outcome.Fail, "No key yet")
                !host.sourceReady -> Check("Key", Outcome.Fail, "The console has not sent nodes yet")
                else -> Check("Key", Outcome.Pass, "${host.nodeCount} nodes")
            }
            if (host.throughAdapter) {
                show("Service")
                checks += when {
                    host.serviceConnected -> Check("Service", Outcome.Pass, "Connected through it")
                    !Places.windows -> Check("Service", Outcome.Warn, "Only on Windows")
                    else -> {
                        val version = withContext(Dispatchers.IO) { ServiceLink.open(Places.service)?.use { it.hello() } }
                        if (version != null) Check("Service", Outcome.Pass, "Version $version") else Check("Service", Outcome.Fail, "Not answering")
                    }
                }
            }
            show("Node")
            val api = host.api
            val tag = host.inUse()
            checks += if (api == null || tag == null) {
                Check("Node", Outcome.Warn, "Nothing to test through yet")
            } else {
                val measured = withContext(Dispatchers.IO) { api.delay(tag) }
                host.record(tag, measured)
                Diagnosis.node(host.nameOf(tag), measured)
            }
            show("DNS")
            checks += Diagnosis.names(Diagnosis.namesFor(host.region))
            show("Sites")
            checks += Diagnosis.sites(host.proxied, Diagnosis.sitesFor(host.region))
            show("Exit")
            val online = host.online
            val address = withContext(Dispatchers.IO) { ExitTrace.detect(host.proxied) }
            checks += when {
                address == null -> Check("Exit", Outcome.Warn, "Unknown")
                online -> Check("Exit", Outcome.Pass, address)
                else -> Check("Exit", Outcome.Warn, "$address, not connected")
            }
            host.update { it.copy(checks = checks.toList(), checking = false, advice = Diagnosis.advice(checks)) }
            host.refresh()
        }
    }

    /** The last diagnosis as plain text, with the version, mode and way, for whoever helps. */
    fun report(): String = Diagnosis.report(
        host.tools.checks, host.tools.advice,
        listOf(
            "Version" to (System.getProperty("jpackage.app-version") ?: "development"),
            "Windows" to System.getProperty("os.version").orEmpty() + " " + System.getProperty("os.arch").orEmpty(),
            "Mode" to host.mode,
            "Way" to if (host.proxied != null) "System proxy" else "Virtual adapter",
            "Connected" to host.online.toString(),
        ) + listOfNotNull(
            // A stop in the last week, in a few words: what and where, never its message.
            Crashes.last()?.takeIf { System.currentTimeMillis() - it.at < 7 * 86_400_000L }?.let { stop ->
                "Last stop" to "${java.time.Instant.ofEpochMilli(stop.at).toString().substringBefore('.').replace('T', ' ')} UTC, ${stop.what}"
            },
        ),
    )

    /** Hands the report to [copy] and says so. */
    fun copyReport(copy: (String) -> Unit) {
        if (host.tools.checks.isEmpty()) return
        copy(report())
        host.hint("Diagnosis copied")
    }

    fun clear() {
        if (!host.tools.checking) host.update { it.copy(checks = emptyList(), advice = null) }
    }
}
