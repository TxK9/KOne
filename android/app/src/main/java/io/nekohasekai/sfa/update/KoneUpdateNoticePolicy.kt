package io.nekohasekai.sfa.update

/** Pure notice policy; no network, Android permission request, VPN or installer work. */
internal object KoneUpdateNoticePolicy {
    fun releaseKey(versionCode: Int, sha256: String): String = "$versionCode:${sha256.lowercase()}"

    fun notificationKey(releaseKey: String, confirmationSession: Int?): String =
        if (confirmationSession != null && confirmationSession >= 0) "$releaseKey:confirmation:$confirmationSession"
        else "$releaseKey:ready"

    fun shouldPrompt(key: String, eligible: Boolean, prompted: Set<String>, dismissed: Set<String>): Boolean =
        eligible && key !in prompted && key !in dismissed

    fun shouldNotify(key: String, notice: String, ready: Boolean, foreground: Boolean, allowed: Boolean,
                     notified: Set<String>, dismissed: Set<String>): Boolean =
        ready && !foreground && allowed && notice !in notified && key !in dismissed
}
