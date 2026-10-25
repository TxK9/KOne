package io.nekohasekai.sfa.udp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class UdpPhase { Idle, Running, Passed, Failed }
internal data class UdpCheckState(val target: UdpTarget? = null, val phase: UdpPhase = UdpPhase.Idle, val measurement: UdpMeasurement? = null)

/** One run at a time. A route/network change invalidates results and late callbacks. */
internal class UdpCheckController(private val scope: CoroutineScope, private val probe: UdpProbe) {
    private val guard = Any()
    private var serial = 0L
    private var job: Job? = null
    private val mutable = MutableStateFlow(UdpCheckState())
    val state = mutable.asStateFlow()
    fun bind(target: UdpTarget?) = synchronized(guard) {
        if (target == mutable.value.target) return@synchronized
        serial++; job?.cancel(); job = null
        mutable.value = UdpCheckState(target)
    }
    fun cancel() = synchronized(guard) {
        serial++; job?.cancel(); job = null
        mutable.value = UdpCheckState(mutable.value.target)
    }
    fun start() = synchronized(guard) {
        val target = mutable.value.target ?: return@synchronized
        if (target.tcpOnly || mutable.value.phase == UdpPhase.Running) return@synchronized
        val generation = ++serial
        mutable.value = UdpCheckState(target, UdpPhase.Running)
        job = scope.launch {
            val measurement = try { probe.measure(target) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { UdpMeasurement(false) }
            synchronized(guard) {
                if (serial == generation && mutable.value.target == target) {
                    mutable.value = UdpCheckState(target, if (measurement.passed) UdpPhase.Passed else UdpPhase.Failed, measurement)
                    job = null
                }
            }
        }
    }
}
