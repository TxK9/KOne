package cc.txwd.kone.shared

/**
 * Where the person is, which decides two defaults. In China the templates send Chinese sites and
 * addresses straight out (the China lists) and ask AliDNS for names that stay out of the tunnel.
 * Anywhere else there is no China split, and the direct server is Cloudflare's, so nobody abroad
 * has their lookups sent to a Chinese resolver. The Windows service's Go template
 * (desktop/service/dns.go, plan.go) reads regions the same way.
 */
object Region {
    const val CHINA = "china"
    const val GLOBAL = "global"

    /** Whether [region] is China: anything but [GLOBAL] is, as before regions existed. */
    fun china(region: String) = region != GLOBAL

    /** The region as kept: [GLOBAL] or [CHINA]. */
    fun clean(region: String) = if (china(region)) CHINA else GLOBAL

    /** The DNS servers asked straight out, by preset: the Chinese ones, and the public ones that serve everywhere else. */
    val DIRECT = linkedMapOf(
        "alidns" to "223.5.5.5", "dnspod" to "119.29.29.29",
        "cloudflare" to "1.1.1.1", "google" to "8.8.8.8", "quad9" to "9.9.9.9",
    )

    /** The direct presets offered in [region], the default first. */
    fun directPresets(region: String): List<String> =
        if (china(region)) listOf("alidns", "dnspod", "cloudflare", "google", "quad9") else listOf("cloudflare", "google", "quad9")

    /** The direct server when none is chosen. */
    fun directDefault(region: String) = if (china(region)) "223.5.5.5" else "1.1.1.1"
}
