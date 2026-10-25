package io.nekohasekai.sfa.connection

import io.nekohasekai.sfa.database.Settings
import io.nekohasekai.sfa.vendor.Vendor

/** The same app exceptions must be used when checking an option and when starting its service. */
internal data class AppPolicySnapshot(
    val directPackages: Set<String> = emptySet(),
    val proxyPackages: Set<String>? = null,
) {
    companion object {
        fun current(applicationPackage: String): AppPolicySnapshot {
            val enabled = Vendor.isPerAppProxyAvailable() && Settings.perAppProxyEnabled
            return from(
                enabled = enabled,
                packages = if (enabled) Settings.getEffectivePerAppProxyList().toSet() else emptySet(),
                includeOnly = enabled && Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE,
                applicationPackage = applicationPackage,
            )
        }

        fun from(
            enabled: Boolean,
            packages: Set<String>,
            includeOnly: Boolean,
            applicationPackage: String,
        ): AppPolicySnapshot = when {
            !enabled -> AppPolicySnapshot()
            includeOnly -> AppPolicySnapshot(proxyPackages = packages + applicationPackage)
            else -> AppPolicySnapshot(directPackages = packages - applicationPackage)
        }
    }
}

/** Pure final transformation, after HomeSettings.prepare; also used for pre-save native checks. */
internal object RuntimeConnectionConfig {
    fun prepare(homePreparedContent: String, calling: CallingOptions, appPolicy: AppPolicySnapshot): String =
        VideoCompatibility.apply(GlobalRoutingPolicy.apply(
            ConnectionCompatibility.apply(homePreparedContent, calling),
            strict = true,
            directPackages = appPolicy.directPackages,
            proxyPackages = appPolicy.proxyPackages,
        ), calling.videoTcp)
}
