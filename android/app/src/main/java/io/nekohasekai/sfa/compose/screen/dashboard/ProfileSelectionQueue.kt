package io.nekohasekai.sfa.compose.screen.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Called on the UI thread. Only this worker may apply a selected profile. */
internal class ProfileSelectionQueue(
    private val scope: CoroutineScope,
    private val onBusy: (Boolean) -> Unit,
    private val onFailure: (Exception) -> Unit,
    private val apply: suspend (Long) -> Unit,
    private val debounceMs: Long = 180,
) {
    private var pending: Long? = null
    private var worker: Job? = null

    fun request(profileId: Long) {
        pending = profileId
        if (worker?.isActive == true) return
        worker = scope.launch {
            onBusy(true)
            try {
                while (pending != null) {
                    delay(debounceMs)
                    val target = pending ?: break
                    pending = null
                    try {
                        apply(target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (pending == null) onFailure(e)
                    }
                }
            } finally {
                onBusy(false)
                worker = null
            }
        }
    }
}
