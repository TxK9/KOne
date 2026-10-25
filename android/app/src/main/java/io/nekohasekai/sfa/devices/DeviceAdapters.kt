package io.nekohasekai.sfa.devices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/*
 * The contracts an integration implements. An integration is any way of reaching devices and
 * their engines: the phone's own core, a router's management API, a desktop agent, the console.
 * The screens depend only on these interfaces, so a new integration needs no screen changes.
 *
 * Rules for implementers:
 * - Report only what was observed. A value that was not measured stays null; never estimate it.
 * - A request is not a result. Return Success only once the device has acknowledged the change,
 *   and let the status flow, not the call, tell the screen what is now true.
 * - Keep transport details (addresses, ports, protocols, credentials) inside the adapter.
 * - Calls are made from the main thread's coroutine scope; switch dispatchers inside the adapter.
 */

/** The outcome of a call into an integration. */
sealed interface DeviceResult<out T> {
    data class Success<T>(val value: T) : DeviceResult<T>

    /** No integration is connected for this device or engine. */
    data object NotConnected : DeviceResult<Nothing>

    /** The connected integration does not offer this. */
    data object Unsupported : DeviceResult<Nothing>

    /** The integration tried and the device refused or did not answer. [message] is shown as is. */
    data class Failure(val message: String, val cause: Throwable? = null) : DeviceResult<Nothing>
}

inline fun <T, R> DeviceResult<T>.map(transform: (T) -> R): DeviceResult<R> = when (this) {
    is DeviceResult.Success -> DeviceResult.Success(transform(value))
    DeviceResult.NotConnected -> DeviceResult.NotConnected
    DeviceResult.Unsupported -> DeviceResult.Unsupported
    is DeviceResult.Failure -> this
}

fun <T> DeviceResult<T>.valueOrNull(): T? = (this as? DeviceResult.Success)?.value

// ---- Devices --------------------------------------------------------------------------------

/** Finds the devices one integration can reach: a local scan, a console list, a pairing code. */
interface DeviceDiscovery {
    /** Devices found so far. The flow may keep updating as devices come and go. */
    fun devices(): Flow<List<DeviceDescriptor>>
}

/** Signs in to devices that ask for it. */
interface DeviceAuthenticator {
    /** The credential forms [device] accepts, so the screen asks for the right one. */
    fun accepts(device: DeviceDescriptor): Set<CredentialKind>

    suspend fun authenticate(device: DeviceDescriptor, credential: DeviceCredential): DeviceResult<DeviceSession>

    suspend fun signOut(session: DeviceSession): DeviceResult<Unit>
}

/** Opens connections to the devices one integration understands. */
interface DeviceConnector {
    fun supports(device: DeviceDescriptor): Boolean

    fun open(device: DeviceDescriptor): DeviceConnection
}

/** A live link to one device: connection management, device facts and engine identification. */
interface DeviceConnection {
    val device: DeviceDescriptor

    val link: StateFlow<LinkState>

    /** Links to the device; [session] comes from the integration's authenticator when it has one. */
    suspend fun connect(session: DeviceSession? = null): DeviceResult<Unit>

    suspend fun disconnect()

    suspend fun info(): DeviceResult<DeviceInfo>

    /** The engines found on the device. */
    suspend fun engines(): DeviceResult<List<EngineDescriptor>>

    /** The adapter for one engine on the device, or null when this integration cannot drive it. */
    fun engine(kind: EngineKind): EngineAdapter?
}

// ---- Engines --------------------------------------------------------------------------------

/**
 * One engine on one device. Only [status] is required; every other capability is optional, and a
 * null capability shows as "Not available" for that engine rather than as an error.
 */
interface EngineAdapter {
    val kind: EngineKind

    val status: EngineStatusSource

    val control: EngineControl? get() = null

    val configuration: EngineConfiguration? get() = null

    val modes: ProxyModeControl? get() = null

    val nodes: NodeManager? get() = null

    val routing: RoutingConfiguration? get() = null

    val traffic: TrafficStatistics? get() = null

    val logs: LogSource? get() = null
}

interface EngineStatusSource {
    fun status(): Flow<EngineStatus>
}

interface EngineControl {
    suspend fun setEnabled(enabled: Boolean): DeviceResult<Unit>

    suspend fun start(): DeviceResult<Unit>

    suspend fun stop(): DeviceResult<Unit>

    suspend fun restart(): DeviceResult<Unit>
}

interface EngineConfiguration {
    suspend fun summary(): DeviceResult<EngineConfigSummary>

    suspend fun read(): DeviceResult<EngineConfigDocument>

    /**
     * Replaces the configuration and returns the new revision. With [expectedRevision], the
     * adapter must refuse when the device holds a different one, so a newer edit is never lost.
     */
    suspend fun write(document: EngineConfigDocument, expectedRevision: String?): DeviceResult<String>
}

interface ProxyModeControl {
    /** The modes this engine offers; the others show as unavailable. */
    val supported: Set<ProxyMode>

    suspend fun current(): DeviceResult<ProxyMode>

    suspend fun select(mode: ProxyMode): DeviceResult<Unit>
}

interface NodeManager {
    suspend fun groups(): DeviceResult<List<NodeGroup>>

    suspend fun select(groupId: String, nodeId: String): DeviceResult<Unit>

    /** Measures the delay to every node in the group; returns node id to milliseconds. */
    suspend fun measure(groupId: String): DeviceResult<Map<String, Int>>
}

interface RoutingConfiguration {
    suspend fun rules(): DeviceResult<RoutingTable>

    /** Replaces the rules; refuses when the device holds a revision other than [expectedRevision]. */
    suspend fun update(rules: List<RoutingRule>, expectedRevision: String?): DeviceResult<String>
}

interface TrafficStatistics {
    /** Measured samples as they arrive. */
    fun traffic(): Flow<TrafficSnapshot>
}

interface LogSource {
    suspend fun recent(limit: Int): DeviceResult<List<EngineLogLine>>

    /** New lines as the engine writes them. */
    fun follow(): Flow<EngineLogLine>
}
