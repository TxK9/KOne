package cc.txwd.kone.core

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The core run by KOne itself, as the person (no TUN, no rights): with a local port that Windows'
 * proxy points at, when the service cannot make the adapter. Its lines go to [onLine] as they come.
 * Like the service's, it ends with KOne, however KOne ends.
 */
class LocalCore(private val core: File, private val onLine: (String) -> Unit) {
    private var process: Process? = null

    @Synchronized
    fun start(config: String): Boolean {
        stop()
        val started = runCatching {
            ProcessBuilder(core.path, "run", "-c", "stdin", "--disable-color").directory(core.parentFile).redirectErrorStream(true).start()
        }.getOrNull() ?: return false
        Children.tie(started)
        runCatching { started.outputStream.use { it.write(config.toByteArray()) } }
        Thread({ runCatching { started.inputStream.bufferedReader().forEachLine(onLine) } }, "KOne core lines").apply { isDaemon = true }.start()
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

    /** How the last run ended, as the service says it: "exited" and the code. */
    val end: String @Synchronized get() = process?.takeUnless { it.isAlive }?.let { "exited ${it.exitValue()}" } ?: "exited"
}

/**
 * The cores KOne starts itself, tied to it by a Windows job object: when KOne ends, even by a
 * crash, Windows ends them too, so no connection outlives the App.
 */
object Children {
    private interface Jobs : StdCallLibrary {
        fun CreateJobObjectW(attributes: Pointer?, name: String?): WinNT.HANDLE?
        fun SetInformationJobObject(job: WinNT.HANDLE, kind: Int, information: Pointer, length: Int): Boolean
        fun AssignProcessToJobObject(job: WinNT.HANDLE, process: WinNT.HANDLE): Boolean
    }

    private const val JOB_OBJECT_EXTENDED_LIMIT_INFORMATION = 9
    private const val JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000
    private const val PROCESS_SET_QUOTA = 0x0100
    private const val PROCESS_TERMINATE = 0x0001

    private val jobs: Jobs? by lazy {
        if (!System.getProperty("os.name").startsWith("Windows")) null else runCatching { Native.load("kernel32", Jobs::class.java) }.getOrNull()
    }

    /** One job for the life of KOne; its handle closes when KOne ends, and with it the cores. */
    private val job: WinNT.HANDLE? by lazy {
        val api = jobs ?: return@lazy null
        val job = api.CreateJobObjectW(null, null) ?: return@lazy null
        // JOBOBJECT_EXTENDED_LIMIT_INFORMATION on 64-bit Windows: 144 bytes, LimitFlags at 16.
        val limits = Memory(144).apply {
            clear()
            setInt(16, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE)
        }
        if (api.SetInformationJobObject(job, JOB_OBJECT_EXTENDED_LIMIT_INFORMATION, limits, 144)) job else null
    }

    fun tie(process: Process) {
        val api = jobs ?: return
        val job = job ?: return
        val handle = Kernel32.INSTANCE.OpenProcess(PROCESS_SET_QUOTA or PROCESS_TERMINATE, false, process.pid().toInt()) ?: return
        api.AssignProcessToJobObject(job, handle)
        Kernel32.INSTANCE.CloseHandle(handle)
    }
}
