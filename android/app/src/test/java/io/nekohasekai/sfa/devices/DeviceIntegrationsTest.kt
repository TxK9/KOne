package io.nekohasekai.sfa.devices

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceIntegrationsTest {
    private val phone = DeviceDescriptor(id = "this-device", name = "Phone", role = DeviceRole.ThisDevice)
    private val remote = DeviceDescriptor(id = "box-1", name = "Box", role = DeviceRole.Remote)

    @Test
    fun anEmptyRegistryClaimsNothingAndFindsNothing() = runBlocking {
        val registry = DeviceIntegrationRegistry()
        assertNull(registry.connectorFor(phone))
        assertNull(registry.authenticatorFor(phone))
        assertTrue(!registry.canDiscover)
        assertEquals(emptyList<DeviceDescriptor>(), registry.discovered().first())
    }

    @Test
    fun registeringTheSameIdReplacesTheEarlierIntegration() {
        val registry = DeviceIntegrationRegistry()
        registry.register(FakeIntegration("a"))
        val second = FakeIntegration("a")
        registry.register(second)
        assertEquals(listOf(second), registry.integrations.value)
        registry.unregister("a")
        assertEquals(emptyList<DeviceIntegration>(), registry.integrations.value)
    }

    @Test
    fun discoveredDevicesCarryTheIntegrationThatFoundThem() = runBlocking {
        val registry = DeviceIntegrationRegistry()
        registry.register(FakeIntegration("lan", found = listOf(remote)))
        registry.register(FakeIntegration("console", found = listOf(remote.copy(id = "box-2"))))
        val found = registry.discovered().first { it.size == 2 }
        assertEquals(listOf("lan", "console"), found.map { it.adapterId })
        assertTrue(registry.canDiscover)
    }

    @Test
    fun aFoundDeviceOpensOnlyThroughItsOwnIntegration() {
        val registry = DeviceIntegrationRegistry()
        val lan = FakeIntegration("lan", claims = { true })
        val other = FakeIntegration("other", claims = { true })
        registry.register(other)
        registry.register(lan)
        assertSame(lan.connector, registry.connectorFor(remote.copy(adapterId = "lan")))
        assertNull(registry.connectorFor(remote.copy(adapterId = "gone")))
        // This phone was found by nobody: the first integration claiming it opens it.
        assertSame(other.connector, registry.connectorFor(phone))
    }

    @Test
    fun withoutAConnectionEveryPartIsNotConnected() {
        val features = engineFeatures(connected = false, adapter = FakeEngine())
        assertTrue(features.values.all { it == Availability.NotConnected })
    }

    @Test
    fun aConnectionThatCannotDriveTheEngineMakesEveryPartUnavailable() {
        val features = engineFeatures(connected = true, adapter = null)
        assertTrue(features.values.all { it == Availability.Unsupported })
    }

    @Test
    fun eachPartFollowsWhatTheAdapterOffers() {
        val features = engineFeatures(connected = true, adapter = FakeEngine(withModes = true))
        assertEquals(Availability.Ready, features[EngineFeature.Status])
        assertEquals(Availability.Ready, features[EngineFeature.Mode])
        assertEquals(Availability.Unsupported, features[EngineFeature.Power])
        assertEquals(Availability.Unsupported, features[EngineFeature.Logs])
    }

    @Test
    fun trafficAddsRatesAndShowsTotalsOnlyWhenEveryEngineMeasuredThem() {
        val a = TrafficSnapshot(uploadRate = 10, downloadRate = 20, uploadTotal = 100, downloadTotal = 200, connections = 3, sampledAt = 5)
        val b = TrafficSnapshot(uploadRate = 1, downloadRate = 2, uploadTotal = null, downloadTotal = 20, connections = 1, sampledAt = 9)
        val sum = combinedTraffic(listOf(a, b))!!
        assertEquals(11L, sum.uploadRate)
        assertEquals(22L, sum.downloadRate)
        assertNull(sum.uploadTotal)
        assertEquals(220L, sum.downloadTotal)
        assertEquals(4, sum.connections)
        assertEquals(9L, sum.sampledAt)
        assertNull(combinedTraffic(emptyList()))
    }

    @Test
    fun reportedDeviceFactsWinOverWhatThePhoneKnew() {
        val known = DeviceInfo(model = "Phone", system = "Android", extra = listOf("Slot" to "A"))
        val reported = DeviceInfo(system = "Agent OS", extra = listOf("Slot" to "B", "Uptime" to "1 h"))
        val merged = known.mergedWith(reported)!!
        assertEquals("Phone", merged.model)
        assertEquals("Agent OS", merged.system)
        assertEquals(listOf("Slot" to "B", "Uptime" to "1 h"), merged.extra)
    }

    @Test
    fun mappingAResultKeepsItsProblem() {
        val failure: DeviceResult<Int> = DeviceResult.Failure("refused")
        assertEquals(failure, failure.map { it + 1 })
        assertEquals(DeviceResult.Success(2), DeviceResult.Success(1).map { it + 1 })
        assertNull(DeviceResult.NotConnected.valueOrNull())
    }

    @Test
    fun engineIdsRoundTrip() {
        EngineKind.entries.forEach { assertEquals(it, EngineKind.fromId(it.id)) }
        assertNull(EngineKind.fromId("unknown"))
    }

    private class FakeIntegration(
        override val id: String,
        found: List<DeviceDescriptor>? = null,
        claims: (DeviceDescriptor) -> Boolean = { false },
    ) : DeviceIntegration {
        override val discovery: DeviceDiscovery? = found?.let { list ->
            object : DeviceDiscovery {
                override fun devices(): Flow<List<DeviceDescriptor>> = flowOf(list)
            }
        }

        override val connector: DeviceConnector = object : DeviceConnector {
            override fun supports(device: DeviceDescriptor) = claims(device)
            override fun open(device: DeviceDescriptor): DeviceConnection = FakeConnection(device)
        }
    }

    private class FakeConnection(override val device: DeviceDescriptor) : DeviceConnection {
        override val link: StateFlow<LinkState> = MutableStateFlow(LinkState.NotConnected)
        override suspend fun connect(session: DeviceSession?): DeviceResult<Unit> = DeviceResult.Success(Unit)
        override suspend fun disconnect() = Unit
        override suspend fun info(): DeviceResult<DeviceInfo> = DeviceResult.Unsupported
        override suspend fun engines(): DeviceResult<List<EngineDescriptor>> = DeviceResult.Success(emptyList())
        override fun engine(kind: EngineKind): EngineAdapter? = null
    }

    private class FakeEngine(withModes: Boolean = false) : EngineAdapter {
        override val kind = EngineKind.Custom
        override val status = object : EngineStatusSource {
            override fun status(): Flow<EngineStatus> = flowOf(EngineStatus(RunState.Stopped, enabled = false))
        }
        override val modes: ProxyModeControl? = if (!withModes) null else object : ProxyModeControl {
            override val supported = setOf(ProxyMode.Rule)
            override suspend fun current(): DeviceResult<ProxyMode> = DeviceResult.Success(ProxyMode.Rule)
            override suspend fun select(mode: ProxyMode): DeviceResult<Unit> = DeviceResult.Unsupported
        }
    }
}
