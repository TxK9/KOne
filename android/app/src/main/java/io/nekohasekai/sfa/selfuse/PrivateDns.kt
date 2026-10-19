package io.nekohasekai.sfa.selfuse

import android.content.Context
import android.provider.Settings

/**
 * Android's Private DNS (Settings → Network → Private DNS). Set to a provider's hostname, Android
 * sends apps' lookups to that provider over DNS-over-TLS, past KOne's DNS: the DNS choices and the
 * China split then no longer decide how names are answered, while routing by the names sites send
 * (and the Claude block) still holds. Off or Automatic leaves KOne's DNS in charge.
 */
object PrivateDns {
    /** The provider's hostname when Private DNS is set to one; null when it is off or automatic. */
    fun hostname(context: Context): String? {
        val resolver = context.contentResolver
        val mode = runCatching { Settings.Global.getString(resolver, "private_dns_mode") }.getOrNull()
        if (mode != "hostname") return null
        return runCatching { Settings.Global.getString(resolver, "private_dns_specifier") }.getOrNull()?.takeIf { it.isNotBlank() } ?: ""
    }
}
