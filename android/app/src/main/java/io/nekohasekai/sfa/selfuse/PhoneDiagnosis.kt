package io.nekohasekai.sfa.selfuse

import cc.txwd.kone.shared.Check
import cc.txwd.kone.shared.Checks
import cc.txwd.kone.shared.Outcome

/**
 * Diagnosis on a phone (Tools → Diagnose): the causes that are a phone's own, said in words
 * with the one thing to do, before anything about nodes. The network itself (open, holding
 * traffic for a sign-in page, or nothing at all), the clock, Android's Private DNS, battery
 * limits, Data Saver and another VPN app, then whether names resolve and sites answer the way
 * apps reach them. This part turns what was seen into checks and advice; [PhoneProbe] looks.
 */
object PhoneDiagnosis {
    const val NETWORK = "Network"
    const val PRIVATE_DNS = "Private DNS"
    const val BATTERY = "Battery"
    const val DATA_SAVER = "Data Saver"
    const val OTHER_VPN = "Other VPN"
    const val NAMES = "Names"
    const val SITES = "Sites"

    /** What Android says of the network under the VPN. */
    enum class Network { Open, SignIn, Unchecked, None }

    fun network(network: Network): Check = when (network) {
        Network.Open -> Check(NETWORK, Outcome.Pass, "Open")
        Network.SignIn -> Check(NETWORK, Outcome.Fail, "Wants you to sign in")
        Network.Unchecked -> Check(NETWORK, Outcome.Warn, "Connected, not confirmed by Android")
        Network.None -> Check(NETWORK, Outcome.Fail, "No network")
    }

    fun clock(skew: Long?): Check? = Checks.clock(skew)

    /** [hostname] when Private DNS is set to a provider ("" when Android did not say which). */
    fun privateDns(hostname: String?): Check = when (hostname) {
        null -> Check(PRIVATE_DNS, Outcome.Pass, "Off or automatic")
        else -> Check(PRIVATE_DNS, Outcome.Warn, hostname.ifEmpty { "A provider" })
    }

    fun battery(unrestricted: Boolean): Check =
        if (unrestricted) Check(BATTERY, Outcome.Pass, "Unrestricted") else Check(BATTERY, Outcome.Warn, "Optimized: Android may stop KOne in the background")

    /** Data Saver on, with KOne not allowed background data. */
    fun dataSaver(restricting: Boolean): Check =
        if (restricting) Check(DATA_SAVER, Outcome.Warn, "On, and KOne is not exempt") else Check(DATA_SAVER, Outcome.Pass, "Not in the way")

    /** Another app's VPN up while KOne is not running. */
    fun otherVpn(present: Boolean): Check =
        if (present) Check(OTHER_VPN, Outcome.Fail, "Another app's VPN is on") else Check(OTHER_VPN, Outcome.Pass, "None")

    /** How many of [names] resolved. */
    fun names(resolved: Int, of: Int): Check = when (resolved) {
        of -> Check(NAMES, Outcome.Pass, "$resolved of $of")
        0 -> Check(NAMES, Outcome.Fail, "None resolves")
        else -> Check(NAMES, Outcome.Warn, "$resolved of $of")
    }

    /** Each site's time to answer, or null when it did not. */
    fun sites(answers: List<Pair<String, Int?>>): Check {
        val answered = answers.filter { it.second != null }
        val detail = answers.joinToString(" · ") { (name, millis) -> if (millis == null) "$name —" else "$name $millis ms" }
        return when (answered.size) {
            answers.size -> Check(SITES, Outcome.Pass, detail)
            0 -> Check(SITES, Outcome.Fail, detail)
            else -> Check(SITES, Outcome.Warn, detail)
        }
    }

    /**
     * The one thing to do, for the first cause that explains the rest: the network, the clock and
     * another VPN come before anything KOne decides, then what Android does to KOne, then names
     * and sites. Null when nothing needs doing.
     */
    fun advice(checks: List<Check>): String? {
        fun failed(title: String) = checks.firstOrNull { it.title == title && it.outcome == Outcome.Fail }
        fun warned(title: String) = checks.firstOrNull { it.title == title && it.outcome != Outcome.Pass && it.outcome != Outcome.Running }
        failed(NETWORK)?.let {
            return if (it.detail.startsWith("Wants")) "Open the network's sign-in page from Android's notification, then connect again" else "Turn on Wi-Fi or mobile data"
        }
        failed(Checks.CLOCK)?.let { return "The phone's clock is ${it.detail}: turn on Set time automatically (Settings → System → Date & time)" }
        failed(OTHER_VPN)?.let { return "Turn the other VPN app off: Android lets one VPN run at a time" }
        val sites = checks.firstOrNull { it.title == SITES }
        val names = checks.firstOrNull { it.title == NAMES }
        if (names?.outcome == Outcome.Fail) {
            return if (warned(PRIVATE_DNS) != null) "Set Private DNS to Automatic: its provider is not answering through KOne" else "Names do not resolve: try another DNS server in Settings → Routing"
        }
        if (sites?.outcome == Outcome.Fail) return "Nothing answers: try another node, or Test all"
        warned(BATTERY)?.let { return "Let KOne run unrestricted in the background (Settings → Service), so Android does not stop it" }
        warned(DATA_SAVER)?.let { return "Allow KOne unrestricted data in Data Saver, so it keeps working in the background" }
        warned(PRIVATE_DNS)?.let { return "Set Private DNS to Automatic, so KOne's DNS choices apply" }
        if (sites?.outcome == Outcome.Warn) return "Some sites do not answer: check your Routing rules"
        return null
    }
}
