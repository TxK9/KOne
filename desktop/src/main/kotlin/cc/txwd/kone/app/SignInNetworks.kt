package cc.txwd.kone.app

import cc.txwd.kone.core.NetworkReach
import cc.txwd.kone.core.Probe
import cc.txwd.kone.core.Reach
import cc.txwd.kone.core.ReachCheck
import cc.txwd.kone.ui.SignInState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ServerSocket
import java.util.concurrent.TimeUnit

/**
 * Sign-in networks (hotels, airports, cafés): noticing a network that holds traffic for its
 * sign-in page, and the one press that steps KOne aside, opens the page, and connects again once
 * the network is open. What it needs of the rest of the App is [Host].
 */
class SignInNetworks(private val scope: CoroutineScope, private val host: Host) {
    interface Host {
        /** KOne's adapter carries everything now (so the check goes through a core of KOne's own). */
        val adapterOn: Boolean
        /** Not connected, nor connecting. */
        val idle: Boolean
        /** The person wants to be connected. */
        val wanted: Boolean
        /** Connects, or lets the connection go, as the power button does. */
        fun power()
        fun refresh()
        fun log(line: String)
        /** Said by Windows when the window is away. */
        fun notify(text: String)
        /** A line that went well, for a few seconds. */
        fun hint(text: String)
        /** A line on what went wrong, until the next try. */
        fun notice(text: String)
        /** Opens a page in the person's browser. */
        fun browse(page: String)
    }

    /** The network holding traffic for its sign-in page, and whether KOne is waiting for the person to sign in. */
    var state: SignInState? = null
        private set
    private var waiting: Job? = null
    private var asked = 0L

    /**
     * Asks the network itself whether it is open. While KOne's adapter carries everything, through
     * a core of KOne's own that the template sends straight out; otherwise straight from the App.
     * Null when it could not ask.
     */
    suspend fun reach(): ReachCheck? = withContext(Dispatchers.IO) {
        if (!host.adapterOn) return@withContext NetworkReach.check()
        val core = Places.core ?: return@withContext null
        val port = ServerSocket(0).use { it.localPort }
        val helper = Probe(core)
        try {
            val config = """{"log":{"level":"error"},"inbounds":[{"type":"mixed","listen":"127.0.0.1","listen_port":$port}],"outbounds":[{"type":"direct"}]}"""
            if (!helper.start(config)) return@withContext null
            var up = false
            for (i in 0 until 30) {
                up = runCatching { java.net.Socket("127.0.0.1", port).close() }.isSuccess
                if (up) break
                Thread.sleep(100)
            }
            if (up) NetworkReach.check(port) else null
        } finally {
            helper.stop()
        }
    }

    /** After a failure: whether the network wants a sign-in, at most once a minute; said once, with the way to sign in. */
    fun look() {
        val now = System.currentTimeMillis()
        if (now - asked < 60_000 || waiting?.isActive == true) return
        asked = now
        scope.launch {
            val check = reach() ?: return@launch
            if (check.reach == Reach.SignIn) {
                if (state == null) {
                    host.log("The network is holding traffic for its sign-in page")
                    host.notify("This network needs you to sign in")
                }
                state = SignInState(check.page ?: NetworkReach.ANY_PAGE, waiting = false)
            } else if (state?.waiting == false) {
                state = null
            }
            host.refresh()
        }
    }

    /**
     * Sign in: KOne steps aside (its tunnel would carry the sign-in page away), opens the page,
     * and once the network is open, connects again if it was connected, for up to 15 minutes.
     */
    fun signIn() {
        val shown = state ?: return
        val reconnect = host.wanted || !host.idle
        if (!host.idle) host.power()
        state = shown.copy(waiting = true)
        host.refresh()
        host.browse(shown.page)
        waiting?.cancel()
        waiting = scope.launch {
            val until = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(15)
            delay(3_000)
            while (System.currentTimeMillis() < until) {
                if (withContext(Dispatchers.IO) { NetworkReach.check() }.reach == Reach.Open) {
                    state = null
                    host.hint("Signed in to the network")
                    host.refresh()
                    if (reconnect && host.idle) host.power()
                    return@launch
                }
                delay(5_000)
            }
            state = null
            host.notice("The network is still waiting for a sign-in")
            host.refresh()
        }
    }

    fun cancel() {
        waiting?.cancel()
        state = null
        host.refresh()
    }

    /** The person connects themselves: any waiting stops. */
    fun connecting() {
        waiting?.cancel()
        state = null
    }

    /** The node answers again: a sign-in that was only offered is put away. */
    fun answered() {
        if (state?.waiting == false) {
            state = null
            host.refresh()
        }
    }
}
