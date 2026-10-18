package io.nekohasekai.sfa.bg

import android.os.Handler
import android.os.Looper
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.compose.model.Group
import io.nekohasekai.sfa.compose.screen.dashboard.LatencySample
import io.nekohasekai.sfa.compose.screen.dashboard.NodeHealthRules
import io.nekohasekai.sfa.compose.screen.dashboard.NodeLatency
import io.nekohasekai.sfa.compose.screen.dashboard.NodeReading
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether the node in use is working, for the notification and the quick-settings tile. It is the
 * core's URL test through the proxy, judged by the same rules as the dashboard's dot, so the two
 * agree. It only runs while the screen is on, since nobody can look otherwise. A test is one small
 * HTTPS request through the node: when the screen comes on (unless a result is under a minute
 * old), then once a minute on Wi-Fi and once every three minutes on mobile data. Results of the
 * dashboard's own tests arrive here too, so while it is open this asks for none of its own.
 */
internal object NodeWatch : CommandClient.Handler {
    private const val WIFI_GAP_S = 60L
    private const val METERED_GAP_S = 180L
    private const val WAKE_GAP_S = 60L
    private const val RETRY_GAP_S = 20L
    private const val ANSWER_S = 10L

    /** The verdict for the node in use; null until it has been tested, and when stopped. */
    @Volatile
    var reading: NodeReading? = null
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    // Everything below is touched on the main thread only.
    private var client: CommandClient? = null
    private var loop: Job? = null
    private var groupTag: String? = null
    private var nodeTag: String? = null
    private var history = emptyList<LatencySample>()
    private var lastResult = 0L
    private var askedAt = 0L
    private var waking = false

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    /** Main thread. The service is up and the screen is on: listen for results and keep them fresh. */
    @OptIn(DelicateCoroutinesApi::class)
    fun resume() {
        if (loop != null) return
        waking = true
        client = CommandClient(GlobalScope, CommandClient.ConnectionType.Groups, this, localOnly = true).also { it.connect() }
        loop = GlobalScope.launch(Dispatchers.Main) {
            while (true) {
                check()
                delay(5_000)
            }
        }
    }

    /** Main thread. The screen went off or the service is stopping: no listening, no testing. */
    fun pause() {
        loop?.cancel()
        loop = null
        client?.disconnect()
        client = null
        askedAt = 0L
    }

    /** Main thread. The service stopped: nothing to watch until it starts again. */
    fun stop() {
        pause()
        groupTag = null
        nodeTag = null
        reset()
    }

    /** Main thread. Another node, or none: what was known no longer applies. */
    fun reset() {
        history = emptyList()
        lastResult = 0L
        askedAt = 0L
        publish()
    }

    private suspend fun check() {
        val tag = groupTag ?: return
        val now = System.currentTimeMillis() / 1_000
        if (askedAt > 0L && now - askedAt >= ANSWER_S) {
            askedAt = 0L
            lastResult = now
            record(LatencySample(null, "Proxy unreachable"))
        }
        if (askedAt > 0L) return
        val failing = history.lastOrNull()?.ms == null && history.isNotEmpty()
        val gap = when {
            waking -> WAKE_GAP_S
            failing -> RETRY_GAP_S
            NodeLatency.metered(Application.application) -> METERED_GAP_S
            else -> WIFI_GAP_S
        }
        waking = false
        if (now - lastResult < gap) return
        askedAt = now
        withContext(Dispatchers.IO) { runCatching { Libbox.newStandaloneCommandClient().urlTest(tag) } }
    }

    override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
        val groups = newGroups.map { Group(it) }
        // A paired profile routes through its "proxy" selector; otherwise use the first selector.
        val probe = groups.firstOrNull { it.selectable && it.tag == "proxy" } ?: groups.firstOrNull { it.selectable }
        val inUse = probe?.items?.firstOrNull { it.tag == probe.selected }
        main.post {
            if (probe?.tag != groupTag || inUse?.tag != nodeTag) {
                groupTag = probe?.tag
                nodeTag = inUse?.tag
                reset()
            }
            val time = inUse?.urlTestTime?.let { if (it > 100_000_000_000L) it / 1_000 else it } ?: 0L
            val delay = inUse?.urlTestDelay ?: 0
            if (delay > 0 && time > 0L && (time > lastResult || askedAt > 0L && time >= askedAt - 1)) {
                lastResult = maxOf(lastResult, time)
                askedAt = 0L
                record(LatencySample(delay))
            }
        }
    }

    private fun record(sample: LatencySample) {
        history = (history + sample).takeLast(NodeHealthRules.WINDOW)
        publish()
    }

    private fun publish() {
        reading = NodeHealthRules.judge(history, NodeHealthRules.PROXY_SLOW_MS, NodeHealthRules.PROXY_JITTER_MS)
        listeners.forEach { it() }
    }
}
