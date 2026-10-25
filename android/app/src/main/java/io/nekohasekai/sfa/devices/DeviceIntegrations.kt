package io.nekohasekai.sfa.devices

import android.os.Build
import io.nekohasekai.sfa.BuildConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.update

/** One integration's parts, registered together. Any part may be absent. */
interface DeviceIntegration {
    /** Stable and unique, such as "local-core" or "router-api". */
    val id: String

    val discovery: DeviceDiscovery? get() = null

    val authenticator: DeviceAuthenticator? get() = null

    val connector: DeviceConnector? get() = null
}

/** The integrations KOne knows. The screens read devices and engines only through it. */
class DeviceIntegrationRegistry {
    private val registered = MutableStateFlow<List<DeviceIntegration>>(emptyList())

    val integrations: StateFlow<List<DeviceIntegration>> = registered.asStateFlow()

    /** Adds [integration], replacing any registered under the same id. */
    fun register(integration: DeviceIntegration) {
        registered.update { list -> list.filterNot { it.id == integration.id } + integration }
    }

    fun unregister(id: String) {
        registered.update { list -> list.filterNot { it.id == id } }
    }

    val canDiscover: Boolean get() = registered.value.any { it.discovery != null }

    /** Every device the registered integrations have found, each tagged with its integration. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun discovered(): Flow<List<DeviceDescriptor>> = registered.flatMapLatest { list ->
        val sources = list.mapNotNull { integration ->
            integration.discovery?.devices()
                ?.map { found -> found.map { it.copy(adapterId = integration.id) } }
                ?.onStart { emit(emptyList()) }
        }
        if (sources.isEmpty()) flowOf(emptyList()) else combine(sources) { lists -> lists.flatMap { it } }
    }

    /**
     * The connector for [device]: the integration that found it, or for a device nobody had to find
     * (this phone), the first integration that claims it.
     */
    fun connectorFor(device: DeviceDescriptor): DeviceConnector? {
        val list = registered.value
        device.adapterId?.let { id -> return list.firstOrNull { it.id == id }?.connector?.takeIf { it.supports(device) } }
        return list.firstNotNullOfOrNull { integration -> integration.connector?.takeIf { it.supports(device) } }
    }

    fun authenticatorFor(device: DeviceDescriptor): DeviceAuthenticator? {
        val list = registered.value
        device.adapterId?.let { id -> return list.firstOrNull { it.id == id }?.authenticator }
        return list.firstOrNull { it.connector?.supports(device) == true }?.authenticator
    }
}

/**
 * The app's integrations. None ships yet, so every device and engine reads "Not connected".
 * To connect a real service, implement [DeviceIntegration] and add it to [builtIn].
 */
object DeviceIntegrations {
    val registry: DeviceIntegrationRegistry by lazy {
        DeviceIntegrationRegistry().also { registry -> builtIn().forEach(registry::register) }
    }

    private fun builtIn(): List<DeviceIntegration> = emptyList()
}

/** Who sees device management: the owner's edition now; the edition for others once the console grants it. */
object DeviceManagementGate {
    val visible: Boolean get() = BuildConfig.KNINE_OWNER
}

/** This phone as a device. Its facts are read here, never assumed. */
object ThisDevice {
    const val ID = "this-device"

    fun descriptor(): DeviceDescriptor = DeviceDescriptor(id = ID, name = name(), role = DeviceRole.ThisDevice, platform = "Android")

    fun info(): DeviceInfo = DeviceInfo(
        model = Build.MODEL,
        manufacturer = Build.MANUFACTURER.replaceFirstChar { it.titlecase() },
        system = "Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
        software = "KOne ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}",
        architecture = Build.SUPPORTED_ABIS.firstOrNull(),
    )

    private fun name(): String {
        val maker = Build.MANUFACTURER.replaceFirstChar { it.titlecase() }
        val model = Build.MODEL.orEmpty()
        return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
    }
}
