package cc.txwd.kone.app

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import kotlin.concurrent.thread

/**
 * The computer's network changing under KOne: Wi-Fi to a cable, one hotspot to another, an
 * address lost. Windows says when an IPv4 address changes (NotifyAddrChange, waited on in a
 * thread of its own, so nothing is polled); KOne then compares the addresses that carry traffic
 * out with those before, and calls [onChange] only when they differ. KOne's own adapter coming
 * and going as it connects is not a change of network, and IPv6 addresses are left out: their
 * private addresses change by themselves every few hours.
 */
class NetworkChanges(private val onChange: (Change) -> Unit) {
    /** Addresses gone, which the connections made over them cannot outlive, and addresses new. */
    data class Change(val lost: Set<String>, val gained: Set<String>)

    fun start() {
        if (!Places.windows) return
        thread(isDaemon = true, name = "kone-network") {
            var before = addresses(Conflicts.adapters())
            while (true) {
                // Blocks until an address changes; a failure ends the watching rather than spin.
                if (runCatching { Iphlpapi.INSTANCE.NotifyAddrChange(null, null) }.getOrDefault(-1) != 0) return@thread
                // A network arriving changes several things in a row (address, gateway, DNS): let it settle.
                Thread.sleep(SETTLE_MILLIS)
                val after = addresses(Conflicts.adapters())
                change(before, after)?.let(onChange)
                before = after
            }
        }
    }

    companion object {
        const val SETTLE_MILLIS = 1500L

        /**
         * The IPv4 addresses traffic can leave by: those of adapters that are up, but not the
         * computer's own (127.x), not a network that never answered DHCP (169.254.x), and not KOne's
         * adapter (which holds the template's 172.19.0.1).
         */
        fun addresses(adapters: List<Conflicts.Adapter>): Set<String> = adapters.asSequence()
            .filter { it.up && Conflicts.OWN_ADDRESS !in it.addresses }
            .flatMap { it.addresses.asSequence() }
            .filter { ':' !in it && !it.startsWith("127.") && !it.startsWith("169.254.") }
            .toSet()

        /** What changed from [before] to [after], or null when nothing did. */
        fun change(before: Set<String>, after: Set<String>): Change? =
            if (before == after) null else Change(lost = before - after, gained = after - before)
    }

    private interface Iphlpapi : StdCallLibrary {
        fun NotifyAddrChange(handle: Pointer?, overlapped: Pointer?): Int

        companion object {
            val INSTANCE: Iphlpapi by lazy { Native.load("iphlpapi", Iphlpapi::class.java) }
        }
    }
}
