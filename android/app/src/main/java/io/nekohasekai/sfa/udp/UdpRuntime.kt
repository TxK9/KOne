package io.nekohasekai.sfa.udp

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Published only after the local core has accepted this exact configuration. Never persisted. */
internal data class RunningUdpProfile(val id: Long, val name: String, val config: String, val epoch: Long, val network: Long)

internal object UdpRuntime {
    private val serial = AtomicLong()
    private val network = AtomicLong()
    private val mutable = MutableStateFlow<RunningUdpProfile?>(null)
    val active = mutable.asStateFlow()
    fun changing() { mutable.value = null }
    fun started(id: Long, name: String, config: String) {
        mutable.value = RunningUdpProfile(id, name, config, serial.incrementAndGet(), network.get())
    }
    fun networkChanged(handle: Long) {
        if (network.getAndSet(handle) != handle) mutable.update { it?.copy(epoch = serial.incrementAndGet(), network = handle) }
    }
}
