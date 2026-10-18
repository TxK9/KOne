package cc.txwd.kone.core

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The core without TUN, run as the person themselves while not connected: it only carries the
 * nodes, so their delays can be tested and the dashboard shows weather before connecting.
 */
class Probe(private val core: File) {
    private var process: Process? = null

    @Synchronized
    fun start(config: String): Boolean {
        stop()
        val started = runCatching {
            ProcessBuilder(core.path, "run", "-c", "stdin", "--disable-color").directory(core.parentFile)
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        }.getOrNull() ?: return false
        runCatching { started.outputStream.use { it.write(config.toByteArray()) } }
        process = started
        return true
    }

    @Synchronized
    fun stop() {
        process?.let { running ->
            running.destroy()
            if (!running.waitFor(3, TimeUnit.SECONDS)) running.destroyForcibly()
        }
        process = null
    }

    val running: Boolean @Synchronized get() = process?.isAlive == true
}
