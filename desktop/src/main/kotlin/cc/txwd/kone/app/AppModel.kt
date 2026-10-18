package cc.txwd.kone.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.txwd.kone.console.ClaimLink
import cc.txwd.kone.console.ConsoleException
import cc.txwd.kone.core.Carried
import cc.txwd.kone.core.ClashApi
import cc.txwd.kone.core.Dns
import cc.txwd.kone.core.CoreApi
import cc.txwd.kone.core.Ends
import cc.txwd.kone.core.ExitTrace
import cc.txwd.kone.core.Host
import cc.txwd.kone.core.Hosts
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
import cc.txwd.kone.node.Countries
import cc.txwd.kone.node.SharedNode
import cc.txwd.kone.node.NodeDraft
import cc.txwd.kone.node.NodeForm
import cc.txwd.kone.node.ShareLinkWriter
import cc.txwd.kone.node.ShareLinks
import cc.txwd.kone.node.SubscriptionContent
import cc.txwd.kone.ui.Connection
import cc.txwd.kone.ui.DashboardState
import cc.txwd.kone.ui.UsageDetails
import cc.txwd.kone.ui.DnsLookupState
import cc.txwd.kone.ui.SiteCheckState
import cc.txwd.kone.ui.KeyState
import cc.txwd.kone.ui.Node
import cc.txwd.kone.ui.NodeEditing
import cc.txwd.kone.ui.NodeQr
import cc.txwd.kone.ui.qrModules
import cc.txwd.kone.ui.NodeSection
import cc.txwd.kone.ui.NetworkState
import cc.txwd.kone.ui.NodesState
import cc.txwd.kone.ui.RulesState
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
import kotlinx.serialization.json.Json
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

    /** A node being written by hand on the Nodes page. */
    private var editing: NodeEditing? = null

    /** The last bandwidth test of each node, and the node being tested now (one at a time). */
    private val speeds = HashMap<String, Speed>()
    private var speedTesting: String? = null

    /** The person's subscriptions (Nodes), fetched in the background and joined after the imported nodes. */
    private val subscriptions = Subscriptions(File(Places.data, "subscriptions.bin"), Vault.forThisComputer())

    /** The subscription each listed node came from, by tag. */
    private var subscribed: Map<String, Subscription> = emptyMap()

    /** Subscriptions being fetched now. */
    private val updating = HashSet<String>()

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
        override fun changed() {
            rulesPage = rulesNow()
        }
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

    /** Rules: the programs and sites lists, the person's own rules and ad blocking, as kept. */
    var rulesPage by mutableStateOf(rulesNow())
        private set
    private var watching: Job? = null

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
    private val warned = HashSet<String>()

    /** Opens a page in the person's browser (the network's sign-in page). */
    var onBrowse: (String) -> Unit = {}

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
        Runtime.getRuntime().addShutdownHook(Thread { SystemProxy.restore(); usage.save(); traffic.save() })
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
                    subscriptions.all.filter { it.due(now) }.forEach { updateSubscription(it.id, first = false) }
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
                onNotice("KOne is on again after the pause")
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
        runningTags = tagsOf(source)
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
                        preferences.hosts, preferences.unmatchedDirect,
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
                    unmatchedDirect = preferences.unmatchedDirect,
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
                log("The adapter is unavailable$why; switching to the system proxy")
                onNotice("The adapter is unavailable$why; using the system proxy")
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

    /**
     * The share links in [text] (from the clipboard or a file), each read as the phone reads it and
     * checked by sing-box, added to the person's own nodes. Says how it went on the dashboard.
     */
    fun importText(text: String) {
        val source = account.binding?.source ?: return
        scope.launch {
            val (message, good) = withContext(Dispatchers.IO) {
                val links = ShareLinks.findAll(text).take(100)
                if (links.isEmpty()) return@withContext "No node links found" to false
                var refusal: String? = null
                val read = links.mapNotNull { link ->
                    runCatching { ShareLinks.parse(link) }.onFailure { refusal = refusal ?: it.message }.getOrNull()
                }
                fun fits(found: List<SharedNode>): Boolean =
                    accepts(Merge.into(source, listOf(found.map { Json.parseToJsonElement(it.outbound.toString()) as JsonObject })).source)
                // All at once when they all fit; one by one only to find which do not.
                val fitting = if (read.isEmpty() || fits(read)) read else read.filter { fits(listOf(it)) }.also {
                    if (it.size < read.size) refusal = refusal ?: "sing-box does not accept some nodes"
                }
                val added = imports.add(fitting)
                when {
                    added > 0 -> "Imported $added nodes" to true
                    fitting.isNotEmpty() -> "These nodes are already here" to true
                    else -> (refusal ?: "No usable nodes") to false
                }
            }
            nodesChanged()
            tell(message, error = !good)
            if (good) {
                notice = null
                hint(message)
            } else {
                say(message)
            }
        }
    }

    /** Takes an imported node out; the console's are the console's to take. */
    fun removeImported(tag: String) {
        if (tag !in importedTags) return
        scope.launch {
            withContext(Dispatchers.IO) { imports.remove(tag) }
            delays.remove(tag)
            nodesChanged()
            if (account.binding?.selected == tag) restart()
        }
    }

    // Nodes

    /** A subscription link typed or pasted: kept and fetched at once. False when it is not one KOne fetches (https only). */
    fun addSubscription(text: String): Boolean {
        val url = text.trim()
        if (!SubscriptionContent.acceptable(url)) return false
        val added = subscriptions.add(url, SubscriptionContent.name(url, null))
        if (added == null) {
            tell(if (subscriptions.all.size >= Subscriptions.MOST) "No more subscriptions can be added" else "This subscription is already here", error = true)
            return true
        }
        refresh()
        updateSubscription(added.id, first = true)
        return true
    }

    fun updateSubscription(id: String) = updateSubscription(id, first = false)

    private fun updateSubscription(id: String, first: Boolean) {
        val managed = account.binding?.source ?: return
        if (!updating.add(id)) return
        refresh()
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { fetchSubscription(id, managed, first) }.getOrNull() }
            updating.remove(id)
            nodesChanged()
            outcome?.let { (message, error) -> tell(message, error) }
        }
    }

    /**
     * Fetches, reads and checks one subscription, and keeps what sing-box accepts. A failure keeps
     * the last good nodes and is shown on the subscription; a partial success is told in one line.
     */
    private fun fetchSubscription(id: String, managed: JsonObject, first: Boolean): Pair<String, Boolean>? {
        val subscription = subscriptions.all.firstOrNull { it.id == id } ?: return null
        val now = System.currentTimeMillis()
        val fetched = try {
            SubscriptionFetch.fetch(subscription.url, proxied)
        } catch (failure: Exception) {
            subscriptions.change(id) { it.copy(tried = now, error = SubscriptionFetch.reason(failure)) }
            return null
        }
        val read = SubscriptionContent.read(fetched.body)
        val kept = acceptedNodes(managed, read.nodes)
        if (kept.isEmpty()) {
            subscriptions.change(id) { it.copy(tried = now, info = fetched.info ?: it.info, error = read.refusal ?: "No usable nodes") }
            return null
        }
        // A name KOne made up gives way to the provider's own.
        val madeUp = subscription.name == SubscriptionContent.name(subscription.url, null)
        subscriptions.change(id) {
            it.copy(
                name = if (madeUp) fetched.name else it.name,
                hours = if (first) fetched.hours ?: it.hours else it.hours,
                nodes = kept, info = fetched.info ?: it.info, updated = now, tried = now, error = null,
            )
        }
        // The provider's terms running out, said once a session, before the nodes simply stop.
        subscriptions.all.firstOrNull { it.id == id }?.warning(now)?.let { warning -> if (warned.add(warning)) onNotice(warning) }
        val left = read.nodes.size - kept.size + read.refused
        return if (left > 0) "${kept.size} nodes kept, $left left out" to false else null
    }

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

    /** A subscription's name, link and interval; false when the link is not one KOne fetches. A new link is fetched at once. */
    fun editSubscription(id: String, name: String, url: String, hours: Int): Boolean {
        if (!SubscriptionContent.acceptable(url) || hours !in 0..720) return false
        val before = subscriptions.all.firstOrNull { it.id == id } ?: return false
        subscriptions.change(id) { it.copy(name = name.ifBlank { it.name }.take(32), url = url, hours = hours) }
        loadNodes()
        if (url != before.url) updateSubscription(id, first = false)
        return true
    }

    fun removeSubscription(id: String) {
        val used = account.binding?.selected?.let { subscribed[it]?.id } == id
        if (!subscriptions.remove(id)) return
        nodesChanged()
        if (used) restart()
    }

    /**
     * An imported or subscribed node as a share link, handed to [copy]. The console's nodes are
     * never written out: they are the owner's, given to this key only.
     */
    fun copyLink(tag: String, copy: (String) -> Unit) {
        if (tag !in importedTags && tag !in subscribed) return
        val outbound = (merged()?.source?.get("outbounds") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject }?.firstOrNull { (it["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.content == tag }
        val link = outbound?.let { ShareLinkWriter.write(JsonObject(it - "tag"), nameOf(tag)) }
        if (link == null) {
            tell("This node cannot be written as a link", error = true)
            return
        }
        copy(link)
        tell("Link copied", error = false)
    }

    /**
     * Tests how fast [tag] carries data, through a core of its own, so the live connection is left
     * alone. Uses about 35 MB; one node at a time.
     */
    fun testSpeed(tag: String) {
        val core = Places.core
        if (core == null) {
            tell("Speed tests need KOne installed", error = true)
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
                val config = runCatching { KoneConfig.build(source, rules, api, selected = tag, tun = false, mode = "Global", mixed = port).config.toString() }.getOrNull()
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
            if (result == null || (result.down == null && result.up == null)) tell("${nameOf(tag)} did not carry the test", error = true) else speeds[tag] = result
            refresh()
        }
    }

    /**
     * Shows an imported or subscribed node as a QR code (null closes it). The console's nodes are
     * never written out, as for [copyLink].
     */
    fun showQr(tag: String?) {
        if (tag == null) {
            qr = null
            refresh()
            return
        }
        if (tag !in importedTags && tag !in subscribed) return
        val outbound = (merged()?.source?.get("outbounds") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { it as? JsonObject }?.firstOrNull { (it["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.content == tag }
        val link = outbound?.let { ShareLinkWriter.write(JsonObject(it - "tag"), nameOf(tag)) }
        if (link == null || qrModules(link) == null) {
            tell("This node cannot be shown as a QR code", error = true)
            return
        }
        qr = NodeQr(nameOf(tag), link)
        refresh()
    }

    private var qr: NodeQr? = null

    /** Opens the form for a new node. */
    fun addNode() {
        editing = NodeEditing(null, NodeDraft())
        refresh()
    }

    /** Opens an imported node in the form; subscribed nodes are their provider's, the console's the owner's. */
    fun editNode(tag: String) {
        val outbound = imports.all.getOrNull(importedOrder.indexOf(tag)) ?: return
        val draft = NodeForm.read(JsonObject(outbound - "tag"), nameOf(tag))
        if (draft == null) {
            tell("This node has settings the form cannot show", error = true)
            return
        }
        editing = NodeEditing(tag, draft)
        refresh()
    }

    fun cancelNode() {
        editing = null
        refresh()
    }

    /**
     * Saves the node in the form, once sing-box accepts it beside the console's: a new one joins
     * the imported nodes, an edited one takes its old place. A running connection on the edited
     * node starts afresh with it.
     */
    fun saveNode(draft: NodeDraft) {
        val open = editing ?: return
        val outbound = NodeForm.build(draft).getOrElse { failure ->
            editing = open.copy(draft = draft, error = failure.message ?: "The node is not complete")
            refresh()
            return
        }
        val managed = account.binding?.source ?: run {
            editing = open.copy(draft = draft, error = "Waiting for the console's nodes")
            refresh()
            return
        }
        editing = open.copy(draft = draft, error = null, saving = true)
        refresh()
        scope.launch {
            val clean = JsonObject(outbound - "tag")
            val name = (outbound["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
            val accepted = withContext(Dispatchers.IO) { accepts(Merge.into(managed, listOf(listOf(outbound))).source) }
            val saved = accepted && withContext(Dispatchers.IO) {
                if (open.tag == null) imports.addOutbounds(listOf(clean to name)) > 0 else imports.replace(importedOrder.indexOf(open.tag), clean, name)
            }
            if (!saved) {
                editing = editing?.copy(saving = false, error = if (accepted) "This node is already here" else "sing-box does not accept this node")
                refresh()
                return@launch
            }
            editing = null
            nodesChanged()
            tell(if (open.tag == null) "Node added" else "Node saved", error = false)
            if (open.tag != null && (account.binding?.selected == open.tag || inUse() == open.tag)) restart()
        }
    }

    /** Every imported and subscribed node as share links, one a line; never the console's. */
    fun exportLinks(): String {
        val outbounds = (merged()?.source?.get("outbounds") as? kotlinx.serialization.json.JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        return outbounds.mapNotNull { outbound ->
            val tag = (outbound["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            if (tag !in importedTags && tag !in subscribed) return@mapNotNull null
            ShareLinkWriter.write(JsonObject(outbound - "tag"), nameOf(tag))
        }.joinToString("\n")
    }

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

    /** Once a minute for a few nodes; for many, every ten minutes, as each test is a real connection. */
    private fun testEvery(): Long = if (nodes.size <= 30) 60_000 else 600_000

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
    fun setBypass(program: String, straight: Boolean) {
        val now = preferences.bypass
        val next = if (straight) now + program else now.filterNot { it.equals(program, ignoreCase = true) }
        preferences.bypass = next
        if (preferences.bypass == now) return
        tools = tools.copy(bypass = preferences.bypass)
        rulesPage = rulesNow()
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
        rulesPage = rulesNow()
        restart()
        return true
    }

    fun removeSite(site: String) {
        preferences.direct -= site
        preferences.proxied -= site
        rulesPage = rulesNow()
        restart()
    }

    // Diagnosis (Tools)

    /** The node carrying traffic now: a stand-in, KOne's pick for Automatic, the core's, or the person's own. */
    private fun inUse(): String? {
        standIn?.let { return it }
        val chosen = account.binding?.selected ?: return nodes.firstOrNull()?.tag
        if (chosen == KoneConfig.AUTO_TAG) return drivenPick ?: autoNow ?: autoPick()?.tag
        return regionOf(chosen)?.let { regionPick(it)?.tag } ?: chosen
    }

    /**
     * Checks the way out step by step, through the same path as the person's traffic, showing
     * each as it finishes: the key and nodes, the service, the node in use, DNS, a few sites and
     * the exit. The first failure comes with one suggestion.
     */
    fun diagnose() {
        if (tools.checking) return
        scope.launch {
            val checks = mutableListOf<Check>()
            fun show(running: String?) {
                tools = tools.copy(checks = checks.toList() + listOfNotNull(running?.let { Check(it, Outcome.Running) }), checking = running != null, advice = null)
            }
            show("Network")
            checks += when (val reached = signIns.reach()) {
                null -> Check("Network", Outcome.Warn, "Could not check")
                else -> Diagnosis.network(reached)
            }
            show(Conflicts.TITLE)
            checks += withContext(Dispatchers.IO) { Conflicts.check(Conflicts.adapters(), SystemProxy.read(), proxied) }
            show("Key")
            val binding = account.binding
            checks += when {
                binding == null -> Check("Key", Outcome.Fail, "No key yet")
                binding.source == null -> Check("Key", Outcome.Fail, "The console has not sent nodes yet")
                else -> Check("Key", Outcome.Pass, "${nodes.size} nodes")
            }
            if (preferences.way == WAY_ADAPTER && !noAdapter) {
                show("Service")
                checks += when {
                    service != null -> Check("Service", Outcome.Pass, "Connected through it")
                    !Places.windows -> Check("Service", Outcome.Warn, "Only on Windows")
                    else -> {
                        val version = withContext(Dispatchers.IO) { ServiceLink.open(Places.service)?.use { it.hello() } }
                        if (version != null) Check("Service", Outcome.Pass, "Version $version") else Check("Service", Outcome.Fail, "Not answering")
                    }
                }
            }
            show("Node")
            val api = clash ?: probeApi?.let(::ClashApi)
            val tag = inUse()
            checks += if (api == null || tag == null) {
                Check("Node", Outcome.Warn, "Nothing to test through yet")
            } else {
                val measured = withContext(Dispatchers.IO) { api.delay(tag) }
                health.record(tag, measured)
                delays[tag] = measured
                Diagnosis.node(nameOf(tag), measured)
            }
            show("DNS")
            checks += Diagnosis.names()
            show("Sites")
            checks += Diagnosis.sites(proxied)
            show("Exit")
            val online = connection == Connection.Online
            val address = withContext(Dispatchers.IO) { ExitTrace.detect(proxied) }
            checks += when {
                address == null -> Check("Exit", Outcome.Warn, "Unknown")
                online -> Check("Exit", Outcome.Pass, address)
                else -> Check("Exit", Outcome.Warn, "$address, not connected")
            }
            tools = tools.copy(checks = checks.toList(), checking = false, advice = Diagnosis.advice(checks))
            refresh()
        }
    }

    /** The last diagnosis as plain text, with the version, mode and way, for whoever helps. */
    fun diagnosisReport(): String = Diagnosis.report(
        tools.checks, tools.advice,
        listOf(
            "Version" to (System.getProperty("jpackage.app-version") ?: "development"),
            "Windows" to System.getProperty("os.version").orEmpty() + " " + System.getProperty("os.arch").orEmpty(),
            "Mode" to preferences.mode,
            "Way" to if (proxied != null) "System proxy" else "Virtual adapter",
            "Connected" to (connection == Connection.Online).toString(),
        ),
    )

    /** Hands the report to [copy] and says so. */
    fun copyReport(copy: (String) -> Unit) {
        if (tools.checks.isEmpty()) return
        copy(diagnosisReport())
        hint("Diagnosis copied")
    }

    fun clearChecks() {
        if (!tools.checking) tools = tools.copy(checks = emptyList(), advice = null)
    }

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

    /**
     * Looks [input] up every way KOne can, at once, showing each as it answers: as programs see it,
     * through KOne's own DNS (while it runs), from the direct server (unless the adapter is on, when
     * KOne answers for it) and from the remote one. False when [input] is not a domain name.
     */
    fun lookUp(input: String): Boolean {
        val name = DnsLookup.clean(input) ?: return false
        if (tools.lookup?.running == true) return true
        val refused = DnsLookup.refused(name, account.binding?.source)
        val ipv6 = preferences.ipv6
        val api = clash ?: probeApi?.let(::ClashApi)
        val adapterOn = service != null
        val viaPort = proxied
        val ways = buildList<Pair<String, () -> Check>> {
            add("Apps" to { DnsLookup.system(name) })
            if (api != null) add("KOne" to { DnsLookup.core(api, name, ipv6) })
            if (!adapterOn) add("Direct" to { DnsLookup.direct(DnsLookup.directServer(preferences.directDns), name) })
            add("Remote" to { DnsLookup.remote(DnsLookup.remoteUrl(preferences.remoteDns), name, viaPort) })
        }
        if (refused) {
            tools = tools.copy(lookup = DnsLookupState(name, emptyList(), running = false, verdict = DnsLookup.verdict(emptyList(), refused = true, ipv6 = ipv6)))
            return true
        }
        val done = arrayOfNulls<Check>(ways.size)
        fun show() {
            val checks = ways.mapIndexed { i, (title, _) -> done[i] ?: Check(title, Outcome.Running) }
            val finished = done.all { it != null }
            tools = tools.copy(lookup = DnsLookupState(name, checks, running = !finished, verdict = if (finished) DnsLookup.verdict(checks, refused = false, ipv6 = ipv6) else null))
        }
        show()
        scope.launch {
            coroutineScope {
                ways.forEachIndexed { i, (_, ask) ->
                    launch {
                        done[i] = withContext(Dispatchers.IO) { ask() }
                        show()
                    }
                }
            }
        }
        return true
    }

    /**
     * Site check: [input] tried straight out and through the node in use and the best few others,
     * in a core of KOne's own (the running core keeps every test for Automatic, and a slow site
     * must not move it). Sites the console refuses are never tried. False when it is not a name.
     */
    fun checkSite(input: String): Boolean {
        val site = DnsLookup.clean(input) ?: return false
        if (tools.site?.running == true) return true
        fun settle(verdict: String) {
            tools = tools.copy(site = SiteCheckState(site, emptyList(), running = false, verdict = verdict))
        }
        if (DnsLookup.refused(site, account.binding?.source)) {
            settle("Your provider blocks $site")
            return true
        }
        val core = Places.core
        val source = source()
        if (core == null || source == null) {
            settle(if (core == null) "Site checks need KOne installed" else "Waiting for the console's nodes")
            return true
        }
        val current = inUse()?.takeIf { it != KoneConfig.AUTO_TAG && nodes.any { node -> node.tag == it } }
        val others = nodes.map { it.tag }.filter { it != current }.sortedBy { delays[it] ?: Int.MAX_VALUE }
        val picked = (listOfNotNull(current) + others).take(4)
        val ways = listOf("direct" to "Straight out") + picked.map { it to nameOf(it) }
        tools = tools.copy(site = SiteCheckState(site, ways.map { (_, title) -> Check(title, Outcome.Running) }, running = true))
        scope.launch {
            val tries = withContext(Dispatchers.IO) {
                val api = CoreApi(freePort(), token())
                val config = runCatching { KoneConfig.build(source, rules, api, tun = false, mode = "Global").config.toString() }.getOrNull()
                val tester = Probe(core)
                try {
                    val up = config != null && tester.start(config) && ClashApi(api).let { clash -> (0 until 50).any { clash.answers().also { ok -> if (!ok) Thread.sleep(200) } } }
                    coroutineScope {
                        ways.map { (tag, title) ->
                            async { SiteCheck.Try(tag, title, if (up) ClashApi(api).delay(tag, 8_000, SiteCheck.url(site)) else null) }
                        }.awaitAll()
                    }
                } finally {
                    tester.stop()
                }
            }
            val way = SiteCheck.way(tries.first().millis, tries.drop(1).map { it.millis })
            tools = tools.copy(site = SiteCheckState(site, SiteCheck.rows(tries), running = false, verdict = SiteCheck.verdict(site, way), way = way))
        }
        return true
    }

    /** Keeps what a site check found: the site on Direct sites or Proxied sites (Rules), ahead of the person's own rules. */
    fun keepSite(direct: Boolean) {
        val site = tools.site?.site ?: return
        if (addSite(site, direct)) {
            hint(if (direct) "$site now goes straight out" else "$site now goes through the node")
            tools = tools.copy(site = null)
        }
    }

    fun clearSite() {
        if (tools.site?.running != true) tools = tools.copy(site = null)
    }

    fun clearLookup() {
        if (tools.lookup?.running != true) tools = tools.copy(lookup = null)
    }

    // Backup (Settings)

    /** The person's setup as a backup file's text; never the key or the device's binding. */
    fun backup(): String = Backup(
        mode = preferences.mode, way = preferences.way, bypass = preferences.bypass, direct = preferences.direct, proxied = preferences.proxied,
        rules = preferences.rules, blockAds = preferences.blockAds, remoteDns = preferences.remoteDns, directDns = preferences.directDns,
        ipv6 = preferences.ipv6, failover = preferences.failover, hosts = preferences.hosts, unmatchedDirect = preferences.unmatchedDirect,
        subscriptions = subscriptions.all.map { Triple(it.name, it.url, it.hours) },
        imported = imports.all,
    ).write()

    /**
     * Restores a backup into what is here: every value through its usual checks, lists joined
     * rather than replaced, subscriptions added and fetched, imported nodes checked by sing-box.
     * What happened, in a line; null when [text] is not a KOne backup.
     */
    fun restore(text: String): String? {
        val backup = Backup.read(text) ?: return null
        backup.mode?.takeIf { it in KoneConfig.MODES }?.let { mode ->
            preferences.mode = mode
            tools = tools.copy(mode = mode)
        }
        backup.way?.takeIf { it == WAY_ADAPTER || it == WAY_PROXY }?.let { way ->
            preferences.way = way
            tools = tools.copy(way = way)
        }
        preferences.bypass = (preferences.bypass + backup.bypass).distinctBy { it.lowercase() }
        preferences.direct = (preferences.direct + backup.direct.mapNotNull(KoneConfig::site)).distinct()
        preferences.proxied = (preferences.proxied + backup.proxied.mapNotNull(KoneConfig::site)).filter { it !in preferences.direct }.distinct()
        preferences.rules = preferences.rules + backup.rules
        backup.blockAds?.let { preferences.blockAds = it }
        backup.remoteDns?.let { preferences.remoteDns = it }
        backup.directDns?.let { preferences.directDns = it }
        backup.ipv6?.let { preferences.ipv6 = it }
        backup.failover?.let { preferences.failover = it }
        if (backup.hosts.isNotEmpty()) preferences.hosts = preferences.hosts + backup.hosts
        backup.unmatchedDirect?.let { preferences.unmatchedDirect = it }
        tools = tools.copy(bypass = preferences.bypass)
        rulesPage = rulesNow()
        if (backup.rules.any { it.kind == "set" }) updateLists()
        network = NetworkState(preferences.remoteDns, preferences.directDns, preferences.ipv6, preferences.failover, preferences.hosts)
        val added = backup.subscriptions.mapNotNull { (name, url, hours) ->
            if (!SubscriptionContent.acceptable(url)) return@mapNotNull null
            subscriptions.add(url, name.take(32))?.also { subscriptions.change(it.id) { kept -> kept.copy(hours = hours.coerceIn(0, 720)) } }
        }
        added.forEach { updateSubscription(it.id, first = false) }
        val managed = account.binding?.source
        if (managed != null && backup.imported.isNotEmpty()) {
            scope.launch {
                val kept = withContext(Dispatchers.IO) {
                    val checked = acceptedNodes(managed, backup.imported.map { JsonObject(it - "tag") })
                    imports.addOutbounds(checked.map { node -> node to backup.imported.firstOrNull { JsonObject(it - "tag") == node }?.let { (it["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.content } })
                }
                if (kept > 0) nodesChanged()
            }
        }
        restart()
        return "Backup restored"
    }

    // Network (Settings)

    /** The DNS servers and IPv6, as kept. */
    var network by mutableStateOf(NetworkState(preferences.remoteDns, preferences.directDns, preferences.ipv6, preferences.failover, preferences.hosts))
        private set

    /** Whether a chosen node that stops answering is stood in for; a stand-in in place goes back to the chosen node. */
    fun setFailover(on: Boolean) {
        preferences.failover = on
        network = network.copy(failover = on)
        val api = clash
        val chosen = account.binding?.selected
        if (!on && standIn != null && api != null && chosen != null) {
            standIn = null
            scope.launch(Dispatchers.IO) { api.select(chosen) }
            refresh()
        }
    }

    /** A remote DNS choice; false when it is neither a preset nor a DNS-over-HTTPS address KOne takes. */
    fun setRemoteDns(choice: String): Boolean {
        val clean = Dns.remoteChoice(choice)
        if (clean.isEmpty() && choice.isNotBlank()) return false
        if (clean == preferences.remoteDns) return true
        preferences.remoteDns = clean
        network = network.copy(remoteDns = clean)
        restart()
        return true
    }

    /** A direct DNS choice; false when it is neither a preset nor an address. */
    fun setDirectDns(choice: String): Boolean {
        val clean = Dns.directChoice(choice)
        if (clean.isEmpty() && choice.isNotBlank()) return false
        if (clean == preferences.directDns) return true
        preferences.directDns = clean
        network = network.copy(directDns = clean)
        restart()
        return true
    }

    /** A fixed answer typed as "name address" (or a hosts file's "address name"); false when it is not one. */
    fun addHost(line: String): Boolean {
        val host = Hosts.parse(line) ?: return false
        val before = preferences.hosts
        if (host in before) return true
        if (Hosts.clean(before + host).sumOf { it.v4.size + it.v6.size } == before.size) return false
        preferences.hosts = before + host
        network = network.copy(hosts = preferences.hosts)
        restart()
        return true
    }

    fun removeHost(host: Host) {
        val before = preferences.hosts
        if (host !in before) return
        preferences.hosts = before - host
        network = network.copy(hosts = preferences.hosts)
        restart()
    }

    fun setIpv6(on: Boolean) {
        preferences.ipv6 = on
        network = network.copy(ipv6 = on)
        restart()
    }

    // Rules

    private fun rulesNow(): RulesState = RulesState(
        preferences.bypass, preferences.direct, preferences.proxied, preferences.rules, preferences.blockAds,
        lists = keeper.named().associateWith { keeper.lists.status(it) }, listsBusy = keeper.busy, unmatchedDirect = preferences.unmatchedDirect,
    )

    /** What nothing matched, in Rule mode: straight out ([direct]) or through the node. */
    fun setUnmatched(direct: Boolean) {
        if (preferences.unmatchedDirect == direct) return
        preferences.unmatchedDirect = direct
        rulesPage = rulesNow()
        restart()
    }

    fun setBlockAds(on: Boolean) {
        preferences.blockAds = on
        rulesPage = rulesNow()
        restart()
    }

    /** [rule] at the end, its value written as the template writes it; false when it does not fit its kind. */
    fun addRule(rule: UserRule): Boolean {
        val value = UserRules.value(rule.kind, rule.value) ?: return false
        if (rule.action !in UserRules.ACTIONS) return false
        val before = preferences.rules
        if (before.size >= UserRules.MOST) return false
        val added = UserRule(rule.kind, value, rule.action)
        if (added.kind == "set" && value !in keeper.named() && keeper.named().size >= UserRules.MOST_SETS) return false
        preferences.rules = before + added
        if (preferences.rules != before) {
            rulesPage = rulesNow()
            // A list not kept yet is downloaded first; the connection starts afresh once it is ready.
            if (added.kind == "set" && keeper.lists.digest(value) == null) updateLists() else restart()
        }
        return true
    }

    // Category lists (Rules): see ListKeeper.

    fun updateLists(force: Boolean = false) = keeper.update(force)

    fun removeRule(index: Int) {
        val rules = preferences.rules
        if (index !in rules.indices) return
        preferences.rules = rules.filterIndexed { i, _ -> i != index }
        rulesPage = rulesNow()
        restart()
    }

    /** The rule at [index] one place earlier ([up]) or later; order decides which matches first. */
    fun moveRule(index: Int, up: Boolean) {
        val rules = preferences.rules.toMutableList()
        val other = if (up) index - 1 else index + 1
        if (index !in rules.indices || other !in rules.indices) return
        rules[index] = rules[other].also { rules[other] = rules[index] }
        preferences.rules = rules
        rulesPage = rulesNow()
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
                carried?.let(::count)
                // Why each went its way, in the person's terms.
                val lists = Why.Lists(preferences.bypass, preferences.direct, preferences.proxied)
                tools = tools.copy(carried = carried?.let { it.copy(connections = it.connections.map { one -> one.copy(why = Why.explain(one.rule, lists)) }) })
                delay(1000)
            }
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
            if (watching == null) withContext(Dispatchers.IO) { api.connections() }?.let(::count)
        }
    }

    private fun count(carried: Carried) {
        traffic.sample(carried.connections, ::nameOf)
        tools.details?.let { tools = tools.copy(details = details(it.span)) }
        val now = System.currentTimeMillis()
        watched.sample(carried.connections, now)
        // Passive health: stalling connections through the node in use count against it, and it is tested now.
        inUse()?.let { tag ->
            if (watched.stalling(tag, now) && now - nudgedAt >= 20_000) {
                nudgedAt = now
                health.record(tag, null)
                log("Connections through ${nameOf(tag)} are stalling; testing it now")
                nudges.trySend(Unit)
            }
        }
        // Sites failing straight out, in Rule mode, where sending them through the node is the person's to choose.
        val advice = if (preferences.mode == "Rule") watched.advice().take(3) else emptyList()
        advice.filter { advised.add(it.site) }.forEach { onNotice("${it.site} does not answer straight out; Tools can send it through the node") }
        if (advice != tools.siteAdvice) tools = tools.copy(siteAdvice = advice)
    }

    /** A connection's site sent the other way from now on: on Direct sites (every mode) or Proxied sites (Rule mode). */
    fun sendSite(site: String, direct: Boolean) {
        if (addSite(site, direct)) hint(if (direct) "$site now goes straight out" else "$site now goes through the node")
    }

    /** Takes an advice: the site goes on Proxied sites (Rules), so Rule mode sends it through the node. */
    fun takeAdvice(site: String) {
        watched.dismiss(site)
        if (addSite(site, direct = false)) hint("$site now goes through the node")
        tools = tools.copy(siteAdvice = tools.siteAdvice.filter { it.site != site })
    }

    fun dismissAdvice(site: String) {
        watched.dismiss(site)
        tools = tools.copy(siteAdvice = tools.siteAdvice.filter { it.site != site })
    }

    /** Usage by node, by program and by day over [span] days, or put away for null. */
    fun showUsage(span: Int?) {
        tools = tools.copy(details = span?.takeIf { it in setOf(1, 7, 30) }?.let(::details))
    }

    private fun details(span: Int) = UsageDetails(span, traffic.top(TrafficBy.Node, span), traffic.top(TrafficBy.App, span), traffic.daily(span))

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
                        trail.addLast(down to up)
                        while (trail.size > 60) trail.removeFirst()
                        tools = tools.copy(today = usage.today(), month = usage.month())
                        refresh()
                    }
                }
            }
            launch { while (isActive && clash === api) { delay(1000); seconds++; refresh() } }
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
            }
            // Every 15 seconds, or at once when real traffic through the node in use stalls.
            withTimeoutOrNull(15_000) { nudges.receive() }
        }
    }

    /** What real traffic says about sites and nodes (see ConnectionWatch). */
    private val watched = ConnectionWatch()

    /** Wakes the watchdog early: the node in use is stalling. */
    private val nudges = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var nudgedAt = 0L
    private val advised = HashSet<String>()

    private suspend fun steerAutomatic(api: ClashApi) {
        val current = drivenPick ?: withContext(Dispatchers.IO) { api.now(KoneConfig.AUTO_TAG) }
        if (current != null) testOne(api, current)
        if (clash !== api || account.binding?.selected != KoneConfig.AUTO_TAG) return
        val next = steer.next(current, nodes.map { it.tag })
        if (next != null && withContext(Dispatchers.IO) { api.select(next) }) {
            if (current != null && health.failing(current) >= Health.FAILED) closeVia(api, current)
            drivenPick = next
            log("Automatic: ${nameOf(next)}")
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
            log("Automatic in $region: ${nameOf(next)}")
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
                tellConnection("Back on ${nameOf(chosen)}")
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

    private fun nameOf(tag: String) = nodes.firstOrNull { it.tag == tag }?.name ?: tag

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

    /** Tests every node through [api] now and then each minute, while it is the one in use. */
    private suspend fun testDelays(api: ClashApi) {
        while (clash === api) {
            measure(api) { clash === api }
            delay(testEvery())
        }
    }

    /** While not connected, the core without TUN carries the nodes so their delays can be tested. */
    private fun startProbe() {
        val probe = probe ?: return
        val source = source() ?: return
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
                measure(clash) { probeApi === api }
                delay(testEvery())
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
        val groups = listOf(imports.all) + subscriptions.all.map { it.nodes }
        return sequenceOf(groups, listOf(imports.all) + subscriptions.all.map { emptyList() }, groups.map { emptyList() })
            .map { Merge.into(managed, it) }
            .firstOrNull { builds(it.source) } ?: Merge.into(managed, groups.map { emptyList() })
    }

    private fun builds(source: JsonObject) = runCatching { KoneConfig.build(source, rules, CoreApi(9090, "list")) }.isSuccess

    private fun source(): JsonObject? = merged()?.source

    /** The nodes [source] carries, by tag. */
    private fun tagsOf(source: JsonObject): Set<String> =
        runCatching { KoneConfig.build(source, rules, CoreApi(9090, "list")).nodes.map { it.tag }.toSet() }.getOrDefault(emptySet())

    private fun loadNodes() {
        val binding = account.binding
        val merged = merged()
        importedOrder = merged?.groups?.firstOrNull().orEmpty()
        importedTags = importedOrder.toSet()
        subscribed = merged?.let { all -> subscriptions.all.zip(all.groups.drop(1)).flatMap { (subscription, tags) -> tags.map { it to subscription } }.toMap() }.orEmpty()
        nodes = merged?.source?.let { runCatching { KoneConfig.build(it, rules, CoreApi(9090, "list")).nodes }.getOrNull() }.orEmpty()
        health.forget(nodes.mapTo(HashSet()) { it.tag })
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
                            subscription.info, subscription.updated, subscription.error, subscription.hours, subscription.url, subscription.id in updating,
                        ),
                    )
                }
            },
            editing = editing,
            qr = qr,
            speedTesting = speedTesting,
            selected = selected,
            testing = testing,
            notice = nodesNotice?.first,
            noticeIsError = nodesNotice?.second == true,
        )
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
