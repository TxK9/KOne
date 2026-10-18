package cc.txwd.kone.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.txwd.kone.console.ClaimLink
import cc.txwd.kone.console.ConsoleException
import cc.txwd.kone.core.ClashApi
import cc.txwd.kone.core.CoreApi
import cc.txwd.kone.core.Ends
import cc.txwd.kone.core.ExitTrace
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.Probe
import cc.txwd.kone.core.RuleSets
import cc.txwd.kone.core.ServiceLink
import cc.txwd.kone.core.SourceNode
import cc.txwd.kone.node.Countries
import cc.txwd.kone.ui.Connection
import cc.txwd.kone.ui.DashboardState
import cc.txwd.kone.ui.KeyState
import cc.txwd.kone.ui.Node
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.SecureRandom
import java.util.HexFormat
import java.util.concurrent.TimeUnit

enum class Screen { Key, Dashboard }

/**
 * The App's state and what it does: claim with a key, keep the nodes in step with the console,
 * test their delays, connect through the KOne service and follow the connection. The screens only
 * draw what is here and call back into it. Runs its work on [scope]; state changes on the UI thread.
 */
class AppModel(private val scope: CoroutineScope) {
    private val account = Account(BindingStore(Places.data, Vault.forThisComputer()), Places.deviceName, accepts = ::accepts)
    private val rules: String by lazy { Places.rules?.path?.replace('\\', '/') ?: RuleSets.ensure(File(Places.data, "rules")) }
    private val probe: Probe? = Places.core?.let(::Probe)

    var screen by mutableStateOf(if (account.binding != null) Screen.Dashboard else Screen.Key)
        private set
    var key by mutableStateOf(KeyState())
        private set
    var dashboard by mutableStateOf(DashboardState(emptyList(), emptyList(), "", null))
        private set

    /** The core's last lines, for Logs. */
    val logs = mutableStateListOf<String>()

    private var nodes: List<SourceNode> = emptyList()
    private val delays = HashMap<String, Int?>()
    private var tab = ""
    private var connection = Connection.Idle
    private var exit: String? = null
    private var rates = 0L to 0L
    private var seconds = 0L
    private var notice: String? = null

    private var service: ServiceLink? = null
    private var clash: ClashApi? = null
    private var probeApi: CoreApi? = null
    private var following: Job? = null

    init {
        loadNodes()
        if (account.binding != null) {
            startProbe()
            scope.launch { syncNow() }
        }
        scope.launch {
            // Every four hours, as the phone does, the nodes are brought in step with the console.
            while (isActive) {
                delay(TimeUnit.HOURS.toMillis(4))
                if (account.binding != null) syncNow()
            }
        }
    }

    // Enter access key

    fun typeKey(text: String) {
        key = key.copy(key = text, error = null)
    }

    /** A key that arrived as a kone://claim link (clicked, or pasted): put it in the field. */
    fun offerKey(text: String) {
        if (screen == Screen.Key && !key.busy) key = KeyState(text.trim())
    }

    fun claim() {
        val link = ClaimLink.read(key.key, Places.console)
        if (link == null) {
            key = key.copy(error = "Invalid access key")
            return
        }
        key = key.copy(busy = true, error = null)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { account.claim(link) } }
            result.onSuccess { sync ->
                key = KeyState()
                screen = Screen.Dashboard
                afterSync(sync)
            }.onFailure { failure ->
                key = key.copy(busy = false, error = (failure as? ConsoleException)?.shown ?: "Activation failed")
            }
        }
    }

    // Dashboard

    fun pickTab(name: String) {
        tab = name
        refresh()
    }

    fun pickNode(tag: String) {
        account.select(tag)
        val running = clash
        if (running != null) {
            scope.launch(Dispatchers.IO) {
                if (running.select(tag)) detectExit()
            }
        }
        refresh()
    }

    fun power() {
        when (connection) {
            Connection.Idle -> connect()
            Connection.Online -> service?.let { link -> scope.launch(Dispatchers.IO) { link.disconnect() } }
            Connection.Connecting -> Unit
        }
    }

    /** On quitting: the connection ends with the App, as the person expects. */
    fun close() {
        service?.disconnect()
        probe?.stop()
    }

    private fun connect() {
        val binding = account.binding ?: return
        val source = binding.source ?: return
        connection = Connection.Connecting
        notice = null
        refresh()
        probeApi = null
        scope.launch {
            val link = withContext(Dispatchers.IO) {
                probe?.stop()
                // Installed, the service must be the one installed beside the App; only a
                // development run, with no installation, talks to whatever serves the pipe.
                if (Places.launcher != null && Places.service == null) null else ServiceLink.open(Places.service)
            }
            val refused = if (link == null) "no service" else withContext(Dispatchers.IO) { runCatching { link.connect(source, binding.selected) }.getOrElse { "failed" } }
            if (link == null || refused != null) {
                link?.close()
                ended(refused ?: "no service")
                return@launch
            }
            service = link
            // The service is asked once a second how the core is; that also keeps the session,
            // and with it the connection, alive exactly as long as the App.
            var since = 0L
            var end = "exited"
            while (isActive) {
                val status = withContext(Dispatchers.IO) { runCatching { link.status(since) }.getOrNull() } ?: break
                status.logs.forEach(::log)
                since = status.next
                if (status.state == "running" && clash == null && status.api != null) follow(ClashApi(status.api))
                if (status.state == "stopped") {
                    end = status.end
                    break
                }
                delay(1000)
            }
            withContext(Dispatchers.IO) { link.close() }
            ended(end)
        }
    }

    private fun ended(end: String) {
        following?.cancel()
        service = null
        clash = null
        exit = null
        rates = 0L to 0L
        seconds = 0
        connection = Connection.Idle
        notice = Ends.message(end)
        refresh()
        startProbe()
    }

    /** Once the core answers: follow the traffic, the clock, the exit and the delays. */
    private fun follow(api: ClashApi) {
        clash = api
        connection = Connection.Online
        seconds = 0
        refresh()
        following = scope.launch {
            launch(Dispatchers.IO) {
                api.traffic({ clash === api }) { down, up -> scope.launch { rates = down to up; refresh() } }
            }
            launch { while (isActive && clash === api) { delay(1000); seconds++; refresh() } }
            launch(Dispatchers.IO) { detectExit() }
            launch { testDelays(api) }
        }
    }

    private suspend fun detectExit() {
        repeat(3) {
            val found = ExitTrace.detect()
            if (found != null) {
                withContext(Dispatchers.Main) { exit = found; refresh() }
                return
            }
            delay(2000)
        }
    }

    /** Tests every node through [api] now and then each minute, while it is the one in use. */
    private suspend fun testDelays(api: ClashApi) {
        while (true) {
            for (node in nodes) {
                val measured = withContext(Dispatchers.IO) { api.delay(node.tag) }
                if (clash !== api) return
                delays[node.tag] = measured
                refresh()
            }
            delay(60_000)
        }
    }

    /** While not connected, the core without TUN carries the nodes so their delays can be tested. */
    private fun startProbe() {
        val probe = probe ?: return
        val source = account.binding?.source ?: return
        val api = CoreApi(freePort(), token())
        val config = runCatching { KoneConfig.build(source, rules, api, tun = false).config.toString() }.getOrNull() ?: return
        scope.launch {
            val started = withContext(Dispatchers.IO) { probe.start(config) }
            if (!started) return@launch
            probeApi = api
            val clash = ClashApi(api)
            val ready = withContext(Dispatchers.IO) {
                repeat(50) { if (clash.answers()) return@withContext true; Thread.sleep(200) }
                false
            }
            while (ready && isActive && probeApi === api && connection == Connection.Idle) {
                for (node in nodes) {
                    val measured = withContext(Dispatchers.IO) { clash.delay(node.tag) }
                    if (probeApi !== api) return@launch
                    delays[node.tag] = measured
                    refresh()
                }
                delay(60_000)
            }
        }
    }

    private suspend fun syncNow() {
        afterSync(withContext(Dispatchers.IO) { account.sync() })
    }

    private fun afterSync(sync: Sync) {
        when (sync) {
            Sync.Revoked -> {
                service?.let { link -> scope.launch(Dispatchers.IO) { link.disconnect() } }
                probe?.stop()
                probeApi = null
                nodes = emptyList()
                screen = Screen.Key
                key = KeyState(error = sync.message)
            }
            Sync.Updated -> {
                loadNodes()
                if (connection == Connection.Idle) startProbe()
            }
            Sync.Unchanged -> Unit
            // Paused, nothing assigned yet, offline: worth saying only while there are no nodes to use.
            else -> if (account.binding?.source == null) notice = sync.message
        }
        refresh()
    }

    /** Whether the core takes a source the console sent: built into the template and checked by sing-box itself. */
    private fun accepts(source: JsonObject): Boolean {
        val config = runCatching { KoneConfig.build(source, rules, CoreApi(9090, "check")).config.toString() }.getOrNull() ?: return false
        val core = Places.core ?: return true
        val process = ProcessBuilder(core.path, "check", "-c", "stdin").redirectErrorStream(true).start()
        process.outputStream.use { it.write(config.toByteArray()) }
        process.inputStream.readBytes()
        return process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0
    }

    private fun loadNodes() {
        val binding = account.binding
        val source = binding?.source
        nodes = source?.let { runCatching { KoneConfig.build(it, rules, CoreApi(9090, "list")).nodes }.getOrNull() }.orEmpty()
        if (tab !in categories()) tab = categoryOf(nodes.firstOrNull { it.tag == binding?.selected } ?: nodes.firstOrNull())
        refresh()
    }

    private fun categoryOf(node: SourceNode?): String = node?.country?.let { Countries.byCode(it)?.zh } ?: "Node"

    private fun categories(): List<String> = nodes.map(::categoryOf).distinct()

    private fun say(message: String?) {
        notice = message
        refresh()
    }

    private fun log(line: String) {
        scope.launch {
            logs += line
            if (logs.size > 500) logs.removeRange(0, logs.size - 500)
        }
    }

    private fun refresh() {
        val selected = account.binding?.selected?.takeIf { tag -> nodes.any { it.tag == tag } } ?: nodes.firstOrNull()?.tag
        dashboard = DashboardState(
            categories = categories(),
            nodes = nodes.map { Node(it.tag, it.name, it.country, categoryOf(it), delays[it.tag], tested = it.tag in delays) },
            tab = tab,
            selected = selected,
            connection = connection,
            exit = exit,
            downKbps = rates.first / 1024.0,
            upKbps = rates.second / 1024.0,
            seconds = seconds,
            notice = notice,
        )
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun token(): String = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
}
