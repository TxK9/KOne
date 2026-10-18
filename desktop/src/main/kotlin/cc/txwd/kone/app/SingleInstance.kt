package cc.txwd.kone.app

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import java.security.SecureRandom
import java.util.HexFormat
import kotlin.concurrent.thread

/**
 * One KOne at a time. The first holds a lock in the App's folder and listens on loopback; a
 * second one (a kone:// link clicked while KOne runs) hands its link to the first and quits.
 */
class SingleInstance(private val folder: File) {
    private var lock: FileLock? = null

    /**
     * True when this is the first KOne: [onMessage] then receives what later ones pass on (a
     * link, or "show"). False when another runs and has been given [message]; this one should quit.
     */
    fun claim(message: String, onMessage: (String) -> Unit): Boolean {
        folder.mkdirs()
        val channel = RandomAccessFile(File(folder, "instance.lock"), "rw").channel
        val held = runCatching { channel.tryLock() }.getOrNull()
        if (held == null) {
            channel.close()
            pass(message)
            return false
        }
        lock = held
        val token = HexFormat.of().formatHex(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val server = ServerSocket(0, 4, InetAddress.getLoopbackAddress())
        File(folder, "instance.port").writeText("${server.localPort} $token")
        thread(isDaemon = true, name = "kone-instance") {
            while (true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    socket.use {
                        it.soTimeout = 3_000
                        val lines = it.getInputStream().bufferedReader().readText().lines()
                        if (lines.firstOrNull() == "KONE $token") lines.getOrNull(1)?.takeIf { line -> line.length <= 1024 }?.let(onMessage)
                    }
                }
            }
        }
        return true
    }

    private fun pass(message: String) {
        runCatching {
            val (port, token) = File(folder, "instance.port").readText().trim().split(' ')
            Socket(InetAddress.getLoopbackAddress(), port.toInt()).use { socket ->
                socket.getOutputStream().write("KONE $token\n$message\n".toByteArray())
            }
        }
    }
}
