package io.nekohasekai.sfa.selfuse

import android.content.Context
import androidx.annotation.StringRes
import io.nekohasekai.sfa.R

/** Resolves a validation failure to the active language; [PairedProfile] itself stays Android-free. */
object PairedProfileMessages {
    @StringRes
    fun resource(reason: PairedProfile.Reason): Int = when (reason) {
        PairedProfile.Reason.INVALID_LINK -> R.string.knine_paired_invalid_link
        PairedProfile.Reason.NOT_VLESS -> R.string.knine_paired_not_vless
        PairedProfile.Reason.INVALID_IDENTIFIER -> R.string.knine_paired_invalid_identifier
        PairedProfile.Reason.DUPLICATE_PARAMETER -> R.string.knine_paired_duplicate_parameter
        PairedProfile.Reason.UNSUPPORTED_TRANSPORT -> R.string.knine_paired_unsupported_transport
        PairedProfile.Reason.UNENCRYPTED -> R.string.knine_paired_unencrypted
        PairedProfile.Reason.MISSING_REALITY_KEY -> R.string.knine_paired_missing_reality_key
        PairedProfile.Reason.INVALID_REALITY_KEY -> R.string.knine_paired_invalid_reality_key
        PairedProfile.Reason.INVALID_SHORT_ID -> R.string.knine_paired_invalid_short_id
        PairedProfile.Reason.UNSUPPORTED_FLOW -> R.string.knine_paired_unsupported_flow
        PairedProfile.Reason.UNSUPPORTED_FINGERPRINT -> R.string.knine_paired_unsupported_fingerprint
        PairedProfile.Reason.MISSING_SERVER -> R.string.knine_paired_missing_server
        PairedProfile.Reason.INVALID_SERVER -> R.string.knine_paired_invalid_server
        PairedProfile.Reason.INVALID_PORT -> R.string.knine_paired_invalid_port
        PairedProfile.Reason.NOT_A_PROXY -> R.string.knine_paired_not_a_proxy
        PairedProfile.Reason.INVALID_CONFIGURATION -> R.string.knine_paired_invalid_configuration
        PairedProfile.Reason.TAILSCALE_MISSING -> R.string.knine_paired_tailscale_missing
        PairedProfile.Reason.NO_PROXY_NODE -> R.string.knine_paired_no_proxy_node
        PairedProfile.Reason.TUN_REQUIRED -> R.string.knine_paired_tun_required
        PairedProfile.Reason.NO_PRIVATE_RULE -> R.string.knine_paired_no_private_rule
        PairedProfile.Reason.RULE_OVERRIDES_HOME -> R.string.knine_paired_rule_overrides_home
        PairedProfile.Reason.PRIVATE_AFTER_GLOBAL -> R.string.knine_paired_private_after_global
    }

    fun message(context: Context, failure: PairedProfileException): String {
        val text = context.getString(resource(failure.reason))
        val detail = failure.detail?.takeIf { it.isNotBlank() } ?: return text
        return context.getString(R.string.knine_paired_detail_format, text, detail)
    }
}
