package cc.txwd.kone.shared

/** A memory-only authorization deadline. HTTP and platform clocks stay in their adapters. */
class ControllerLease(private val elapsedMillis: () -> Long) {
    data class Context(
        val deviceId: String,
        val configRevision: String,
        val authGeneration: Long,
        val mode: String,
        val nodeIds: Set<String> = emptySet(),
    )

    data class Request internal constructor(
        val context: Context,
        val nonce: String,
        internal val epoch: Long,
        internal val started: Long,
        internal val sequence: Long,
    )

    data class Reply(
        val format: Int,
        val deviceId: String,
        val configRevision: String,
        val authGeneration: Long,
        val mode: String,
        val required: Boolean,
        val nonce: String,
        val serverTime: Double,
        val notAfter: Double,
        val ttlSeconds: Long,
        val nodeIds: Set<String>,
    )

    private var epoch = 0L
    private var sequence = 0L
    private var context: Context? = null
    private var pending: Request? = null
    private var deadline: Long? = null
    private var lastClock: Long? = null

    @Synchronized
    fun begin(expected: Context, nonce: String): Request? {
        val stable = expected.copy(nodeIds = expected.nodeIds.toSet())
        if (!validContext(stable) || !NONCE.matches(nonce)) { invalidate(); return null }
        if (context != stable) { invalidate(); context = stable }
        val now = clock() ?: return null
        return Request(stable, nonce, epoch, now, ++sequence).also { pending = it }
    }

    @Synchronized
    fun accept(request: Request, reply: Reply): Boolean {
        if (pending != request || request.epoch != epoch || context != request.context) return false
        val now = clock() ?: return false
        val duration = reply.notAfter - reply.serverTime
        val lifetime = minOf(reply.ttlSeconds.coerceIn(0, MAX_SECONDS) * 1000, kotlin.math.floor(duration * 1000).toLong())
        val matches =
            reply.format == 1 && reply.nonce == request.nonce && reply.deviceId == request.context.deviceId &&
            reply.configRevision == request.context.configRevision && reply.authGeneration == request.context.authGeneration &&
            reply.mode == request.context.mode && reply.required == (reply.mode == "enabled") &&
            reply.nodeIds == request.context.nodeIds && reply.ttlSeconds in 1..MAX_SECONDS &&
            reply.serverTime.isFinite() && reply.notAfter.isFinite() && duration > 0 &&
            duration <= reply.ttlSeconds.toDouble() + 0.001 && lifetime > 0 &&
            request.started <= Long.MAX_VALUE - lifetime && now >= request.started &&
            now - request.started < lifetime
        if (!matches) { invalidate(); return false }
        deadline = request.started + lifetime
        pending = null
        return true
    }

    @Synchronized
    fun authorized(expected: Context): Boolean {
        val now = clock() ?: return false
        val end = deadline ?: return false
        if (context != expected || now >= end) { invalidate(); return false }
        return true
    }

    @Synchronized
    fun remainingMillis(): Long {
        val now = clock() ?: return 0
        return deadline?.let { (it - now).coerceAtLeast(0) } ?: 0
    }

    /** Call before stopping, changing account/source/network, suspending, or reporting denial. */
    @Synchronized
    fun invalidate() {
        epoch++
        pending = null
        deadline = null
        context = null
    }

    private fun clock(): Long? {
        val now = elapsedMillis()
        if (now < 0 || lastClock?.let { now < it } == true) {
            invalidate()
            lastClock = now
            return null
        }
        lastClock = now
        return now
    }

    private fun validContext(value: Context): Boolean = value.deviceId.length in 1..128 &&
        REVISION.matches(value.configRevision) && value.authGeneration >= 0 && value.mode in setOf("legacy", "enabled") &&
        value.nodeIds.size <= 512 && value.nodeIds.all { it.length in 1..128 } &&
        (value.mode != "legacy" || value.nodeIds.isEmpty()) && (value.mode != "enabled" || value.nodeIds.isNotEmpty())

    companion object {
        const val MAX_SECONDS = 60L
        const val RENEW_SECONDS = 20L
        private val NONCE = Regex("[A-Za-z0-9_-]{32,128}")
        private val REVISION = Regex("[0-9a-f]{64}")
    }
}
