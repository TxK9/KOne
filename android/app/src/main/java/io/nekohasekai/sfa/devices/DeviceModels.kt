package io.nekohasekai.sfa.devices

/*
 * The words device management speaks, independent of any engine, operating system or transport.
 * Adapters translate their own APIs into these types; the screens only ever see these. Nothing
 * here knows an address, a port or a protocol: where a device is and how it is reached stays
 * inside the adapter that found it.
 */

/** A device as an integration knows it. The id is opaque and belongs to the adapter that found it. */
data class DeviceDescriptor(
    val id: String,
    val name: String,
    val role: DeviceRole,
    /** The integration that found the device; null for this phone, which needs no discovery. */
    val adapterId: String? = null,
    /** Free text the adapter reports, such as "Router" or "Desktop". Never parsed. */
    val platform: String? = null,
)

enum class DeviceRole {
    /** The phone KOne is running on. */
    ThisDevice,

    /** Anything reached through an integration: a router, a desktop, a server. */
    Remote,
}

/** Facts a device reports about itself. Every field is optional; adapters fill what they know. */
data class DeviceInfo(
    val model: String? = null,
    val manufacturer: String? = null,
    val system: String? = null,
    val software: String? = null,
    val architecture: String? = null,
    /** Adapter-specific facts shown as extra rows, in order. */
    val extra: List<Pair<String, String>> = emptyList(),
)

/** Ways a device may ask to be signed in to. */
enum class CredentialKind { None, Token, Password, PairingCode }

/** What the person typed or scanned to sign in. Adapters must not store it beyond the session. */
sealed interface DeviceCredential {
    val kind: CredentialKind

    data object None : DeviceCredential {
        override val kind = CredentialKind.None
    }

    class Token(val token: String) : DeviceCredential {
        override val kind = CredentialKind.Token
    }

    class Password(val username: String, val secret: CharArray) : DeviceCredential {
        override val kind = CredentialKind.Password
    }

    class PairingCode(val code: String) : DeviceCredential {
        override val kind = CredentialKind.PairingCode
    }
}

/** An authenticated session with one device. Adapters may subclass it to carry their own state. */
interface DeviceSession {
    val deviceId: String

    /** Epoch milliseconds after which the session must be renewed, or null when it does not expire. */
    val expiresAt: Long? get() = null
}

/** The link between KOne and a device. */
sealed interface LinkState {
    /** No integration has linked to the device. */
    data object NotConnected : LinkState

    data object Connecting : LinkState

    data object Connected : LinkState

    /** The link was up and dropped; [reason] is the adapter's own words. */
    data class Lost(val reason: String) : LinkState
}

/** The engines device management has a place for. [id] is stable and safe to store. */
enum class EngineKind(val id: String) {
    SingBox("sing-box"),
    Mihomo("mihomo"),
    Xray("xray"),
    Custom("custom"),
    ;

    companion object {
        fun fromId(id: String): EngineKind? = entries.firstOrNull { it.id == id }
    }
}

/** One engine found on a device: engine identification. */
data class EngineDescriptor(
    val kind: EngineKind,
    /** Distinguishes two instances of the same engine on one device. */
    val instanceId: String,
    val name: String,
    val version: String? = null,
)

/** The proxy modes every engine is offered in. Adapters map them to their engine's own terms. */
enum class ProxyMode { Global, Rule, Direct }

enum class RunState { Stopped, Starting, Running, Stopping, Failed }

data class EngineStatus(
    val state: RunState,
    val enabled: Boolean,
    val version: String? = null,
    val mode: ProxyMode? = null,
    /** Epoch milliseconds the engine last started, when known. */
    val since: Long? = null,
    /** The adapter's own words for a failure. */
    val message: String? = null,
)

data class EngineConfigSummary(
    val name: String? = null,
    val revision: String? = null,
    val updatedAt: Long? = null,
    /** Free text such as "sing-box JSON" or "YAML". */
    val format: String? = null,
)

data class EngineConfigDocument(
    val format: String,
    val content: String,
    val revision: String? = null,
)

data class EngineNode(
    val id: String,
    val name: String,
    val protocol: String? = null,
    /** The last measured delay, null until measured. Never estimated. */
    val latencyMs: Int? = null,
)

data class NodeGroup(
    val id: String,
    val name: String,
    val selectedNodeId: String?,
    val nodes: List<EngineNode>,
    /** False for automatic groups the engine picks for itself. */
    val selectable: Boolean = true,
)

/** A routing rule in display terms: what it matches and where matching traffic goes. */
data class RoutingRule(
    val id: String,
    val match: String,
    val target: String,
    val enabled: Boolean = true,
)

data class RoutingTable(
    val rules: List<RoutingRule>,
    val revision: String? = null,
)

/** One measured sample. Rates are bytes per second; totals are bytes since the engine started. */
data class TrafficSnapshot(
    val uploadRate: Long,
    val downloadRate: Long,
    val uploadTotal: Long? = null,
    val downloadTotal: Long? = null,
    val connections: Int? = null,
    val sampledAt: Long,
)

enum class LogLevel { Trace, Debug, Info, Warn, Error }

data class EngineLogLine(
    val level: LogLevel,
    val message: String,
    val time: Long? = null,
)
