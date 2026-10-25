package io.nekohasekai.sfa.udp

import io.nekohasekai.libbox.STUNTestHandler
import io.nekohasekai.libbox.STUNTestProgress
import io.nekohasekai.libbox.STUNTestResult
import io.nekohasekai.libbox.STUNTestSession
import io.nekohasekai.libbox.Libbox
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A cancelled/dismissed test closes even a session that arrives after cancellation.
 * Never close a stream on its callback thread: native Close waits for that thread to finish.
 */
internal object NativeUdpBinding {
    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    suspend fun bind(tag: String, server: String): UdpReply = suspendCancellableCoroutine { result ->
        val session = AtomicReference<STUNTestSession?>()
        val ended = AtomicBoolean(false)
        fun close() { session.getAndSet(null)?.let { owned -> worker.launch { runCatching { owned.close() } } } }
        fun finish(address: String, latency: Int) {
            if (address.isBlank() || !ended.compareAndSet(false, true)) return
            if (result.isActive) result.resume(UdpReply(address, latency.coerceAtLeast(0)))
            close()
        }
        val handler = object : STUNTestHandler {
            override fun onProgress(progress: STUNTestProgress?) { progress?.let { finish(it.externalAddr, it.latencyMs) } }
            override fun onResult(value: STUNTestResult?) {
                if (value?.externalAddr.isNullOrBlank()) onError("No binding response")
                else finish(value!!.externalAddr, value.latencyMs)
            }
            override fun onError(message: String?) {
                if (!ended.compareAndSet(false, true)) return
                if (result.isActive) result.resumeWithException(IllegalStateException("UDP binding failed"))
                close()
            }
        }
        result.invokeOnCancellation { ended.set(true); close() }
        worker.launch {
            try {
                val owned = Libbox.newStandaloneCommandClient().startSTUNTest(server, tag, handler)
                session.set(owned)
                if (!result.isActive) close()
            } catch (_: Exception) { handler.onError(null) }
        }
    }
}
