package cc.txwd.kone.core

import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.concurrent.thread

/**
 * Where the running core sends a site now, and by which rule, asked of the core itself rather
 * than worked out beside it: KOne opens a connection to the site the way programs do (through
 * its adapter, or Windows' proxy at the core's port) and finds it in the core's own list while
 * it is open. The core's answer is the answer; nothing here imitates its rules.
 */
object RouteProbe {
    sealed interface Found {
        /** The core carries it: [connection] says which way and by which rule. */
        data class Carried(val connection: CoreConnection) : Found

        /** The name did not resolve: refused by a rule, or it has none. */
        data object NoName : Found

        /** The core never listed it: refused at once, or not reached through KOne. */
        data object Unseen : Found
    }

    /** This process's file name (KOne.exe, or java when run from the build), to find its own connection first. */
    private val own: String? = ProcessHandle.current().info().command().map { File(it).name }.orElse(null)

    fun find(site: String, viaPort: Int?, api: ClashApi, waitMillis: Long = 3_000): Found {
        val socket = Socket()
        try {
            if (viaPort != null) {
                socket.connect(InetSocketAddress("127.0.0.1", viaPort), 3_000)
                socket.getOutputStream().apply { write("CONNECT $site:443 HTTP/1.1\r\nHost: $site:443\r\n\r\n".toByteArray()); flush() }
            } else {
                socket.connect(InetSocketAddress(site, 443), 3_000)
                // The core learns the name from the TLS greeting; how the handshake ends does not matter.
                val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket, site, 443, false) as SSLSocket
                tls.soTimeout = waitMillis.toInt()
                thread(isDaemon = true, name = "route-probe") { runCatching { tls.startHandshake() } }
            }
            val until = System.currentTimeMillis() + waitMillis
            while (System.currentTimeMillis() < until) {
                val listed = api.connections()?.connections.orEmpty().filter { it.target.equals("$site:443", ignoreCase = true) }
                (listed.firstOrNull { own != null && it.program.equals(own, ignoreCase = true) } ?: listed.firstOrNull())?.let { return Found.Carried(it) }
                Thread.sleep(150)
            }
            return Found.Unseen
        } catch (_: UnknownHostException) {
            return Found.NoName
        } catch (_: IOException) {
            return Found.Unseen
        } finally {
            runCatching { socket.close() }
        }
    }
}
