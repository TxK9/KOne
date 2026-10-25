package io.nekohasekai.sfa.compose.screen.devices

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sfa.devices.Availability
import io.nekohasekai.sfa.devices.DeviceConnection
import io.nekohasekai.sfa.devices.DeviceDescriptor
import io.nekohasekai.sfa.devices.DeviceInfo
import io.nekohasekai.sfa.devices.DeviceIntegrations
import io.nekohasekai.sfa.devices.DeviceResult
import io.nekohasekai.sfa.devices.DeviceRole
import io.nekohasekai.sfa.devices.EngineAdapter
import io.nekohasekai.sfa.devices.EngineConfigSummary
import io.nekohasekai.sfa.devices.EngineFeature
import io.nekohasekai.sfa.devices.EngineKind
import io.nekohasekai.sfa.devices.EngineLogLine
import io.nekohasekai.sfa.devices.EngineStatus
import io.nekohasekai.sfa.devices.LinkState
import io.nekohasekai.sfa.devices.LogSource
import io.nekohasekai.sfa.devices.NodeGroup
import io.nekohasekai.sfa.devices.ProxyMode
import io.nekohasekai.sfa.devices.RoutingTable
import io.nekohasekai.sfa.devices.ThisDevice
import io.nekohasekai.sfa.devices.TrafficSnapshot
import io.nekohasekai.sfa.devices.combinedTraffic
import io.nekohasekai.sfa.devices.engineFeatures
import io.nekohasekai.sfa.devices.map
import io.nekohasekai.sfa.devices.mergedWith
import io.nekohasekai.sfa.devices.valueOrNull
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One engine as the device page lists it. */
internal data class EngineRowState(
    val kind: EngineKind,
    val availability: Availability,
    val status: EngineStatus? = null,
    /** The engine's latest measured sample; only this makes anything move along its spoke. */
    val traffic: TrafficSnapshot? = null,
)

/** Everything the device page shows for the selected device. Unmeasured values stay null. */
internal data class DeviceOverviewState(
    val device: DeviceDescriptor,
    val info: DeviceInfo?,
    /** NotConnected while no integration claims the device. */
    val integration: Availability,
    val link: LinkState,
    val engines: List<EngineRowState>,
    val traffic: TrafficSnapshot? = null,
    /** Measured samples, oldest first, for the waterline. */
    val history: List<TrafficSnapshot> = emptyList(),
    val trafficAvailability: Availability = integration,
    val configuration: EngineConfigSummary? = null,
    val configurationAvailability: Availability = integration,
) {
    companion object {
        fun notConnected(device: DeviceDescriptor, info: DeviceInfo?) = DeviceOverviewState(
            device = device,
            info = info,
            integration = Availability.NotConnected,
            link = LinkState.NotConnected,
            engines = EngineKind.entries.map { EngineRowState(it, Availability.NotConnected) },
        )
    }
}

/** What an engine reported the last time it was asked. */
internal data class EngineSnapshot(
    val mode: ProxyMode? = null,
    val configuration: EngineConfigSummary? = null,
    val groups: List<NodeGroup>? = null,
    val routing: RoutingTable? = null,
)

/** A request in flight, and the last request's problem in the integration's terms. */
internal data class EngineActivity(
    val busy: Boolean = false,
    val problem: DeviceResult<Nothing>? = null,
)

internal data class EnginePageState(
    val kind: EngineKind,
    val features: Map<EngineFeature, Availability>,
    val status: EngineStatus? = null,
    val supportedModes: Set<ProxyMode> = emptySet(),
    val snapshot: EngineSnapshot = EngineSnapshot(),
    val traffic: TrafficSnapshot? = null,
    val history: List<TrafficSnapshot> = emptyList(),
    val logs: List<EngineLogLine> = emptyList(),
    val activity: EngineActivity = EngineActivity(),
) {
    fun availability(feature: EngineFeature): Availability = features[feature] ?: Availability.NotConnected

    /** The engine as a whole: not connected, not driven by this integration, or ready. */
    val reach: Availability get() = availability(EngineFeature.Status)

    /** The mode the engine reports live, else the one it answered when last asked. */
    val mode: ProxyMode? get() = status?.mode ?: snapshot.mode
}

/**
 * Device management's state, shared by the device page and its engine pages. It reads devices and
 * engines only through [DeviceIntegrations]; with nothing registered every surface says so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class DeviceManagementViewModel(private val savedState: SavedStateHandle) : ViewModel() {
    private val registry = DeviceIntegrations.registry
    private val thisDevice = ThisDevice.descriptor()
    private val thisInfo = ThisDevice.info()

    val devices: StateFlow<List<DeviceDescriptor>> = registry.discovered()
        .map { found -> listOf(thisDevice) + found.filterNot { it.id == thisDevice.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), listOf(thisDevice))

    /** Whether any integration can look for other devices. */
    val discovery: StateFlow<Availability> = registry.integrations
        .map { list -> if (list.any { it.discovery != null }) Availability.Ready else Availability.NotConnected }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), Availability.NotConnected)

    private val selectedId = savedState.getStateFlow(KEY_DEVICE, thisDevice.id)

    /** What the orbit has chosen: an engine, or null for the device itself. */
    val selection: StateFlow<EngineKind?> = savedState.getStateFlow<String?>(KEY_ENGINE, null)
        .map { id -> id?.let(EngineKind::fromId) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, savedState.get<String>(KEY_ENGINE)?.let(EngineKind::fromId))

    private val device: Flow<DeviceDescriptor> = combine(selectedId, devices) { id, list -> list.firstOrNull { it.id == id } ?: thisDevice }
        .distinctUntilChanged()

    private val linkProblem = MutableStateFlow<DeviceResult<Nothing>?>(null)

    /** A problem the last attempt to link the selected device reported. */
    val deviceProblem: StateFlow<DeviceResult<Nothing>?> = linkProblem.asStateFlow()

    /** The open connection to the selected device, or null while no integration claims it. */
    private val connection: StateFlow<DeviceConnection?> = combine(device, registry.integrations) { selected, _ -> selected }
        .flatMapLatest(::linkTo)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), null)

    val overview: StateFlow<DeviceOverviewState> = combine(device, connection) { selected, open -> selected to open }
        .flatMapLatest { (selected, open) -> overviewOf(selected, open) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), DeviceOverviewState.notConnected(thisDevice, thisInfo))

    private val snapshots = MutableStateFlow<Map<EngineKind, EngineSnapshot>>(emptyMap())
    private val activity = MutableStateFlow<Map<EngineKind, EngineActivity>>(emptyMap())
    private val enginePages = mutableMapOf<EngineKind, StateFlow<EnginePageState>>()

    fun select(device: DeviceDescriptor) {
        savedState[KEY_DEVICE] = device.id
    }

    fun choose(kind: EngineKind?) {
        savedState[KEY_ENGINE] = kind?.id
    }

    fun engine(kind: EngineKind): StateFlow<EnginePageState> = enginePages.getOrPut(kind) {
        connection
            .flatMapLatest { open -> engineOf(kind, open) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_AFTER), EnginePageState(kind, engineFeatures(false, null)))
    }

    fun setEnabled(kind: EngineKind, enabled: Boolean) = act(kind) { it.control?.setEnabled(enabled) }

    fun start(kind: EngineKind) = act(kind) { it.control?.start() }

    fun stop(kind: EngineKind) = act(kind) { it.control?.stop() }

    fun restart(kind: EngineKind) = act(kind) { it.control?.restart() }

    fun selectMode(kind: EngineKind, mode: ProxyMode) = act(kind) { it.modes?.select(mode) }

    fun selectNode(kind: EngineKind, groupId: String, nodeId: String) = act(kind) { it.nodes?.select(groupId, nodeId) }

    fun measure(kind: EngineKind, groupId: String) = act(kind) { adapter -> adapter.nodes?.measure(groupId)?.map { } }

    fun refresh(kind: EngineKind) {
        connection.value?.engine(kind)?.let { refresh(kind, it) }
    }

    fun dismissProblem(kind: EngineKind) {
        activity.update { it + (kind to (it[kind] ?: EngineActivity()).copy(problem = null)) }
    }

    // ---- Linking ----------------------------------------------------------------------------

    private fun linkTo(device: DeviceDescriptor): Flow<DeviceConnection?> = flow {
        linkProblem.value = null
        val connector = registry.connectorFor(device)
        if (connector == null) {
            emit(null)
            return@flow
        }
        val open = connector.open(device)
        try {
            emit(open)
            linkProblem.value = open.connect().problem()
            awaitCancellation()
        } finally {
            withContext(NonCancellable) { open.disconnect() }
        }
    }

    private fun overviewOf(device: DeviceDescriptor, open: DeviceConnection?): Flow<DeviceOverviewState> {
        val known = thisInfo.takeIf { device.role == DeviceRole.ThisDevice }
        if (open == null) return flowOf(DeviceOverviewState.notConnected(device, known))
        val adapters = EngineKind.entries.map { kind -> kind to open.engine(kind) }
        val statuses = combine(adapters.map { (_, adapter) -> adapter?.status?.status()?.orNullFirst() ?: flowOf(null) }) { it.toList() }
        val hasTraffic = adapters.any { (_, adapter) -> adapter?.traffic != null }
        // Each engine's latest sample, in engine order, and the device's sum with its history.
        val traffic = combine(adapters.map { (_, adapter) -> adapter?.traffic?.traffic()?.orNullFirst() ?: flowOf(null) }) { it.toList() }
            .scan(TrafficState()) { state, latest ->
                val sum = combinedTraffic(latest.filterNotNull())
                TrafficState(latest, sum, if (sum == null || sum.sampledAt == state.history.lastOrNull()?.sampledAt) state.history else (state.history + sum).takeLast(TRAFFIC_SLOTS))
            }
        val info = flow {
            emit(known)
            emit(known.mergedWith(open.info().valueOrNull()))
        }
        val configuration = flow {
            emit(null)
            emit(adapters.firstNotNullOfOrNull { (_, adapter) -> adapter?.configuration?.summary()?.valueOrNull() })
        }
        val hasConfiguration = adapters.any { (_, adapter) -> adapter?.configuration != null }
        return combine(open.link, statuses, traffic, info, configuration) { link, states, measured, facts, summary ->
            DeviceOverviewState(
                device = device,
                info = facts,
                integration = Availability.Ready,
                link = link,
                engines = adapters.mapIndexed { index, (kind, adapter) ->
                    EngineRowState(kind, if (adapter == null) Availability.Unsupported else Availability.Ready, states[index], measured.latest.getOrNull(index))
                },
                traffic = measured.sum,
                history = measured.history,
                trafficAvailability = if (hasTraffic) Availability.Ready else Availability.Unsupported,
                configuration = summary,
                configurationAvailability = if (hasConfiguration) Availability.Ready else Availability.Unsupported,
            )
        }
    }

    // ---- Engines ----------------------------------------------------------------------------

    private fun engineOf(kind: EngineKind, open: DeviceConnection?): Flow<EnginePageState> {
        val adapter = open?.engine(kind)
        val features = engineFeatures(open != null, adapter)
        val doing = activity.map { it[kind] ?: EngineActivity() }
        if (adapter == null) return doing.map { EnginePageState(kind, features, activity = it) }
        val status = adapter.status.status().orNullFirst()
        val traffic = (adapter.traffic?.traffic() ?: flowOf()).scan(TrafficState()) { state, sample ->
            TrafficState(sum = sample, history = (state.history + sample).takeLast(TRAFFIC_SLOTS))
        }
        val logs = adapter.logs?.let(::logLines) ?: flowOf(emptyList())
        val snapshot = snapshots.map { it[kind] ?: EngineSnapshot() }
        return combine(status, traffic, logs, snapshot, doing) { reported, measured, lines, answered, request ->
            EnginePageState(
                kind = kind,
                features = features,
                status = reported,
                supportedModes = adapter.modes?.supported.orEmpty(),
                snapshot = answered,
                traffic = measured.sum,
                history = measured.history,
                logs = lines,
                activity = request,
            )
        }.onStart { refresh(kind, adapter) }
    }

    private fun refresh(kind: EngineKind, adapter: EngineAdapter) {
        viewModelScope.launch {
            val answered = EngineSnapshot(
                mode = adapter.modes?.current()?.valueOrNull(),
                configuration = adapter.configuration?.summary()?.valueOrNull(),
                groups = adapter.nodes?.groups()?.valueOrNull(),
                routing = adapter.routing?.rules()?.valueOrNull(),
            )
            snapshots.update { it + (kind to answered) }
        }
    }

    private fun logLines(source: LogSource): Flow<List<EngineLogLine>> = flow {
        var lines = source.recent(LOG_LINES).valueOrNull().orEmpty().takeLast(LOG_LINES)
        emit(lines)
        source.follow().collect { line ->
            lines = (lines + line).takeLast(LOG_LINES)
            emit(lines)
        }
    }

    /** Runs one request against the engine; the engine's own status, not the call, shows the result. */
    private fun act(kind: EngineKind, call: suspend (EngineAdapter) -> DeviceResult<*>?) {
        val adapter = connection.value?.engine(kind)
        if (adapter == null) {
            activity.update { it + (kind to EngineActivity(problem = if (connection.value == null) DeviceResult.NotConnected else DeviceResult.Unsupported)) }
            return
        }
        if (activity.value[kind]?.busy == true) return
        activity.update { it + (kind to EngineActivity(busy = true)) }
        viewModelScope.launch {
            val result = call(adapter) ?: DeviceResult.Unsupported
            activity.update { it + (kind to EngineActivity(problem = result.problem())) }
            if (result is DeviceResult.Success) refresh(kind, adapter)
        }
    }

    private companion object {
        const val KEY_DEVICE = "device"
        const val KEY_ENGINE = "engine"
        const val LOG_LINES = 200
        const val STOP_AFTER = 5_000L
    }
}

/** Measured traffic as the screens draw it: per-engine latest, the sum, and the sum's history. */
private data class TrafficState(
    val latest: List<TrafficSnapshot?> = emptyList(),
    val sum: TrafficSnapshot? = null,
    val history: List<TrafficSnapshot> = emptyList(),
)

/** Starts with null so a screen can draw before the first report arrives. */
private fun <T : Any> Flow<T>.orNullFirst(): Flow<T?> = map<T, T?> { it }.onStart { emit(null) }

private fun DeviceResult<*>.problem(): DeviceResult<Nothing>? = when (this) {
    is DeviceResult.Success -> null
    DeviceResult.NotConnected -> DeviceResult.NotConnected
    DeviceResult.Unsupported -> DeviceResult.Unsupported
    is DeviceResult.Failure -> this
}
