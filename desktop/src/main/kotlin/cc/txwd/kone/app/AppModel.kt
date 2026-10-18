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
import cc.txwd.kone.core.LocalCore
import cc.txwd.kone.core.Probe
import cc.txwd.kone.core.RuleSets
import cc.txwd.kone.core.ServiceLink
import cc.txwd.kone.core.SourceNode
import cc.txwd.kone.node.Countries
import cc.txwd.kone.ui.Connection
import cc.txwd.kone.ui.DashboardState
import cc.txwd.kone.ui.KeyState
import cc.txwd.kone.ui.Node
import cc.txwd.kone.ui.ToolsState
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
class AppModel(private val scope: CoroutineScope, private val preferences: Preferences) {
    private val account = Account(BindingStore(Places.data, Vault.forThisComputer()), Places.deviceName, accepts = ::accepts)
    private val rules: String by lazy { Places.rules?.path?.replace('\\', '/') ?: RuleSets.ensure(File(Places.data, "rules")) }
    private val probe: Probe? = Places.core?.let(::Probe)

    /** The core KOne runs itself, as the system proxy, when the service cannot make the adapter. */
    private val local: LocalCore? = Places.core?.let { core -> LocalCore(core, ::log) }

    /** How much the core has carried, today and this month (Tools → Usage). */
    private val usage = Usage(File(Places.data, "usage.properties"))

    var screen by mutableStateOf(if (account.binding != null) Screen.Dashboard else Screen.Key)
        private set
    var key by mutableStateOf(KeyState())
        private set
    var dashboard by mutableStateOf(DashboardState(emptyList(), emptyList(), "", null))
        private set

    /** The core's last lines, for Logs. */
    val logs = mutableStateListOf<String>()

    /** Tools: the mode, the programs sent straight out, and what the core carries while connected. */
    var tools by mutableStateOf(ToolsState(
            preferences.mode, preferences.bypass, way = preferences.way, today = usage.today(), month = usage.month(),
            direct = preferences.direct, proxied = preferences.proxied,
        ))
        private set
    private var watching: Job? = null

    private var nodes: List<SourceNode> = emptyList()
    private val delays = HashMap<String, Int?>()
    private var tab = ""
    private var connection = Connection.Idle
    private var exit: String? = null
    private var autoNow: String? = null
    private var rates = 0L to 0L
    private var seconds = 0L
    private var notice: String? = null

    private var service: ServiceLink? = null
    private var clash: ClashApi? = null
    private var probeApi: CoreApi? = null
    private var following: Job? = null

    /** Whether the person wants to be connected: on from Connect until Close, quitting, or the key taken back. */
    private var wanted = false

    /** Tries since the connection last held; [Reconnection] says when to give up. */
    private var attempts = 0

    /** Whether the connection has held since Connect; only then is a drop worth trying again. */
    private var held = false

    /** Whether the person was told it dropped, and so should hear it came back. */
    private var told = false

    /** One line for a Windows notification: the connection dropped, or came back. Main shows it by the clock. */
    var onNotice: (String) -> Unit = {}

    /** Set to start the core afresh (the programs sent straight out changed, or the line died in sleep). */
    private var restarting = false

    /** The port Windows' proxy points at while connected through it, without TUN. */
    private var proxied: Int? = null

    /** The adapter could not be made this time KOne runs: connections go through the system proxy until it is chosen again. */
    private var noAdapter = false

    init {
        // A KOne that crashed while Windows' proxy pointed at it puts the person's own back now;
        // one that is ended with Windows puts it back on its way out.
        SystemProxy.restore()
        Runtime.getRuntime().addShutdownHook(Thread { SystemProxy.restore(); usage.save() })
        loadNodes()
        if (account.binding != null) {
            // Connected when Windows shut down or restarted (not ended by Close or Quit): connect again.
            if (preferences.connected && account.binding?.source != null) {
                wanted = true
                held = true
                connect()
            } else {
                startProbe()
            }
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
            Connection.Idle -> {
                wanted = true
                preferences.connected = true
                attempts = 0
                held = false
                told = false
                connect()
            }
            // Close, or giving up on a reconnection still waiting for its turn.
            Connection.Online, Connection.Connecting -> {
                wanted = false
                preferences.connected = false
                service?.let { link -> scope.launch(Dispatchers.IO) { link.disconnect() } }
                if (service == null && proxied == null && attempts > 0) ended("stopped")
            }
        }
    }

    /** On quitting: the connection ends with the App, as the person expects, and is not picked up next time. */
    fun close() {
        wanted = false
        preferences.connected = false
        service?.disconnect()
        probe?.stop()
        local?.stop()
        SystemProxy.restore()
        usage.save()
    }

    private fun connect() {
        val binding = account.binding ?: return
        val source = binding.source ?: return
        connection = Connection.Connecting
        notice = null
        refresh()
        probeApi = null
        if (preferences.way == WAY_PROXY || noAdapter) {
            connectByProxy()
            return
        }
        scope.launch {
            val link = withContext(Dispatchers.IO) {
                probe?.stop()
                // Installed, the service must be the one installed beside the App; only a
                // development run, with no installation, talks to whatever serves the pipe.
                if (Places.launcher != null && Places.service == null) null else ServiceLink.open(Places.service)
            }
            val refused = if (link == null) "no service" else withContext(Dispatchers.IO) {
                runCatching { link.connect(source, binding.selected, preferences.mode, preferences.bypass, preferences.direct, preferences.proxied) }.getOrElse { "failed" }
            }
            if (link == null || refused != null) {
                link?.close()
                val end = refused ?: "no service"
                if (wanted && withoutAdapter(end)) connectByProxy(fellBack = true) else ended(end)
                return@launch
            }
            service = link
            // Close pressed while it was starting.
            if (!wanted) withContext(Dispatchers.IO) { link.disconnect() }
            // The service is asked once a second how the core is; that also keeps the session,
            // and with it the connection, alive exactly as long as the App.
            var since = 0L
            var end = "exited"
            var ran = false
            restarting = false
            var asked = System.currentTimeMillis()
            while (isActive) {
                val status = withContext(Dispatchers.IO) { runCatching { link.status(since) }.getOrNull() } ?: break
                status.logs.forEach(::log)
                since = status.next
                if (status.state == "running" && clash == null && status.api != null) {
                    ran = true
                    follow(ClashApi(status.api))
                    if (told) onNotice("Reconnected")
                    attempts = 0
                    held = true
                    told = false
                }
                if (status.state == "stopped") {
                    end = if (restarting) "restart" else status.end
                    break
                }
                delay(1000)
                // A second that took far longer: the computer slept. If the line no longer
                // answers, the core starts afresh rather than leave the person waiting.
                val now = System.currentTimeMillis()
                val slept = now - asked > SLEPT_MILLIS
                asked = now
                val running = clash
                if (slept && running != null && !restarting) {
                    val node = account.binding?.selected ?: nodes.firstOrNull()?.tag
                    val answers = node != null && withContext(Dispatchers.IO) { running.delay(node) != null || running.delay(node) != null }
                    if (!answers) {
                        restarting = true
                        withContext(Dispatchers.IO) { link.disconnect() }
                    }
                }
            }
            withContext(Dispatchers.IO) { link.close() }
            // The core never came up: most likely the adapter could not be made (security software,
            // a policy). The system proxy carries what follows it instead.
            if (wanted && !ran && !restarting && withoutAdapter(end)) connectByProxy(fellBack = true) else ended(end)
        }
    }

    /** Whether a connection that never came up ended for want of the adapter, rather than of the key, the configuration or someone else. */
    private fun withoutAdapter(end: String) = end == "no service" || end == "failed" || end.startsWith("exited")

    /**
     * Connects without TUN: KOne runs the core itself with a port on 127.0.0.1, and points Windows'
     * proxy at it while connected. Browsers and most programs follow it; the Claude block is the
     * same, in the same rules. [fellBack]: the adapter was tried first and could not be made.
     */
    private fun connectByProxy(fellBack: Boolean = false) {
        val core = local
        val source = account.binding?.source
        if (core == null || source == null) {
            ended("no service")
            return
        }
        if (fellBack) noAdapter = true
        connection = Connection.Connecting
        refresh()
        scope.launch {
            withContext(Dispatchers.IO) { probe?.stop() }
            probeApi = null
            val api = CoreApi(freePort(), token())
            val port = freePort()
            val config = runCatching {
                KoneConfig.build(source, rules, api, account.binding?.selected, tun = false, mode = preferences.mode, bypass = preferences.bypass, mixed = port,
                    direct = preferences.direct, proxied = preferences.proxied,
                ).config.toString()
            }.getOrNull()
            if (config == null || !withContext(Dispatchers.IO) { core.start(config) }) {
                ended("failed")
                return@launch
            }
            val running = ClashApi(api)
            val ready = withContext(Dispatchers.IO) {
                repeat(50) { if (running.answers()) return@withContext true; Thread.sleep(200) }
                false
            }
            if (!ready || !wanted || !withContext(Dispatchers.IO) { SystemProxy.point(port) }) {
                withContext(Dispatchers.IO) { core.stop(); SystemProxy.restore() }
                ended(if (!wanted) "stopped" else if (!ready) core.end else "failed")
                return@launch
            }
            proxied = port
            if (fellBack) {
                log("The adapter is unavailable; switching to the system proxy")
                onNotice("The adapter is unavailable; using the system proxy")
            }
            if (told) onNotice("Reconnected")
            follow(running)
            attempts = 0
            held = true
            told = false
            restarting = false
            // Once a second: still wanted, still running, still the proxy Windows uses.
            var end: String
            while (true) {
                delay(1000)
                if (!wanted) { end = "stopped"; break }
                if (restarting) { end = "restart"; break }
                if (!core.running) { end = core.end; break }
                // Something else took Windows' proxy over (another VPN, the person): KOne lets go.
                if (!withContext(Dispatchers.IO) { SystemProxy.pointsAt(port) }) { wanted = false; end = "stopped"; break }
            }
            withContext(Dispatchers.IO) {
                core.stop()
                SystemProxy.restore()
            }
            proxied = null
            ended(end)
        }
    }

    private fun ended(end: String) {
        following?.cancel()
        scope.launch(Dispatchers.IO) { usage.save() }
        service = null
        clash = null
        exit = null
        autoNow = null
        rates = 0L to 0L
        seconds = 0
        // A connection that held, dropped without being asked to, not for good: connect again,
        // quietly, a little later each time. One that never came up says why at once.
        val wait = if (wanted) Reconnection.after(end, held, attempts) else null
        if (wait != null) {
            attempts++
            if (attempts == 1 && end != "restart") {
                onNotice("Connection interrupted; reconnecting")
                told = true
            }
            connection = Connection.Connecting
            notice = null
            refresh()
            scope.launch {
                delay(wait)
                if (wanted && connection == Connection.Connecting && service == null) connect()
            }
            return
        }
        if (wanted && end != "stopped") onNotice(Ends.message(end) ?: "Disconnected")
        if (wanted) preferences.connected = false
        wanted = false
        attempts = 0
        connection = Connection.Idle
        notice = Ends.message(end)
        refresh()
        startProbe()
    }

    // Tools

    /** Rule, Global or Direct: kept, and applied at once to a running core. */
    fun pickMode(mode: String) {
        if (mode !in KoneConfig.MODES) return
        preferences.mode = mode
        tools = tools.copy(mode = mode)
        clash?.let { api -> scope.launch(Dispatchers.IO) { api.mode(mode) } }
    }

    /** Sends [program] straight out, or back through KOne; a running connection starts afresh to take it. */
    fun setBypass(program: String, straight: Boolean) {
        val now = preferences.bypass
        val next = if (straight) now + program else now.filterNot { it.equals(program, ignoreCase = true) }
        preferences.bypass = next
        if (preferences.bypass == now) return
        tools = tools.copy(bypass = preferences.bypass)
        restart()
    }

    /**
     * The site in what the person typed, straight out ([direct]) or through the node in Rule; taken
     * from the other list if it was there, and a running connection starts afresh to take it.
     * False when it is not a site.
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
        tools = tools.copy(direct = preferences.direct, proxied = preferences.proxied)
        restart()
        return true
    }

    fun removeSite(site: String) {
        preferences.direct -= site
        preferences.proxied -= site
        tools = tools.copy(direct = preferences.direct, proxied = preferences.proxied)
        restart()
    }

    /** While Tools is open and KOne connected, what the core carries, once a second. */
    fun watchConnections(on: Boolean) {
        watching?.cancel()
        watching = null
        if (!on) return
        watching = scope.launch {
            while (isActive) {
                val api = clash
                val carried = if (api == null) null else withContext(Dispatchers.IO) { api.connections() }
                tools = tools.copy(carried = carried)
                delay(1000)
            }
        }
    }

    /** Ends one connection, or all of them when [id] is null. */
    fun closeConnection(id: String?) {
        val api = clash ?: return
        scope.launch(Dispatchers.IO) { api.close(id) }
    }

    private fun restart() {
        if (connection != Connection.Online) return
        if (proxied != null) {
            restarting = true
            return
        }
        val link = service ?: return
        restarting = true
        scope.launch(Dispatchers.IO) { link.disconnect() }
    }

    /** Virtual adapter or System proxy: kept, and a running connection starts afresh the chosen way. */
    fun pickWay(way: String) {
        if (way != WAY_ADAPTER && way != WAY_PROXY) return
        preferences.way = way
        noAdapter = false
        tools = tools.copy(way = way)
        restart()
    }

    /** Once the core answers: follow the traffic, the clock, the exit and the delays. */
    private fun follow(api: ClashApi) {
        clash = api
        connection = Connection.Online
        seconds = 0
        refresh()
        following = scope.launch {
            launch(Dispatchers.IO) {
                api.traffic({ clash === api }) { down, up ->
                    usage.add(down, up)
                    scope.launch {
                        rates = down to up
                        tools = tools.copy(today = usage.today(), month = usage.month())
                        refresh()
                    }
                }
            }
            launch { while (isActive && clash === api) { delay(1000); seconds++; refresh() } }
            launch(Dispatchers.IO) { detectExit() }
            launch { testDelays(api) }
            // Which node Automatic is on, while it is the choice.
            launch {
                while (isActive && clash === api) {
                    if (account.binding?.selected == KoneConfig.AUTO_TAG) {
                        val now = withContext(Dispatchers.IO) { api.now(KoneConfig.AUTO_TAG) }
                        if (now != autoNow) {
                            autoNow = now
                            refresh()
                        }
                    }
                    delay(5000)
                }
            }
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
                wanted = false
                preferences.connected = false
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
        if (tab !in categories()) {
            tab = if (binding?.selected == KoneConfig.AUTO_TAG && auto) AUTO_NAME else categoryOf(nodes.firstOrNull { it.tag == binding?.selected } ?: nodes.firstOrNull())
        }
        refresh()
    }

    private fun categoryOf(node: SourceNode?): String = node?.country?.let { Countries.byCode(it)?.zh } ?: "Node"

    /** Automatic first when there is a choice to make, then the countries. */
    private fun categories(): List<String> = (if (auto) listOf(AUTO_NAME) else emptyList()) + nodes.map(::categoryOf).distinct()

    /** Whether there is a Automatic: two nodes or more, as the template has it. */
    private val auto get() = nodes.size >= 2

    /**
     * The node Automatic is on: while connected, the core's own pick; otherwise the one that answered
     * fastest in the last test, which is the one the core would pick.
     */
    private fun autoPick(): SourceNode? {
        autoNow?.let { now -> nodes.firstOrNull { it.tag == now }?.let { return it } }
        return nodes.filter { delays[it.tag] != null }.minByOrNull { delays[it.tag]!! }
    }

    /** Automatic as a row of its own: the node it is on, with that node's flag and delay. */
    private fun autoNode(): Node {
        val pick = autoPick()
        return Node(KoneConfig.AUTO_TAG, if (pick != null) "$AUTO_NAME · ${pick.name}" else AUTO_NAME, pick?.country, AUTO_NAME, pick?.let { delays[it.tag] }, tested = pick != null)
    }

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
        val selected = account.binding?.selected?.takeIf { tag -> (tag == KoneConfig.AUTO_TAG && auto) || nodes.any { it.tag == tag } } ?: nodes.firstOrNull()?.tag
        dashboard = DashboardState(
            categories = categories(),
            nodes = (if (auto) listOf(autoNode()) else emptyList()) +
                nodes.map { Node(it.tag, it.name, it.country, categoryOf(it), delays[it.tag], tested = it.tag in delays) },
            tab = tab,
            selected = selected,
            connection = connection,
            exit = exit,
            downKbps = rates.first / 1024.0,
            upKbps = rates.second / 1024.0,
            seconds = seconds,
            notice = notice,
            expiry = Expiry.hint(account.binding?.expiresAt),
            proxied = proxied != null,
        )
    }

    companion object {
        private const val SLEPT_MILLIS = 20_000L
        private const val AUTO_NAME = "Automatic"

        /** How KOne connects: through its own adapter (TUN), or through Windows' proxy. */
        const val WAY_ADAPTER = "Adapter"
        const val WAY_PROXY = "Proxy"
    }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    private fun token(): String = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
}

/** When a connection that ended is tried again, and after how long. */
internal object Reconnection {
    private val waits = longArrayOf(1_000, 3_000, 10_000, 30_000, 60_000)

    /**
     * The wait before trying again after the [attempts] made so far, or null to give up: only a
     * connection that [held], ended for a reason that may pass, five times at most.
     */
    fun after(end: String, held: Boolean, attempts: Int): Long? =
        if (held && Ends.passing(end) && attempts < waits.size) waits[attempts] else null
}
