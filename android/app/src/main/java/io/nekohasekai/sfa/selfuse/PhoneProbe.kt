package io.nekohasekai.sfa.selfuse

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import cc.txwd.kone.shared.Check
import cc.txwd.kone.shared.Checks
import cc.txwd.kone.shared.Region
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * What [PhoneDiagnosis] judges, looked at on the phone. The network under the VPN is read from
 * Android itself, which has already checked it (validated, or holding traffic for a sign-in
 * page); names and sites are tried the way apps reach them, through KOne when it runs. Each check
 * is handed to [show] as it finishes.
 */
object PhoneProbe {
    suspend fun run(context: Context, koneRunning: Boolean, show: (List<Check>) -> Unit): List<Check> = coroutineScope {
        val checks = mutableListOf<Check>()
        fun add(check: Check?) {
            check ?: return
            checks += check
            show(checks.toList())
        }
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        add(PhoneDiagnosis.network(network(connectivity)))
        add(PhoneDiagnosis.otherVpn(!koneRunning && otherVpn(connectivity)))
        add(PhoneDiagnosis.privateDns(PrivateDns.hostname(context)))
        val power = context.getSystemService(PowerManager::class.java)
        add(PhoneDiagnosis.battery(power?.isIgnoringBatteryOptimizations(context.packageName) == true))
        add(PhoneDiagnosis.dataSaver(connectivity?.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED))

        val region = RoutingSettings.current().region
        val names = Region.checkNames(region)
        val resolved = names.map { name ->
            async(Dispatchers.IO) { withTimeoutOrNull(5_000) { runCatching { InetAddress.getAllByName(name).isNotEmpty() }.getOrDefault(false) } == true }
        }.awaitAll().count { it }
        add(PhoneDiagnosis.names(resolved, names.size))

        var date: String? = null
        val answers = Region.checkSites(region).map { (name, address) ->
            async(Dispatchers.IO) {
                val (millis, heard) = fetch(address)
                if (heard != null && date == null) date = heard
                name to millis
            }
        }.awaitAll()
        // The clock against the first site that answered, before the sites themselves.
        add(PhoneDiagnosis.clock(date?.let { Checks.skew(it) }))
        add(PhoneDiagnosis.sites(answers))
        checks
    }

    /** What Android says of the network under the VPN: validated, a sign-in page, connected only, or none. */
    @Suppress("DEPRECATION")
    private fun network(connectivity: ConnectivityManager?): PhoneDiagnosis.Network {
        connectivity ?: return PhoneDiagnosis.Network.None
        val underlying = connectivity.allNetworks.mapNotNull { connectivity.getNetworkCapabilities(it) }
            .filter { !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) }
        return when {
            underlying.isEmpty() -> PhoneDiagnosis.Network.None
            underlying.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) } -> PhoneDiagnosis.Network.SignIn
            underlying.any { it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } -> PhoneDiagnosis.Network.Open
            else -> PhoneDiagnosis.Network.Unchecked
        }
    }

    @Suppress("DEPRECATION")
    private fun otherVpn(connectivity: ConnectivityManager?): Boolean =
        connectivity?.allNetworks?.any { connectivity.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true } == true

    /** How long [address] took to answer, in milliseconds (null for no answer), and the Date it gave. */
    private fun fetch(address: String): Pair<Int?, String?> {
        val started = System.nanoTime()
        return runCatching {
            val connection = URL(address).openConnection() as HttpURLConnection
            connection.connectTimeout = 6_000
            connection.readTimeout = 6_000
            connection.instanceFollowRedirects = false
            try {
                connection.responseCode
                ((System.nanoTime() - started) / 1_000_000).toInt() to connection.getHeaderField("Date")
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(null to null)
    }

    /** [run] on the IO dispatcher. */
    suspend fun diagnose(context: Context, koneRunning: Boolean, show: (List<Check>) -> Unit) =
        withContext(Dispatchers.IO) { run(context, koneRunning, show) }
}
