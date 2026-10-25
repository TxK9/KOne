package io.nekohasekai.sfa.compose.screen.dashboard

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Bounded retries on the same caller-bound VPN; cancellation must stop a stale check. */
internal suspend fun <T : Any> detectWithRetry(
    pause: suspend (Long) -> Unit = { delay(it) },
    detect: suspend () -> T?,
): T? {
    for (wait in longArrayOf(0, 1500, 4000)) {
        if (wait != 0L) pause(wait)
        try {
            detect()?.let { return it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A new tunnel may exist before it carries traffic.
        }
    }
    return null
}
