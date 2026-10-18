package io.nekohasekai.sfa.compose.screen.dashboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.runInterruptible
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Proxy
import java.net.URL

/** A read-only check through this app's existing VPN; never falls back to Wi-Fi/mobile. */
internal class ExitIpDetector(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    internal fun currentVpn(): Network? {
        if (Build.VERSION.SDK_INT < 30) return null // Ownership cannot be verified on older Android.
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        return network.takeIf {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                capabilities.ownerUid == Process.myUid()
        }
    }

    fun networks() = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(currentVpn()) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(currentVpn()) }
            override fun onLost(network: Network) { trySend(currentVpn()) }
        }
        val request = NetworkRequest.Builder()
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        trySend(currentVpn())
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    suspend fun detect(network: Network): String? = runInterruptible(Dispatchers.IO) {
        if (currentVpn() != network) return@runInterruptible null
        val connection = network.openConnection(URL("https://api64.ipify.org"), Proxy.NO_PROXY) as HttpURLConnection
        try {
            connection.connectTimeout = 4_000
            connection.readTimeout = 4_000
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.setRequestProperty("Accept", "text/plain")
            if (connection.responseCode != 200) return@runInterruptible null
            // The endpoint returns only an IP literal. Cap the response before parsing.
            val text = connection.inputStream.bufferedReader(Charsets.US_ASCII).use { reader ->
                val buffer = CharArray(129)
                var size = 0
                while (size < buffer.size) {
                    val count = reader.read(buffer, size, buffer.size - size)
                    if (count < 0) break
                    size += count
                }
                String(buffer, 0, size)
            }
            if (currentVpn() != network) null else parseExitIp(text)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun parseExitIp(response: String): String? {
    val value = response.trim()
    if (value.isEmpty() || value.length > 45) return null
    val isV6 = ':' in value
    if (isV6) {
        if (value.any { it !in "0123456789abcdefABCDEF:." }) return null
    } else {
        val parts = value.split('.')
        if (parts.size != 4 || parts.any {
                it.isEmpty() || it.length > 3 || it.any { char -> char !in '0'..'9' } ||
                    (it.length > 1 && it.startsWith('0')) || it.toIntOrNull() !in 0..255
            }) return null
    }
    // Character validation above prevents DNS lookups for arbitrary hostnames.
    val address = runCatching { InetAddress.getByName(value) }.getOrNull() ?: return null
    if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress) return null
    val bytes = address.address
    if (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) return null
    if (bytes.size == 4) {
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        if (a == 0 || a >= 240 || (a == 100 && b in 64..127)) return null
    }
    return value
}
