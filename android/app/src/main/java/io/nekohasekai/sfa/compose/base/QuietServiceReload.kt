package io.nekohasekai.sfa.compose.base

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Applies the latest saved settings serially, without a confirmation snackbar. */
internal class QuietServiceReload(
    scope: CoroutineScope,
    private val shouldApply: () -> Boolean,
    private val apply: suspend () -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val settleMillis: Long = 250,
) {
    private val requests = Channel<Unit>(Channel.CONFLATED)
    init {
        scope.launch {
            for (request in requests) {
                delay(settleMillis)
                while (requests.tryReceive().isSuccess) { /* Changes share the latest saved state. */ }
                if (!shouldApply()) continue
                try { apply() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { onFailure(e) }
            }
        }
    }
    fun request() { requests.trySend(Unit) }
}
