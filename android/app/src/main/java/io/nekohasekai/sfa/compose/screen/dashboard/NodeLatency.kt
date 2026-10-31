package io.nekohasekai.sfa.compose.screen.dashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal data class NodeEndpoint(val host: String, val port: Int)

/** The addresses a handshake tries in turn: the first IPv4 address, then the first IPv6 one. */
internal fun dialOrder(addresses: List<InetAddress>): List<InetAddress> =
    listOfNotNull(addresses.firstOrNull { it is Inet4Address }, addresses.firstOrNull { it is Inet6Address })

/** Finds the server a profile actually dials, without starting the core. */
internal object NodeEndpoints {
    private val tcpTypes = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http", "ssh", "naive", "anytls", "shadowtls")
    private val udpTypes = setOf("hysteria", "hysteria2", "tuic")

    /**
     * Follows route.final (or the first outbound) through selector defaults to a proxy.
     * QUIC-based proxies return null: a TCP handshake says nothing about them.
     */
    fun fromConfig(content: String): NodeEndpoint? {
        val config = runCatching { JSONObject(content) }.getOrNull() ?: return null
        val list = config.optJSONArray("outbounds") ?: return null
        val outbounds = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        val byTag = outbounds.associateBy { it.optString("tag") }
        fun resolve(tag: String?, depth: Int): JSONObject? {
            val outbound = tag?.let { byTag[it] } ?: return null
            return when (outbound.optString("type")) {
                in tcpTypes, in udpTypes -> outbound
                "selector", "urltest" -> if (depth >= 4) {
                    null
                } else {
                    val members = outbound.optJSONArray("outbounds")
                    resolve(outbound.optString("default").ifBlank { members?.optString(0) }, depth + 1)
                }
                else -> null
            }
        }
        val start = config.optJSONObject("route")?.optString("final")?.ifBlank { null } ?: outbounds.firstOrNull()?.optString("tag")
        val chosen = resolve(start, 0) ?: outbounds.firstOrNull { it.optString("type") in tcpTypes } ?: return null
        if (chosen.optString("type") !in tcpTypes) return null
        val host = chosen.optString("server").trim().ifBlank { return null }
        val port = chosen.optInt("server_port", 0).takeIf { it in 1..65535 } ?: return null
        return NodeEndpoint(host, port)
    }
}

/** What makes two nodes one node: the settings of their proxies, with names and tags left aside. */
internal object NodeFingerprints {
    private val notProxies = setOf("direct", "block", "dns", "selector", "urltest")
    private val cache = ConcurrentHashMap<Long, Pair<Long, String?>>()

    /** A digest of every proxy in [content], the same for two configs that differ only in names; null when it has none. */
    fun of(content: String): String? {
        val config = runCatching { JSONObject(content) }.getOrNull() ?: return null
        val proxies = listOfNotNull(config.optJSONArray("outbounds"), config.optJSONArray("endpoints")).flatMap { list ->
            (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        }.filter { it.optString("type").let { type -> type.isNotEmpty() && type !in notProxies } }
        if (proxies.isEmpty()) return null
        val canonical = proxies.map { canonical(it, top = true) }.sorted().joinToString("\n")
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** Off the main thread. A profile's fingerprint, read again only when its file changes. */
    fun of(profile: Profile): String? {
        val file = File(profile.typed.path)
        val modified = file.lastModified()
        cache[profile.id]?.takeIf { it.first == modified }?.let { return it.second }
        val print = runCatching { of(file.readText()) }.getOrNull()
        cache[profile.id] = modified to print
        return print
    }

    private fun canonical(value: Any?, top: Boolean = false): String = when (value) {
        is JSONObject -> value.keys().asSequence().filterNot { top && it == "tag" }.sorted()
            .joinToString(",", "{", "}") { "${JSONObject.quote(it)}:${canonical(value.opt(it))}" }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.opt(it)) }
        is String -> JSONObject.quote(value)
        null, JSONObject.NULL -> "null"
        else -> value.toString()
    }
}

internal enum class NodeHealth { Good, Slow, Down }

/**
 * A node's latest result: a latency, or with [failed] the reason it failed. Rows show a
 * failure as a dot alone; the reason appears in the connection details.
 */
internal data class NodeReading(val label: String, val health: NodeHealth, val failed: Boolean = false) {
    /** The latency in milliseconds, or null for a failure. */
    val ms: Int? get() = if (failed) null else label.removeSuffix(" ms").toIntOrNull()
}

/** One measurement: [ms] on success, otherwise the [failure] in words. */
internal data class LatencySample(val ms: Int?, val failure: String? = null)

/** Turns the last few measurements into a health verdict. Pure, so it is unit tested. */
internal object NodeHealthRules {
    const val WINDOW = 5

    // A TCP handshake is one round trip; the core's URL test is a whole HTTPS request
    // through the proxy, so it gets its own, wider limits.
    // Cross-ocean round trips of 300 ms are normal on mobile data; slow starts above that.
    const val TCP_SLOW_MS = 400
    const val TCP_JITTER_MS = 150
    const val PROXY_SLOW_MS = 1_000
    const val PROXY_JITTER_MS = 400

    /**
     * Two failures in a row are [NodeHealth.Down]; one failure, a slow reply or a jumpy
     * recent history is [NodeHealth.Slow]; anything else is [NodeHealth.Good].
     */
    fun judge(history: List<LatencySample>, slowMs: Int, jitterMs: Int): NodeReading? {
        val latest = history.lastOrNull() ?: return null
        if (latest.ms == null) {
            val previous = history.getOrNull(history.size - 2)
            val down = previous != null && previous.ms == null
            return NodeReading(latest.failure ?: "Timed out", if (down) NodeHealth.Down else NodeHealth.Slow, failed = true)
        }
        val replies = history.mapNotNull { it.ms }
        val unsteady = history.any { it.ms == null } || (replies.size >= 3 && replies.max() - replies.min() > jitterMs)
        val health = if (latest.ms >= slowMs || unsteady) NodeHealth.Slow else NodeHealth.Good
        return NodeReading("${latest.ms} ms", health)
    }

    /**
     * A reading outside the App, in the notification and on the quick-settings tile, whose
     * seal picture already shows the weather: the latency when all is well, the weather's word
     * before it when slow, and the word alone when it failed (the dashboard says why).
     */
    fun badge(reading: NodeReading): String {
        val word = NodeWeather.of(reading.health).word
        return when {
            reading.failed -> word
            reading.health == NodeHealth.Good -> reading.label
            else -> "$word · ${reading.label}"
        }
    }
}

/**
 * Live node health. Every node gets a TCP handshake to its server, measured on the
 * physical network so it never loops through this app's own VPN. While connected, the
 * node in use is instead judged by the core's URL test through the proxy, which also
 * catches a wrong key or a blocked TLS handshake.
 */
internal object NodeLatency {
    const val INTERVAL_MS = 6_000L
    private const val CONNECT_TIMEOUT_MS = 2_500

    /** Up to this many nodes are all checked by themselves; beyond it, only the rows on screen and the node chosen. */
    const val AUTO_ALL = 24

    // Test all: this many handshakes at once, each started a beat after the last.
    private const val SWEEP_PARALLEL = 8
    private const val SWEEP_GAP_MS = 25L
    private const val TAG = "NodeLatency"

    // Each node's reading is a state of its own, so a result recomposes only the rows showing that
    // node. A row whose node has no state yet watches [added], which moves when states appear.
    private val readings = ConcurrentHashMap<Long, MutableState<NodeReading?>>()
    private val added = mutableIntStateOf(0)
    private val tcpHistory = ConcurrentHashMap<Long, List<LatencySample>>()
    private val proxyHistory = ConcurrentHashMap<Long, List<LatencySample>>()
    private val endpoints = ConcurrentHashMap<Long, Pair<Long, NodeEndpoint?>>()

    /** How many times each node's row is on screen. */
    private val shown = ConcurrentHashMap<Long, Int>()
    private val sweeps = AtomicInteger(0)
    private val rounds = mutableIntStateOf(0)

    /** Moves each time a check of the list finishes, by itself or from Test all. */
    val round: Int get() = rounds.intValue

    /** Main thread. A node's row came on screen. */
    fun show(profileId: Long) {
        shown.merge(profileId, 1, Int::plus)
    }

    /** Main thread. A node's row left the screen. */
    fun hide(profileId: Long) {
        shown.computeIfPresent(profileId) { _, count -> (count - 1).takeIf { it > 0 } }
    }

    /**
     * The nodes the regular check covers: all of a short list; of a long one only the rows on
     * screen and [focus], so hundreds of nodes don't each take a handshake every few seconds.
     */
    internal fun due(ids: List<Long>, onScreen: Set<Long>, focus: Set<Long>): Set<Long> =
        if (ids.size <= AUTO_ALL) ids.toSet() else ids.filterTo(LinkedHashSet()) { it in onScreen || it in focus }

    /** The node whose reading comes from the proxy test; null when disconnected. */
    @Volatile
    private var proxied: Long? = null

    operator fun get(profileId: Long): NodeReading? = readings[profileId]?.value ?: run {
        added.intValue
        null
    }

    fun forget(profileId: Long) {
        drop(profileId)
        tcpHistory.remove(profileId)
        proxyHistory.remove(profileId)
        endpoints.remove(profileId)
    }

    /** Main thread. Switches [profileId]'s reading to the proxy test, or back to TCP with null. */
    fun useProxyReadings(profileId: Long?) {
        val previous = proxied
        if (previous == profileId) return
        proxied = profileId
        previous?.let { proxyHistory.remove(it); publish(it) }
        profileId?.let { publish(it) }
    }

    /** Main thread. One result of the core's URL test for the node in use. */
    fun recordProxy(profileId: Long, sample: LatencySample) {
        if (proxied != profileId) return
        proxyHistory[profileId] = (proxyHistory[profileId].orEmpty() + sample).takeLast(NodeHealthRules.WINDOW)
        publish(profileId)
    }

    suspend fun refresh(context: Context, profiles: List<Profile>): Unit = coroutineScope {
        withContext(Dispatchers.Main) { watch(profiles.map { it.id }) }
        // Test all is checking the list already.
        if (sweeps.get() > 0) return@coroutineScope
        val focus = withContext(Dispatchers.IO) { setOfNotNull(Settings.selectedProfile, proxied) }
        val due = due(profiles.map { it.id }, shown.keys, focus)
        val network = physicalNetwork(context)
        val over = network?.let { context.getSystemService(ConnectivityManager::class.java)?.getNetworkCapabilities(it) }?.let {
            if (it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "Wi-Fi" else if (it.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "mobile data" else "another network"
        } ?: "the default route"
        val gate = Semaphore(6)
        profiles.filter { it.id in due }.map { profile ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    val endpoint = endpointOf(profile)
                    val sample = endpoint?.let { measure(it, network) }
                    // A changed failure is logged once, without the server, so a phone check has a lead.
                    if (sample?.failure != null && tcpHistory[profile.id]?.lastOrNull()?.failure != sample.failure) {
                        Log.w(TAG, "Node ${profile.id} handshake: ${sample.failure} over $over")
                    }
                    withContext(Dispatchers.Main) { recordTcp(profile.id, sample) }
                }
            }
        }.awaitAll()
        val alive = profiles.map { it.id }.toSet()
        withContext(Dispatchers.Main) {
            (readings.keys - alive).forEach(::drop)
            tcpHistory.keys.retainAll(alive)
            proxyHistory.keys.retainAll(alive)
            rounds.intValue++
        }
        endpoints.keys.retainAll(alive)
    }

    /**
     * Test all: [profiles] checked now, in order, a few at a time and a beat apart, so hundreds of
     * nodes never open hundreds of sockets at once. Each result lands in its row as it comes, and
     * [onProgress] has how many are done. Cancelled, results still in flight are dropped.
     */
    suspend fun sweep(context: Context, profiles: List<Profile>, onProgress: (Int) -> Unit) {
        sweeps.incrementAndGet()
        try {
            coroutineScope {
                withContext(Dispatchers.Main) { watch(profiles.map { it.id }) }
                val network = physicalNetwork(context)
                val gate = Semaphore(SWEEP_PARALLEL)
                var done = 0
                for (profile in profiles) {
                    gate.acquire()
                    launch(Dispatchers.IO) {
                        try {
                            val sample = endpointOf(profile)?.let { measure(it, network) }
                            withContext(Dispatchers.Main) {
                                recordTcp(profile.id, sample)
                                onProgress(++done)
                            }
                        } finally {
                            gate.release()
                        }
                    }
                    delay(SWEEP_GAP_MS)
                }
            }
            withContext(Dispatchers.Main) { rounds.intValue++ }
        } finally {
            sweeps.decrementAndGet()
        }
    }

    /** Main thread. One handshake result for [profileId]; null when its server cannot be told. */
    internal fun recordTcp(profileId: Long, sample: LatencySample?) {
        if (sample == null) {
            tcpHistory.remove(profileId)
        } else {
            tcpHistory[profileId] = (tcpHistory[profileId].orEmpty() + sample).takeLast(NodeHealthRules.WINDOW)
        }
        publish(profileId)
    }

    private fun publish(profileId: Long) {
        val proxy = proxyHistory[profileId].takeIf { proxied == profileId && !it.isNullOrEmpty() }
        val reading = if (proxy != null) {
            NodeHealthRules.judge(proxy, NodeHealthRules.PROXY_SLOW_MS, NodeHealthRules.PROXY_JITTER_MS)
        } else {
            NodeHealthRules.judge(tcpHistory[profileId].orEmpty(), NodeHealthRules.TCP_SLOW_MS, NodeHealthRules.TCP_JITTER_MS)
        }
        val slot = readings[profileId]
        when {
            slot != null -> slot.value = reading
            reading != null -> {
                readings[profileId] = mutableStateOf(reading)
                added.intValue++
            }
        }
    }

    /** Main thread. Gives every node of a list its state at once, so the first sweep wakes each row once. */
    internal fun watch(profileIds: Collection<Long>) {
        var fresh = false
        for (id in profileIds) {
            if (readings.containsKey(id)) continue
            readings[id] = mutableStateOf(null)
            fresh = true
        }
        if (fresh) added.intValue++
    }

    private fun drop(profileId: Long) {
        readings.remove(profileId)?.value = null
    }

    private fun endpointOf(profile: Profile): NodeEndpoint? {
        val file = File(profile.typed.path)
        val modified = file.lastModified()
        endpoints[profile.id]?.takeIf { it.first == modified }?.let { return it.second }
        val endpoint = runCatching { NodeEndpoints.fromConfig(file.readText()) }.getOrNull()
        endpoints[profile.id] = modified to endpoint
        return endpoint
    }

    /**
     * A handshake with [endpoint], tried on an IPv4 address first and then on an IPv6 one, as the
     * core reaches a paired node (its DNS is ipv4_only). Only the first address the system gave
     * used to be tried; on a dual-stack mobile network that is usually IPv6, so a server answering
     * on IPv4 alone looked down. The latency is the attempt that connected; a failure names the first.
     */
    internal fun measure(
        endpoint: NodeEndpoint,
        network: Network?,
        resolve: (String) -> Array<InetAddress> = { host -> network?.getAllByName(host) ?: InetAddress.getAllByName(host) },
    ): LatencySample {
        val addresses = try {
            dialOrder(resolve(endpoint.host).toList())
        } catch (_: UnknownHostException) {
            return LatencySample(null, if (network == null) "No network" else "Resolution failed")
        } catch (_: Exception) {
            return LatencySample(null, "Connection failed")
        }
        var failure = LatencySample(null, "Resolution failed")
        addresses.forEachIndexed { i, address ->
            val sample = connect(address, endpoint.port, network)
            if (sample.ms != null) return sample
            if (i == 0) failure = sample
        }
        return failure
    }

    private fun connect(address: InetAddress, port: Int, network: Network?): LatencySample = try {
        Socket().use { socket ->
            network?.bindSocket(socket)
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
            LatencySample(((System.nanoTime() - started) / 1_000_000).toInt().coerceAtLeast(1))
        }
    } catch (_: SocketTimeoutException) {
        LatencySample(null, "Timed out")
    } catch (_: UnknownHostException) {
        LatencySample(null, if (network == null) "No network" else "Resolution failed")
    } catch (e: ConnectException) {
        LatencySample(null, if (e.message?.contains("ENETUNREACH") == true) "No network" else "Connection refused")
    } catch (_: Exception) {
        LatencySample(null, "Connection failed")
    }

    /** Whether the network under the VPN is charged by the byte, mobile data above all. */
    fun metered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = physicalNetwork(context) ?: return false
        return manager.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true
    }

    /** Same interval on Wi-Fi and mobile data. */
    fun gap(): Long = INTERVAL_MS / 1_000

    /** A validated non-VPN network with internet, Wi-Fi first; null falls back to the default route. */
    @Suppress("DEPRECATION")
    private fun physicalNetwork(context: Context): Network? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = manager.allNetworks.mapNotNull { network ->
            manager.getNetworkCapabilities(network)?.let { network to it }
        }.filter { (_, caps) ->
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        }
        return candidates.sortedWith(
            compareByDescending<Pair<Network, NetworkCapabilities>> { it.second.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
                .thenByDescending { it.second.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) },
        ).firstOrNull()?.first
    }
}
