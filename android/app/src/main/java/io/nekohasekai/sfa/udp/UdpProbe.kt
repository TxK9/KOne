package io.nekohasekai.sfa.udp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

internal data class UdpReply(val address: String, val latencyMs: Int) {
    val ip: String get() = if (address.startsWith("[")) address.substringAfter('[').substringBefore(']')
        else address.substringBeforeLast(':', address)
}
internal data class UdpMeasurement(val passed: Boolean, val reply: UdpReply? = null, val attempts: Int = 0)

/** Binding responses prove UDP reachability to this target, never carrier/IMS registration. */
internal class UdpProbe(private val binding: suspend (String, String) -> UdpReply, private val totalMs: Long = TOTAL_MS, private val attemptMs: Long = ATTEMPT_MS) {
    companion object {
        val servers = listOf("stun.voipgate.com:3478", "stun.l.google.com:19302")
        const val TOTAL_MS = 6_000L
        const val ATTEMPT_MS = 2_800L
    }
    suspend fun measure(target: UdpTarget): UdpMeasurement {
        if (target.tcpOnly) return UdpMeasurement(false)
        var attempts = 0
        return withTimeoutOrNull(totalMs) {
            for (server in servers) {
                attempts++
                val reply = try {
                    withTimeoutOrNull(attemptMs) { binding(target.tag, server) }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
                if (reply != null && reply.address.isNotBlank()) return@withTimeoutOrNull UdpMeasurement(true, reply, attempts)
            }
            UdpMeasurement(false, attempts = attempts)
        } ?: UdpMeasurement(false, attempts = attempts)
    }
}
