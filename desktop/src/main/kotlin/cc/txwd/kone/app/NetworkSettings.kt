package cc.txwd.kone.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import cc.txwd.kone.core.Dns
import cc.txwd.kone.core.Host
import cc.txwd.kone.core.Hosts
import cc.txwd.kone.core.NameServer
import cc.txwd.kone.core.NameServers
import cc.txwd.kone.ui.NetworkState

/**
 * Settings' network part: the remote and direct DNS servers, hosts, DNS for domains, IPv6 and
 * failover, each checked, kept in [preferences] at once and, where the template uses it, taken
 * by a running connection through [restart].
 */
class NetworkSettings(private val preferences: Preferences, private val restart: () -> Unit) {
    /** The settings as kept, for the page. */
    var page by mutableStateOf(now())
        private set

    /** Reads the page again from what is kept (after a restore). */
    fun show() {
        page = now()
    }

    private fun now() = NetworkState(
        preferences.remoteDns, preferences.directDns, preferences.ipv6, preferences.failover, preferences.hosts, preferences.nameServers, preferences.region,
    )

    private fun changed() {
        show()
        restart()
    }

    /** Kept only: failover is the App's own doing, not the template's. */
    fun setFailover(on: Boolean) {
        preferences.failover = on
        show()
    }

    /** A remote DNS choice; false when it is neither a preset nor a DNS-over-HTTPS address KOne takes. */
    fun setRemoteDns(choice: String): Boolean {
        val clean = Dns.remoteChoice(choice)
        if (clean.isEmpty() && choice.isNotBlank()) return false
        if (clean == preferences.remoteDns) return true
        preferences.remoteDns = clean
        changed()
        return true
    }

    /** A direct DNS choice; false when it is neither a preset nor an address. */
    fun setDirectDns(choice: String): Boolean {
        val clean = Dns.directChoice(choice)
        if (clean.isEmpty() && choice.isNotBlank()) return false
        if (clean == preferences.directDns) return true
        preferences.directDns = clean
        changed()
        return true
    }

    /** A fixed answer typed as "name address" (or a hosts file's "address name"); false when it is not one. */
    fun addHost(line: String): Boolean {
        val host = Hosts.parse(line) ?: return false
        val before = preferences.hosts
        if (host in before) return true
        if (Hosts.clean(before + host).sumOf { it.v4.size + it.v6.size } == before.size) return false
        preferences.hosts = before + host
        changed()
        return true
    }

    fun removeHost(host: Host) {
        val before = preferences.hosts
        if (host !in before) return
        preferences.hosts = before - host
        changed()
    }

    /** A server of the person's own for a domain, typed as "domain server"; false when it is not one or there are 16 already. */
    fun addNameServer(line: String): Boolean {
        val entry = NameServers.parse(line) ?: return false
        val before = preferences.nameServers
        if (entry in before) return true
        if (NameServers.flatten(NameServers.clean(before + entry)).size == before.size) return false
        preferences.nameServers = before + entry
        changed()
        return true
    }

    fun removeNameServer(entry: NameServer) {
        val before = preferences.nameServers
        if (entry !in before) return
        preferences.nameServers = before - entry
        changed()
    }

    /** Where the person is: decides the China split and the default direct DNS. */
    fun setRegion(region: String) {
        if (cc.txwd.kone.shared.Region.clean(region) == preferences.region) return
        preferences.region = region
        // A preset straight-out server follows the region to its default; an address of the person's own stays.
        if (preferences.directDns in cc.txwd.kone.shared.Region.DIRECT) preferences.directDns = ""
        changed()
    }

    fun setIpv6(on: Boolean) {
        preferences.ipv6 = on
        changed()
    }
}
