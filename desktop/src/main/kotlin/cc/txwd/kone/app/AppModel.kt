package cc.txwd.kone.app

import cc.txwd.kone.i18n.tr


import cc.txwd.kone.shared.Why
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
import cc.txwd.kone.shared.Host
import cc.txwd.kone.shared.NameServer
import cc.txwd.kone.core.KoneConfig
import cc.txwd.kone.core.LocalCore
import cc.txwd.kone.core.Probe
import cc.txwd.kone.core.RuleSets
import cc.txwd.kone.core.ServiceLink
import cc.txwd.kone.core.SourceNode
import cc.txwd.kone.core.Speed
import cc.txwd.kone.core.SpeedTest
import cc.txwd.kone.core.UserRule
import cc.txwd.kone.core.UserRules
import cc.txwd.kone.shared.Countries
import cc.txwd.kone.node.NodeDraft
import cc.txwd.kone.ui.Connection
import cc.txwd.kone.ui.DashboardState
import cc.txwd.kone.ui.KeyState
import cc.txwd.kone.ui.Node
import cc.txwd.kone.ui.NodeSection
import cc.txwd.kone.ui.NodesState
import cc.txwd.kone.ui.SourceKind
import cc.txwd.kone.ui.ToolsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /** The nodes the person imported (⋮), joined to the console's. */
    private val imports = Imports(File(Places.data, "imported.bin"), Vault.forThisComputer())
    private var importedTags: Set<String> = emptySet()

    /** The imported nodes' tags in the order they are kept, so a listed node finds its place in [imports]. */
    private var importedOrder: List<String> = emptyList()

    private val ownNodes = OwnNodes(scope, imports, object : OwnNodes.Host {
        override val managed get() = account.binding?.source
        override val selected get() = account.binding?.selected
        override val imported get() = importedOrder
        override fun own(): OwnNodes.Own {
            val merged = merged() ?: return OwnNodes.Own(emptyList(), emptySet())
            val outbounds = (merged.source["outbounds"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            return OwnNodes.Own(outbounds, merged.groups.flatten().toSet())
        }
        override fun nameOf(tag: String) = this@AppModel.nameOf(tag)
        override fun inUse() = this@AppModel.inUse()
        override fun accepts(source: JsonObject) = this@AppModel.accepts(source)
        override fun accepted(managed: JsonObject, nodes: List<JsonObject>) = acceptedNodes(managed, nodes)
        override fun nodesChanged(gone: String?) {
            gone?.let(delays::remove)
            this@AppModel.nodesChanged()
        }
        override fun restart() = this@AppModel.restart()
        override fun refresh() = this@AppModel.refresh()
        override fun tell(message: String, error: Boolean) = this@AppModel.tell(message, error)
        override fun imported(message: String, good: Boolean) {
            if (good) {
                notice = null
                hint(message)
            } else {
                say(message)
            }
        }
        override fun addSubscription(link: String) = feeds.keep(link)
    })

    /** The last bandwidth test of each node, and the node being tested now (one at a time). */
    private val speeds = HashMap<String, Speed>()
    private var speedTesting: String? = null

    /** The person's subscriptions (Nodes), fetched in the background and joined after the imported nodes. */
    private val subscriptions = Subscriptions(File(Places.data, "subscriptions.bin"), Vault.forThisComputer())

    /** Adding, fetching, editing and removing them: see SubscriptionUpdates. */
    private val feeds: SubscriptionUpdates = SubscriptionUpdates(scope, subscriptions, object : SubscriptionUpdates.Host {
        override val managed get() = account.binding?.source
        override val proxied get() = this@AppModel.proxied
        override val inUse get() = account.binding?.selected?.let { subscribed[it]?.id }
        override fun accepted(managed: JsonObject, nodes: List<JsonObject>) = acceptedNodes(managed, nodes)
        override fun refresh() = this@AppModel.refresh()
        override fun nodesChanged() = this@AppModel.nodesChanged()
        override fun reload() = loadNodes()
        override fun restart() = this@AppModel.restart()
        override fun tell(message: String, error: Boolean) = this@AppModel.tell(message, error)
        override fun notice(text: String) = onNotice(text)
    })

    /** The subscription each listed node came from, by tag. */
    private var subscribed: Map<String, Subscription> = emptyMap()

    /** Subscriptions being fetched now. */

    /** The node tags the running core was started with: choosing one outside them starts it afresh. */
    private var runningTags: Set<String> = emptySet()

    /** Every node's delay is being tested at the person's asking. */
    private var testing = false

    /** Every node's recent tests, which Automatic and failover decide from. */
    private val health = Health()
    private val steer = Steer(health)

    /** While Automatic is chosen: the node KOne set the core on; null while the core's own pick stands. */
    private var drivenPick: String? = null

    /** While a chosen node is not answering (and failover is on): the node standing in for it. */
    private var standIn: String? = null

    /** One line for the Nodes page, and whether it says something went wrong. */
    private var nodesNotice: Pair<String, Boolean>? = null

    /** How much the core has carried, today and this month (Tools → Usage). */
    private val usage = Usage(File(Places.data, "usage.properties"))

    /** How much each node and each program carried, day by day (Tools → Usage → Details). */
    private val traffic = Traffic(File(Places.data, "traffic.properties"))

    /** The category lists the rules name: see ListKeeper. */
    private val keeper: ListKeeper = ListKeeper(scope, object : ListKeeper.Host {
        override val rules get() = preferences.rules
        override val folder get() = this@AppModel.rules
        override val service get() = this@AppModel.service
        override val proxied get() = this@AppModel.proxied
        override val online get() = connection == Connection.Online
        override fun restart() = this@AppModel.restart()
        override fun changed() = ruleBook.show()
    })

    var screen by mutableStateOf(if (account.binding != null) Screen.Dashboard else Screen.Key)
        private set
    var key by mutableStateOf(KeyState())
        private set
    var dashboard by mutableStateOf(DashboardState(emptyList(), emptyList(), "", null))
        private set

    /** Nodes: every source with its nodes, the subscriptions' state, and what just happened. */
    var nodesPage by mutableStateOf(NodesState())
        private set

    /** The core's last lines, for Logs. */
    val logs = mutableStateListOf<String>()

    /** Tools: the mode, the way KOne connects, usage, and what the core carries while connected. */
    var tools by mutableStateOf(ToolsState(preferences.mode, preferences.bypass, way = preferences.way, today = usage.today(), month = usage.month()))
        private set

    /** Rules: the programs and sites lists, the person's own rules and ad blocking, as kept (see RuleBook). */
    private val ruleBook = RuleBook(scope, preferences, keeper, object : RuleBook.Host {
        override fun restart() = this@AppModel.restart()
        override fun bypassChanged() {
            tools = tools.copy(bypass = preferences.bypass)
        }
    })
    val rulesPage get() = ruleBook.page

    private var nodes: List<SourceNode> = emptyList()
    private val delays = HashMap<String, Int?>()
    private var tab = ""
    private var connection = Connection.Idle
    private var exit: String? = null
    private var autoNow: String? = null
    private var rates = 0L to 0L

    /** The last minute of rates, down and up in bytes a second, oldest first. */
    private val trail = ArrayDeque<Pair<Long, Long>>()
    private var seconds = 0L
    private var notice: String? = null
    private var hint: String? = null

    private var service: ServiceLink? = null
    private var clash: ClashApi? = null
    private var probeApi: CoreApi? = null
    private var following: Job? = null

    /** Whether KOne's window is in sight: the nodes are tested often only then (see [Sweeps]). */
    private var inSight = true

    /** When every node was last tested, and a call to test them all again now. */
    private var swept = 0L
    private val sweepNow = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Ends the testing core a while after the window went out of sight, while disconnected. */
    private var resting: Job? = null

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

    /** Subscription warnings already said this session. */

    /** Opens a page in the person's browser (the network's sign-in page). */
    var onBrowse: (String) -> Unit = {}

    /** Set to start the core afresh (the programs sent straight out changed, or the line died in sleep). */
    private var restarting = false

    /** The port Windows' proxy points at while connected through it, without TUN. */
    private var proxied: Int? = null

    /** The adapter could not be made this time KOne runs: connections go through the system proxy until it is chosen again. */
    private var noAdapter = false

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
            key = key.copy(error = tr("Invalid access key"))
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
                key = key.copy(busy = false, error = (failure as? ConsoleException)?.shown ?: tr("Activation failed"))
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
        // The person's choice replaces whatever KOne picked or stood in with.
        drivenPick = null
        standIn = null
        val running = clash
        // A country's Automatic: the core is moved to that country's pick, and steered among its nodes from here.
        regionOf(tag)?.let { region ->
            val pick = regionPick(region)?.tag
            if (running != null && pick != null) {
                if (pick !in runningTags) restart() else scope.launch(Dispatchers.IO) { if (running.select(pick)) detectExit() }
            }
            refresh()
            return
        }
        if (running != null) {
            // A node added since the core started is not in it: the core starts afresh with it.
            if (tag != KoneConfig.AUTO_TAG && tag !in runningTags) {
                restart()
                refresh()
                return
            }
            scope.launch(Dispatchers.IO) {
                if (running.select(tag)) detectExit()
            }
        }
        refresh()
    }

    fun power() {
        when (connection) {
            Connection.Idle -> {
                signIns.connecting()
                // Connecting during a pause ends it.
                resuming?.cancel()
                pausedUntil = null
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

    /** Paused by the person: when KOne comes back on, and the wait for it. */
    private var pausedUntil: Long? = null
    private var resuming: Job? = null

    /**
     * Steps aside for [minutes] (a bank's app that refuses VPNs, a sign-in elsewhere) and connects
     * again by itself; Connect brings it back sooner.
     */
    fun pause(minutes: Int = 15) {
        if (connection == Connection.Idle) return
        power()
        val until = System.currentTimeMillis() + minutes * 60_000L
        pausedUntil = until
        refresh()
        resuming?.cancel()
        resuming = scope.launch {
            delay(minutes * 60_000L)
            if (pausedUntil != until) return@launch
            pausedUntil = null
            if (connection == Connection.Idle) {
                onNotice(tr("KOne is on again after the pause"))
                power()
            } else {
                refresh()
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
        traffic.save()
    }

    private fun connect() {
        val binding = account.binding ?: return
        val source = source() ?: return
        val running = nodesOf(source)
        runningTags = running.mapTo(HashSet()) { it.tag }
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
                keeper.running = keeper.deliver(link)
                runCatching {
                    link.connect(
                        source, startTag(binding.selected), preferences.mode, preferences.bypass, preferences.direct, preferences.proxied,
                        preferences.rules, preferences.blockAds, preferences.remoteDns, preferences.directDns, preferences.ipv6,
                        preferences.hosts, preferences.unmatchedDirect, preferences.nameServers, preferences.region, preferences.fakeIp,
                        KoneConfig.countries(running),
                    )
                }.getOrElse { "failed" }
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
                appendLogs(status.logs)
                since = status.next
                if (status.state == "running" && clash == null && status.api != null) {
                    ran = true
                    follow(ClashApi(status.api))
                    if (told) onNotice(tr("Reconnected"))
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
                    val node = inUse() ?: nodes.firstOrNull()?.tag
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
        val source = source()
        if (core == null || source == null) {
            ended("no service")
            return
        }
        runningTags = tagsOf(source)
        if (fellBack) noAdapter = true
        connection = Connection.Connecting
        refresh()
        scope.launch {
            withContext(Dispatchers.IO) { probe?.stop() }
            probeApi = null
            val api = CoreApi(freePort(), token())
            val port = freePort()
            val listed = withContext(Dispatchers.IO) { keeper.ready() }
            keeper.running = listed
            val config = runCatching {
                KoneConfig.build(source, rules, api, startTag(account.binding?.selected), tun = false, mode = preferences.mode, bypass = preferences.bypass, mixed = port,
                    direct = preferences.direct, proxied = preferences.proxied, routes = keeper.routesWith(listed), blockAds = preferences.blockAds,
                    remoteDns = preferences.remoteDns, directDns = preferences.directDns, ipv6 = preferences.ipv6, hosts = preferences.hosts,
                    nameServers = preferences.nameServers, unmatchedDirect = preferences.unmatchedDirect, region = preferences.region,
                    fakeIp = preferences.fakeIp,
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
                // Another VPN holding the network is the likeliest reason; said by name when there is one.
                val others = withContext(Dispatchers.IO) { Conflicts.vpns(Conflicts.adapters()) }
                val why = if (others.isEmpty()) "" else " (${others.joinToString()} is on)"
                log(tr("The adapter is unavailable{0}; switching to the system proxy" , why))
                onNotice(tr("The adapter is unavailable{0}; using the system proxy" , why))
            }
            if (told) onNotice(tr("Reconnected"))
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
        scope.launch(Dispatchers.IO) { usage.save(); traffic.save() }
        service = null
        clash = null
        exit = null
        autoNow = null
        drivenPick = null
        standIn = null
        trail.clear()
        rates = 0L to 0L
        seconds = 0
        // A connection that held, dropped without being asked to, not for good: connect again,
        // quietly, a little later each time. One that never came up says why at once.
        val wait = if (wanted) Reconnection.after(end, held, attempts) else null
        if (wait != null) {
            if (attempts == 0) signIns.look()
            attempts++
            if (attempts == 1 && end != "restart") {
                onNotice(tr("Connection interrupted; reconnecting"))
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
        if (wanted && end != "stopped") onNotice(Ends.message(end)?.let { tr(it) } ?: tr("Disconnected"))
        if (wanted) preferences.connected = false
        wanted = false
        attempts = 0
        connection = Connection.Idle
        notice = Ends.message(end)?.let { tr(it) }
        refresh()
        startProbe()
    }

    // The person's own nodes: see OwnNodes.

    fun importText(text: String) = ownNodes.importText(text)

    /** The QR codes in [pictures] imported: nodes, subscriptions (see OwnNodes.importQr); [where] says where none was found. */
    fun importQr(where: String, pictures: suspend () -> List<java.awt.image.BufferedImage>) = ownNodes.importQr(where, pictures)

    fun removeImported(tag: String) = ownNodes.remove(tag)

    // Nodes

    /** A subscription link typed or pasted: kept and fetched at once. False when it is not one KOne fetches (https only). */
    fun addSubscription(text: String): Boolean = feeds.add(text)

    fun updateSubscription(id: String) = feeds.update(id)

    /**
     * The nodes sing-box accepts alongside the managed ones: all at once when they all pass, else
     * halves of halves, so one bad node in hundreds costs a few checks, not hundreds.
     */
    private fun acceptedNodes(managed: JsonObject, nodes: List<JsonObject>): List<JsonObject> {
        fun fits(part: List<JsonObject>) = accepts(Merge.into(managed, listOf(part)).source)
        fun keep(part: List<JsonObject>): List<JsonObject> = when {
            part.isEmpty() || fits(part) -> part
            part.size == 1 -> emptyList()
            else -> keep(part.subList(0, part.size / 2)) + keep(part.subList(part.size / 2, part.size))
        }
        return keep(nodes)
    }

    fun editSubscription(id: String, name: String, url: String, hours: Int, leaveOut: String = ""): Boolean = feeds.edit(id, name, url, hours, leaveOut)

    fun removeSubscription(id: String) = feeds.remove(id)

    fun copyLink(tag: String, copy: (String) -> Unit) = ownNodes.copyLink(tag, copy)

    /**
     * Tests how fast [tag] carries data, through a core of its own, so the live connection is left
     * alone. Uses about 35 MB; one node at a time.
     */
    fun testSpeed(tag: String) {
        val core = Places.core
        if (core == null) {
            tell(tr("Speed tests need KOne installed"), error = true)
            return
        }
        val source = source() ?: return
        if (speedTesting != null || tag == KoneConfig.AUTO_TAG) return
        speedTesting = tag
        refresh()
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val api = CoreApi(freePort(), token())
                val port = freePort()
                // Global, on this node alone: every byte of the test goes through it.
                val config = runCatching { KoneConfig.build(source, rules, api, selected = tag, tun = false, mode = "Global", mixed = port, region = preferences.region).config.toString() }.getOrNull()
                    ?: return@withContext null
                val tester = Probe(core)
                try {
                    if (!tester.start(config)) return@withContext null
                    val clash = ClashApi(api)
                    if ((0 until 50).none { clash.answers().also { up -> if (!up) Thread.sleep(200) } }) return@withContext null
                    SpeedTest().run(port)
                } finally {
                    tester.stop()
                }
            }
            speedTesting = null
            if (result == null || (result.down == null && result.up == null)) tell(tr("{0} did not carry the test" , nameOf(tag)), error = true) else speeds[tag] = result
            refresh()
        }
    }

    fun showQr(tag: String?) = ownNodes.showQr(tag)

    fun addNode() = ownNodes.add()

    fun editNode(tag: String) = ownNodes.edit(tag)

    fun cancelNode() = ownNodes.cancel()

    fun saveNode(draft: NodeDraft) = ownNodes.save(draft)

    fun exportLinks(): String = ownNodes.exportLinks()

    /** Every node's delay, now, several at a time. */
    fun testAll() {
        val api = clash ?: probeApi?.let(::ClashApi) ?: return
        if (testing) return
        testing = true
        refresh()
        scope.launch {
            measure(api) { true }
            testing = false
            refresh()
        }
    }

    /** Tests every node through [api], eight at a time, while [current] says the results still matter. */
    private suspend fun measure(api: ClashApi, current: () -> Boolean) {
        swept = System.currentTimeMillis()
        val gate = Semaphore(8)
        coroutineScope {
            nodes.map { node ->
                async {
                    val measured = gate.withPermit { withContext(Dispatchers.IO) { api.delay(node.tag) } }
                    if (current()) {
                        health.record(node.tag, measured)
                        delays[node.tag] = measured
                        refresh()
                    }
                }
            }.awaitAll()
        }
    }


    /** After the person's nodes changed: the list, and the probe's nodes while not connected. */
    private fun nodesChanged() {
        loadNodes()
        if (connection == Connection.Idle) startProbe()
    }

    /** Says [message] on the Nodes page for a few seconds. */
    private fun tell(message: String, error: Boolean) {
        val said = message to error
        nodesNotice = said
        refresh()
        scope.launch {
            delay(6_000)
            if (nodesNotice === said) {
                nodesNotice = null
                refresh()
            }
        }
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
    fun setBypass(program: String, straight: Boolean) = ruleBook.setBypass(program, straight)

    /** The site in what the person typed, straight out ([direct]) or through the node in Rule; false when it is not a site. */
    fun addSite(text: String, direct: Boolean): Boolean = ruleBook.addSite(text, direct)

    fun removeSite(site: String) = ruleBook.removeSite(site)

    // Diagnosis (Tools)

    /** The node carrying traffic now: a stand-in, KOne's pick for Automatic, the core's, or the person's own. */
    private fun inUse(): String? {
        standIn?.let { return it }
        val chosen = account.binding?.selected ?: return nodes.firstOrNull()?.tag
        if (chosen == KoneConfig.AUTO_TAG) return drivenPick ?: autoNow ?: autoPick()?.tag
        return regionOf(chosen)?.let { regionPick(it)?.tag } ?: chosen
    }

    // Tools → Diagnose: see DiagnosisRun.

    private val diagnosis = DiagnosisRun(scope, object : DiagnosisRun.Host {
        override val tools get() = this@AppModel.tools
        override fun update(change: (ToolsState) -> ToolsState) {
            this@AppModel.tools = change(this@AppModel.tools)
        }
        override suspend fun reach() = signIns.reach()
        override val proxied get() = this@AppModel.proxied
        override val bound get() = account.binding != null
        override val sourceReady get() = account.binding?.source != null
        override val nodeCount get() = nodes.size
        override val throughAdapter get() = preferences.way == WAY_ADAPTER && !noAdapter
        override val serviceConnected get() = service != null
        override val api get() = clash ?: probeApi?.let(::ClashApi)
        override fun inUse() = this@AppModel.inUse()
        override fun nameOf(tag: String) = this@AppModel.nameOf(tag)
        override fun record(tag: String, delay: Int?) {
            health.record(tag, delay)
            delays[tag] = delay
        }
        override val region get() = preferences.region
        override val mode get() = preferences.mode
        override val online get() = connection == Connection.Online
        override fun hint(text: String) = this@AppModel.hint(text)
        override fun refresh() = this@AppModel.refresh()
    })

    fun diagnose() = diagnosis.diagnose()

    fun diagnosisReport(): String = diagnosis.report()

    fun copyReport(copy: (String) -> Unit) = diagnosis.copyReport(copy)

    fun clearChecks() = diagnosis.clear()

    // Sign-in networks (hotels, airports, cafés): see SignInNetworks.

    private val signIns = SignInNetworks(scope, object : SignInNetworks.Host {
        override val adapterOn get() = service != null
        override val idle get() = connection == Connection.Idle
        override val wanted get() = this@AppModel.wanted
        override fun power() = this@AppModel.power()
        override fun refresh() = this@AppModel.refresh()
        override fun log(line: String) = this@AppModel.log(line)
        override fun notify(text: String) = onNotice(text)
        override fun hint(text: String) = this@AppModel.hint(text)
        override fun notice(text: String) {
            notice = text
        }
        override fun browse(page: String) = onBrowse(page)
    })

    fun signIn() = signIns.signIn()

    fun cancelSignIn() = signIns.cancel()

    // DNS lookup and Site check (Tools): see SiteTools.

    private val siteTools = SiteTools(scope, preferences, object : SiteTools.Host {
        override val tools get() = this@AppModel.tools
        override fun update(change: (ToolsState) -> ToolsState) {
            this@AppModel.tools = change(this@AppModel.tools)
        }
        override val console get() = account.binding?.source
        override val merged get() = source()
        override val rules get() = this@AppModel.rules
        override val anyCore get() = clash ?: probeApi?.let(::ClashApi)
        override val live get() = clash?.takeIf { connection == Connection.Online }
        override val adapterOn get() = service != null
        override val proxied get() = this@AppModel.proxied
        override val nodes get() = this@AppModel.nodes
        override fun delay(tag: String) = delays[tag]
        override fun inUse() = this@AppModel.inUse()
        override fun nameOf(tag: String) = this@AppModel.nameOf(tag)
        override fun addSite(site: String, direct: Boolean) = this@AppModel.addSite(site, direct)
        override fun hint(text: String) = this@AppModel.hint(text)
        override fun freePort() = this@AppModel.freePort()
        override fun token() = this@AppModel.token()
        override val core get() = Places.core
    })

    fun lookUp(input: String): Boolean = siteTools.lookUp(input)

    fun checkSite(input: String): Boolean = siteTools.checkSite(input)

    fun keepSite(direct: Boolean) = siteTools.keepSite(direct)

    fun clearSite() = siteTools.clearSite()

    fun clearLookup() = siteTools.clearLookup()

    // Backup (Settings): see Backups.

    private val backups = Backups(scope, preferences, subscriptions, imports, object : Backups.Host {
        override val tools get() = this@AppModel.tools
        override fun update(change: (ToolsState) -> ToolsState) {
            this@AppModel.tools = change(this@AppModel.tools)
        }
        override val managed get() = account.binding?.source
        override fun accepted(managed: JsonObject, nodes: List<JsonObject>) = acceptedNodes(managed, nodes)
        override fun showSettings() {
            ruleBook.show()
            networkSettings.show()
        }
        override fun updateLists() = this@AppModel.updateLists()
        override fun fetch(id: String) = feeds.update(id)
        override fun nodesChanged() = this@AppModel.nodesChanged()
        override fun restart() = this@AppModel.restart()
    })

    /** The person's setup as a backup file's text; never the key or the device's binding. */
    fun backup(): String = backups.write()

    /** Restores a backup into what is here; what happened, in a line, or null when [text] is not a KOne backup. */
    fun restore(text: String): String? = backups.restore(text)

    // Network (Settings)

    /** The DNS servers, hosts, DNS for domains, IPv6 and failover, as kept (see NetworkSettings). */
    private val networkSettings = NetworkSettings(preferences) { restart() }
    val network get() = networkSettings.page

    /** Whether a chosen node that stops answering is stood in for; a stand-in in place goes back to the chosen node. */
    fun setFailover(on: Boolean) {
        networkSettings.setFailover(on)
        val api = clash
        val chosen = account.binding?.selected
        if (!on && standIn != null && api != null && chosen != null) {
            standIn = null
            scope.launch(Dispatchers.IO) { api.select(chosen) }
            refresh()
        }
    }

    fun setRemoteDns(choice: String): Boolean = networkSettings.setRemoteDns(choice)

    fun setDirectDns(choice: String): Boolean = networkSettings.setDirectDns(choice)

    fun addHost(line: String): Boolean = networkSettings.addHost(line)

    fun removeHost(host: Host) = networkSettings.removeHost(host)

    fun addNameServer(line: String): Boolean = networkSettings.addNameServer(line)

    fun removeNameServer(entry: NameServer) = networkSettings.removeNameServer(entry)

    fun setIpv6(on: Boolean) = networkSettings.setIpv6(on)

    fun setFakeIp(on: Boolean) = networkSettings.setFakeIp(on)

    fun setRegion(region: String) = networkSettings.setRegion(region)

    // Rules

    fun setUnmatched(direct: Boolean) = ruleBook.setUnmatched(direct)

    fun setBlockAds(on: Boolean) = ruleBook.setBlockAds(on)

    fun addRule(rule: UserRule): Boolean = ruleBook.add(rule)

    fun importRules(text: String): String = ruleBook.import(text)

    fun importRulesFrom(text: String) = ruleBook.importAndSay(text)

    // Category lists (Rules): see ListKeeper.

    fun updateLists(force: Boolean = false) = keeper.update(force)

    fun removeRule(index: Int) = ruleBook.remove(index)

    fun setRuleAction(index: Int, action: String) = ruleBook.setAction(index, action)

    /** Every rule as Clash rule lines, handed to [copy]. */
    fun copyRules(copy: (String) -> Unit) = ruleBook.copy(copy)

    fun moveRule(index: Int, up: Boolean) = ruleBook.move(index, up)

    // Connections and usage (Tools): see ConnectionsPage.

    private val connections = ConnectionsPage(scope, traffic, object : ConnectionsPage.Host {
        override val tools get() = this@AppModel.tools
        override fun update(change: (ToolsState) -> ToolsState) {
            this@AppModel.tools = change(this@AppModel.tools)
        }
        override val api get() = clash
        override val lists get() = Why.Lists(preferences.bypass, preferences.direct, preferences.proxied)
        override val mode get() = preferences.mode
        override fun inUse() = this@AppModel.inUse()
        override fun nameOf(tag: String) = this@AppModel.nameOf(tag)
        override fun stalling(tag: String) {
            health.record(tag, null)
            log(tr("Connections through {0} are stalling; testing it now" , nameOf(tag)))
            nudges.trySend(Unit)
        }
        override fun addSite(site: String, direct: Boolean) = this@AppModel.addSite(site, direct)
        override fun hint(text: String) = this@AppModel.hint(text)
        override fun notice(text: String) = onNotice(text)
    })

    /** While Tools is open and KOne connected, what the core carries, once a second. */
    fun watchConnections(on: Boolean) = connections.watch(on)

    /**
     * The window came into sight or went out of it. In sight, delays gone stale are tested again
     * at once, and while disconnected the core that tests them is started; out of sight, testing
     * slows (see [Sweeps]) and that core is let go after [Sweeps.PROBE_REST].
     */
    fun seen(on: Boolean) {
        if (inSight == on) return
        inSight = on
        resting?.cancel()
        resting = null
        if (on) {
            if (connection == Connection.Idle && probeApi == null && probe?.running != true) startProbe()
            if (Sweeps.stale(System.currentTimeMillis() - swept, nodes.size)) sweepNow.trySend(Unit)
        } else {
            restSoon()
        }
    }

    /** While disconnected and out of sight, the testing core is stopped after a while. */
    private fun restSoon() {
        if (inSight || connection != Connection.Idle) return
        resting?.cancel()
        resting = scope.launch {
            delay(Sweeps.PROBE_REST)
            if (!inSight && connection == Connection.Idle) {
                probeApi = null
                withContext(Dispatchers.IO) { probe?.stop() }
            }
        }
    }

    /**
     * The network changed under KOne (see [NetworkChanges]). Connections made over an address
     * that is gone cannot recover, so they are ended and programs open new ones at once; the node
     * in use is tested now, every node soon, and a new network is looked at for a sign-in page.
     */
    private fun networkChanged(change: NetworkChanges.Change) {
        log(
            when {
                change.gained.isEmpty() -> tr("The network went away")
                change.lost.isEmpty() -> tr("Another network joined")
                else -> tr("The network changed")
            },
        )
        val api = clash
        if (api != null) {
            if (change.lost.isNotEmpty()) scope.launch(Dispatchers.IO) { api.close(null) }
            nudges.trySend(Unit)
            if (change.gained.isNotEmpty()) signIns.look()
        }
        if (change.gained.isNotEmpty()) sweepNow.trySend(Unit)
    }

    /** Waits for the next sweep of every node: due by [Sweeps.every], or asked for now. */
    private suspend fun untilSweep() {
        while (true) {
            val due = swept + Sweeps.every(inSight, nodes.size) - System.currentTimeMillis()
            if (due <= 0) return
            // Looked at again every quarter minute: the window coming into sight shortens the wait.
            if (withTimeoutOrNull(minOf(due, 15_000L)) { sweepNow.receive() } != null) return
        }
    }

    /**
     * While connected, every five seconds (each second while Tools shows the connections, which
     * count the same way): what each node and program carried since the last look.
     */
    private suspend fun countTraffic(api: ClashApi) {
        traffic.forget()
        while (clash === api) {
            delay(5_000)
            if (!connections.showing) withContext(Dispatchers.IO) { api.connections() }?.let(connections::count)
        }
    }

    fun sendSite(site: String, direct: Boolean) = connections.sendSite(site, direct)

    fun takeAdvice(site: String) = connections.takeAdvice(site)

    fun dismissAdvice(site: String) = connections.dismissAdvice(site)

    fun showUsage(span: Int?) = connections.showUsage(span)

    fun closeConnection(id: String?) = connections.close(id)

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
                        trail.addLast(down to up)
                        while (trail.size > 60) trail.removeFirst()
                        tools = tools.copy(today = usage.today(), month = usage.month())
                        refreshTelemetry()
                    }
                }
            }
            launch { while (isActive && clash === api) { delay(1000); seconds++; refreshTelemetry() } }
            launch(Dispatchers.IO) { detectExit() }
            launch { testDelays(api) }
            launch { countTraffic(api) }
            launch { watch(api) }
        }
    }

    /**
     * While connected, every 15 seconds: the node in use is tested; Automatic moves, with
     * patience, to the best node; and a chosen node that stops answering is stood in for until
     * it answers again.
     */
    private suspend fun watch(api: ClashApi) {
        delay(5_000)
        while (clash === api) {
            val chosen = account.binding?.selected
            val region = regionOf(chosen)
            when {
                chosen == KoneConfig.AUTO_TAG && auto -> steerAutomatic(api)
                region != null -> steerRegion(api, region)
                chosen != null -> guard(api, chosen)
            }
            // The node in use not answering may be the network, not the node: a sign-in page.
            inUse()?.let { tag ->
                if (health.failing(tag) >= Health.FAILED) signIns.look()
                else if (health.answering(tag) >= Health.RECOVERED) signIns.answered()
                // And it is time to know which others answer now, for the move away from it.
                if (Sweeps.afterFailure(health.failing(tag), System.currentTimeMillis() - swept)) sweepNow.trySend(Unit)
            }
            // Every 15 seconds, or at once when real traffic through the node in use stalls.
            withTimeoutOrNull(15_000) { nudges.receive() }
        }
    }

    /** Wakes the watchdog early: the node in use is stalling. */
    private val nudges = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    private suspend fun steerAutomatic(api: ClashApi) {
        val current = drivenPick ?: withContext(Dispatchers.IO) { api.now(KoneConfig.AUTO_TAG) }
        if (current != null) testOne(api, current)
        if (clash !== api || account.binding?.selected != KoneConfig.AUTO_TAG) return
        val next = steer.next(current, nodes.map { it.tag })
        if (next != null && withContext(Dispatchers.IO) { api.select(next) }) {
            if (current != null && health.failing(current) >= Health.FAILED) closeVia(api, current)
            drivenPick = next
            log(tr("Automatic: {0}" , nameOf(next)))
        }
        autoNow = drivenPick ?: current
        refresh()
    }

    /** A country's Automatic: the same patient steering as Automatic, among that country's nodes only. */
    private suspend fun steerRegion(api: ClashApi, region: String) {
        val members = regionNodes(region).map { it.tag }
        val current = drivenPick?.takeIf { it in members } ?: withContext(Dispatchers.IO) { api.now(KoneConfig.PROXY_TAG) }?.takeIf { it in members }
        if (current != null) testOne(api, current)
        if (clash !== api || regionOf(account.binding?.selected) != region) return
        // The others are tested now and then too, so there is someone to move to.
        if (members.count { health.tested(it) } < 2) measureSome(api, (members - setOfNotNull(current)).take(4))
        val next = steer.next(current, members)
        if (next != null && withContext(Dispatchers.IO) { api.select(next) }) {
            if (current != null && health.failing(current) >= Health.FAILED) closeVia(api, current)
            drivenPick = next
            log(tr("Automatic in {0}: {1}" , region, nameOf(next)))
        } else if (current == null) {
            drivenPick = null
        } else {
            drivenPick = current
        }
        refresh()
    }

    // Automatic within a country

    /** The country (category) of a country's Automatic as the selection keeps it ("auto@Japan"), if it still has two nodes or more. */
    private fun regionOf(selected: String?): String? =
        selected?.takeIf { it.startsWith(REGION_AUTO) }?.removePrefix(REGION_AUTO)?.takeIf { it in regions() }

    /** The countries with two nodes or more, each of which has an Automatic of its own. */
    private fun regions(): Set<String> = nodes.groupBy(::categoryOf).filter { it.value.size >= 2 }.keys

    private fun regionNodes(region: String) = nodes.filter { categoryOf(it) == region }

    /** The node a country's Automatic is on or starts on: KOne's pick, else the healthiest, else the fastest last test, else the first. */
    private fun regionPick(region: String): SourceNode? {
        val members = regionNodes(region)
        drivenPick?.let { pick -> members.firstOrNull { it.tag == pick }?.let { return it } }
        health.best(members.map { it.tag })?.let { best -> return members.first { it.tag == best } }
        return members.filter { delays[it.tag] != null }.minByOrNull { delays[it.tag]!! } ?: members.firstOrNull()
    }

    /** The node the core starts on for what the person chose: a country's Automatic starts on its pick. */
    private fun startTag(selected: String?): String? = regionOf(selected)?.let { regionPick(it)?.tag } ?: selected

    /** A country's Automatic as a row at the head of its tab: the node it is on, with that node's flag and delay. */
    private fun regionNode(region: String): Node {
        val pick = regionPick(region)
        return Node(REGION_AUTO + region, if (pick != null) "$AUTO_NAME · ${pick.name}" else AUTO_NAME, pick?.country, region, pick?.let { delays[it.tag] }, tested = pick?.tag in delays)
    }

    /** Fails over from [chosen] when it stops answering, and back when it answers again. */
    private suspend fun guard(api: ClashApi, chosen: String) {
        testOne(api, chosen)
        standIn?.let { testOne(api, it) }
        if (clash !== api || account.binding?.selected != chosen) return
        val stand = standIn
        if (stand != null && health.answering(chosen) >= Health.RECOVERED) {
            if (withContext(Dispatchers.IO) { api.select(chosen) }) {
                standIn = null
                closeVia(api, stand)
                tellConnection(tr("Back on {0}" , nameOf(chosen)))
            }
            return
        }
        val left = stand ?: chosen
        if (!preferences.failover || health.failing(left) < Health.FAILED) return
        val others = nodes.map { it.tag } - chosen - left
        var best = health.best(others)
        if (best == null) {
            // Nothing known to work: a few of the others are tried now.
            measureSome(api, others.sortedBy { delays[it] ?: Int.MAX_VALUE }.take(5))
            best = health.best(others)
        }
        if (best == null || clash !== api) return
        if (withContext(Dispatchers.IO) { api.select(best) }) {
            standIn = best
            closeVia(api, left)
            tellConnection("${nameOf(chosen)} is not answering; using ${nameOf(best)}")
        }
    }

    /** Tests one node through [api] and keeps the result. */
    private suspend fun testOne(api: ClashApi, tag: String) {
        val measured = withContext(Dispatchers.IO) { api.delay(tag) }
        if (clash !== api) return
        health.record(tag, measured)
        delays[tag] = measured
        refresh()
    }

    private suspend fun measureSome(api: ClashApi, tags: List<String>) = coroutineScope {
        tags.map { tag -> async { testOne(api, tag) } }.awaitAll()
    }

    /** Ends the connections still going through [tag], so the programs behind them reconnect through the new node at once. */
    private suspend fun closeVia(api: ClashApi, tag: String) = withContext(Dispatchers.IO) {
        api.connections()?.connections?.filter { it.via == tag }?.forEach { api.close(it.id) }
    }

    /** A node's name; a country's group, which the rules through it use, as "Through Japan". */
    private fun nameOf(tag: String) =
        nodes.firstOrNull { it.tag == tag }?.name ?: UserRules.viaOf(tag)?.let { "Through " + (Countries.byCode(it)?.en ?: it) } ?: tag

    /** A change of node KOne made itself: said on the dashboard, and by Windows when the window is away. */
    private fun tellConnection(message: String) {
        log(message)
        onNotice(message)
        hint(message)
    }

    private suspend fun detectExit() {
        repeat(3) {
            val found = ExitTrace.detect(proxied)
            if (found != null) {
                withContext(Dispatchers.Main) { exit = found; refresh() }
                return
            }
            delay(2000)
        }
    }

    /** Tests every node through [api] now and then as [Sweeps] says, while it is the one in use. */
    private suspend fun testDelays(api: ClashApi) {
        while (clash === api) {
            measure(api) { clash === api }
            untilSweep()
        }
    }

    /** While not connected, the core without TUN carries the nodes so their delays can be tested. */
    private fun startProbe() {
        val probe = probe ?: return
        val source = source() ?: return
        val api = CoreApi(freePort(), token())
        val config = runCatching { KoneConfig.build(source, rules, api, tun = false, region = preferences.region).config.toString() }.getOrNull() ?: return
        scope.launch {
            val started = withContext(Dispatchers.IO) { probe.start(config) }
            if (!started) return@launch
            probeApi = api
            val clash = ClashApi(api)
            val ready = withContext(Dispatchers.IO) {
                repeat(50) { if (clash.answers()) return@withContext true; Thread.sleep(200) }
                false
            }
            restSoon()
            while (ready && isActive && probeApi === api && connection == Connection.Idle) {
                measure(clash) { probeApi === api }
                untilSweep()
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

    /**
     * The console's source with the person's own nodes after its own: the imported ones, then each
     * subscription's. Should a group stop it building, the person's groups are left out rather
     * than the managed nodes. Null until the console has sent a source.
     */
    private fun merged(): Merge.Merged? {
        val managed = account.binding?.source ?: return null
        val groups = listOf(imports.all) + subscriptions.all.map { it.kept.nodes }
        return sequenceOf(groups, listOf(imports.all) + subscriptions.all.map { emptyList() }, groups.map { emptyList() })
            .map { Merge.into(managed, it) }
            .firstOrNull { builds(it.source) } ?: Merge.into(managed, groups.map { emptyList() })
    }

    private fun builds(source: JsonObject) = runCatching { KoneConfig.build(source, rules, CoreApi(9090, "list")) }.isSuccess

    private fun source(): JsonObject? = merged()?.source

    /** The nodes [source] carries. */
    private fun nodesOf(source: JsonObject): List<SourceNode> =
        runCatching { KoneConfig.build(source, rules, CoreApi(9090, "list")).nodes }.getOrDefault(emptyList())

    /** The nodes [source] carries, by tag. */
    private fun tagsOf(source: JsonObject): Set<String> = nodesOf(source).mapTo(HashSet()) { it.tag }

    private fun loadNodes() {
        val binding = account.binding
        val merged = merged()
        importedOrder = merged?.groups?.firstOrNull().orEmpty()
        importedTags = importedOrder.toSet()
        subscribed = merged?.let { all -> subscriptions.all.zip(all.groups.drop(1)).flatMap { (subscription, tags) -> tags.map { it to subscription } }.toMap() }.orEmpty()
        nodes = merged?.source?.let { runCatching { KoneConfig.build(it, rules, CoreApi(9090, "list")).nodes }.getOrNull() }.orEmpty()
        health.forget(nodes.mapTo(HashSet()) { it.tag })
        // The countries rules can go through, in the order the country list keeps them.
        val present = nodes.mapNotNullTo(HashSet()) { it.country }
        ruleBook.countriesChanged(Countries.all.map { it.code }.filter { it in present })
        if (tab !in categories()) {
            tab = when {
                binding?.selected == KoneConfig.AUTO_TAG && auto -> AUTO_NAME
                regionOf(binding?.selected) != null -> regionOf(binding?.selected)!!
                else -> categoryOf(nodes.firstOrNull { it.tag == binding?.selected } ?: nodes.firstOrNull())
            }
        }
        refresh()
    }

    private fun categoryOf(node: SourceNode?): String =
        node?.country?.let { Countries.byCode(it)?.zh } ?: when {
            node == null -> "Node"
            node.tag in importedTags -> IMPORTED_NAME
            else -> subscribed[node.tag]?.name ?: "Node"
        }

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

    /** Says [message] softly for a few seconds. */
    private fun hint(message: String) {
        hint = message
        refresh()
        scope.launch {
            delay(6_000)
            if (hint == message) {
                hint = null
                refresh()
            }
        }
    }

    private fun say(message: String?) {
        notice = message
        refresh()
    }

    /** Service status arrives on the model scope; apply a bounded batch without one job per line. */
    internal fun appendLogs(lines: List<String>) {
        if (lines.isEmpty()) return
        val kept = lines.takeLast(500)
        val remove = (logs.size + kept.size - 500).coerceAtLeast(0)
        if (remove > 0) logs.removeRange(0, remove)
        logs.addAll(kept)
    }

    private fun log(line: String) {
        scope.launch {
            logs += line
            if (logs.size > 500) logs.removeRange(0, logs.size - 500)
        }
    }

    /** A node as the screens show it. */
    private fun shown(node: SourceNode) = Node(
        node.tag, node.name, node.country, categoryOf(node), delays[node.tag], tested = node.tag in delays,
        imported = node.tag in importedTags, protocol = node.type, loss = health.loss(node.tag), speed = speeds[node.tag],
        history = health.samples(node.tag),
    )

    /** Rate and clock ticks do not change nodes, health or subscription sections. */
    internal fun refreshTelemetry() {
        dashboard = dashboard.copy(
            downKbps = rates.first / 1024.0,
            upKbps = rates.second / 1024.0,
            seconds = seconds,
            trail = trail.toList(),
            expiry = Expiry.hint(account.binding?.expiresAt),
        )
    }

    private fun refresh() {
        val selected = account.binding?.selected?.takeIf { tag -> (tag == KoneConfig.AUTO_TAG && auto) || regionOf(tag) != null || nodes.any { it.tag == tag } }
            ?: nodes.firstOrNull()?.tag
        val listed = nodes.map(::shown)
        // Each country with two nodes or more leads its tab with an Automatic of its own.
        val regional = regions()
        val withRegions = listed.groupBy { it.category }.flatMap { (category, members) ->
            if (category in regional) listOf(regionNode(category)) + members else members
        }
        dashboard = DashboardState(
            categories = categories(),
            nodes = (if (auto) listOf(autoNode()) else emptyList()) + withRegions,
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
            hint = hint,
            canImport = account.binding != null,
            standIn = standIn?.let(::nameOf),
            trail = trail.toList(),
            signIn = signIns.state,
            pausedUntil = pausedUntil?.let { java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(it), java.time.ZoneId.systemDefault()).withSecond(0).withNano(0).toString() },
        )
        nodesPage = NodesState(
            sections = buildList {
                add(NodeSection("managed", SourceKind.Managed, "Managed", listed.filter { it.id !in importedTags && it.id !in subscribed }))
                if (importedTags.isNotEmpty()) add(NodeSection("imported", SourceKind.Imported, "Imported", listed.filter { it.id in importedTags }))
                subscriptions.all.forEach { subscription ->
                    add(
                        NodeSection(
                            subscription.id, SourceKind.Subscription, subscription.name, listed.filter { subscribed[it.id]?.id == subscription.id },
                            subscription.info, subscription.updated, subscription.error, subscription.hours, subscription.url, feeds.updating(subscription.id),
                            leaveOut = subscription.leaveOut, left = subscription.kept.left,
                        ),
                    )
                }
            },
            editing = ownNodes.editing,
            qr = ownNodes.qr,
            speedTesting = speedTesting,
            selected = selected,
            testing = testing,
            notice = nodesNotice?.first,
            noticeIsError = nodesNotice?.second == true,
        )
    }

    // Last of all, so every property above, the delegates among them, is set before it runs:
    // started earlier, refresh() met a sign-in delegate not yet made, and KOne did not start.
    init {
        // A KOne that crashed while Windows' proxy pointed at it puts the person's own back now;
        // one that is ended with Windows puts it back on its way out.
        SystemProxy.restore()
        Runtime.getRuntime().addShutdownHook(Thread { SystemProxy.restore(); usage.save(); traffic.save() })
        NetworkChanges { change -> scope.launch { networkChanged(change) } }.start()
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
            // Subscriptions due for their update are fetched quietly; a failure keeps their last good nodes.
            delay(20_000)
            while (isActive) {
                if (account.binding != null) {
                    val now = System.currentTimeMillis()
                    feeds.updateDue(now)
                }
                delay(TimeUnit.MINUTES.toMillis(15))
            }
        }
        scope.launch {
            // The category lists are looked at every hour; each is downloaded again only once a day, and only if it changed.
            delay(30_000)
            while (isActive) {
                updateLists()
                delay(TimeUnit.HOURS.toMillis(1))
            }
        }
        scope.launch {
            // Every four hours, as the phone does, the nodes are brought in step with the console.
            while (isActive) {
                delay(TimeUnit.HOURS.toMillis(4))
                if (account.binding != null) syncNow()
            }
        }
    }

    companion object {
        private const val SLEPT_MILLIS = 20_000L
        private const val AUTO_NAME = "Automatic"
        /** How a country's Automatic is kept as the selection: "auto@" and the country's name. */
        private const val REGION_AUTO = "auto@"
        private const val IMPORTED_NAME = "Imported"

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
