package io.nekohasekai.sfa.connection

import android.net.VpnService
import android.os.Build

internal enum class VpnProtectionReadability { Available, Unsupported, Unavailable }
internal data class VpnProtectionSnapshot(
    val readable: VpnProtectionReadability,
    val alwaysOn: Boolean? = null,
    val lockdown: Boolean? = null,
)

/** Android owns these switches; an app cannot enable them on its own. */
object SystemVpnProtection {
    @Volatile private var owner: VpnService? = null

    fun attach(service: VpnService) { owner = service }
    fun detach(service: VpnService) { if (owner === service) owner = null }

    fun enabled(): Boolean? {
        val service = owner ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        return runCatching { service.isAlwaysOn && service.isLockdownEnabled }.getOrNull()
    }

    /** Read each switch separately. Unsupported and unavailable must not look like disabled. */
    internal fun snapshot(): VpnProtectionSnapshot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return VpnProtectionSnapshot(VpnProtectionReadability.Unsupported)
        val service = owner ?: return VpnProtectionSnapshot(VpnProtectionReadability.Unavailable)
        return runCatching { VpnProtectionSnapshot(VpnProtectionReadability.Available, service.isAlwaysOn, service.isLockdownEnabled) }
            .getOrDefault(VpnProtectionSnapshot(VpnProtectionReadability.Unavailable))
    }
}
