package io.nekohasekai.sfa.selfuse

import android.content.Context
import io.nekohasekai.sfa.R

/**
 * Maps the English exception messages thrown by [PairedProfile] and [InitialRuleSets]
 * to localized display strings. The exception texts themselves are never changed,
 * because other code may match on them; translation happens only where a message
 * is shown to the user. Unknown messages pass through unchanged.
 */
object KoneErrorMessages {
    private val messageToResource: Map<String, Int> = mapOf(
        "Invalid connection link" to R.string.knine_error_invalid_link,
        "Import a VLESS link; use a complete sing-box configuration for other protocols" to R.string.knine_error_non_vless,
        "Invalid connection identifier" to R.string.knine_error_invalid_id,
        "Connection server is missing" to R.string.knine_error_no_server,
        "Invalid connection port" to R.string.knine_error_invalid_port,
        "Connection contains duplicate parameters" to R.string.knine_error_dup_param,
        "Import this transport using a complete configuration" to R.string.knine_error_transport,
        "Unsupported VLESS flow" to R.string.knine_error_bad_flow,
        "The paired configuration requires an encrypted VLESS connection" to R.string.knine_error_unencrypted,
        "Reality public key is missing" to R.string.knine_error_no_pbk,
        "Invalid Reality public key format" to R.string.knine_error_bad_pbk,
        "Invalid Reality short ID format" to R.string.knine_error_bad_sid,
        "An actual proxy node is required." to R.string.knine_error_no_node,
        "Invalid configuration format" to R.string.knine_error_bad_config,
        "Tailscale is disabled in this profile. Create a paired Proxy and Home profile first." to R.string.knine_error_no_tailscale,
        "Configuration has no actual proxy node" to R.string.knine_error_no_proxy,
        "Paired profiles require VPN/TUN routing" to R.string.knine_error_no_tun,
        "Configuration has no Tailscale private-network rule" to R.string.knine_error_no_private_rule,
        "A rule before the private-network rule would override Home traffic." to R.string.knine_error_rule_order,
        "Private-network rules must precede global proxy rules." to R.string.knine_error_global_order,
        "Built-in rule-set verification failed" to R.string.knine_error_ruleset_verify,
        "Cannot save the built-in rule set" to R.string.knine_error_ruleset_save,
    )

    /** Returns the localized form of [message], or [message] itself when unknown. Null stays null. */
    fun resolve(context: Context, message: String?): String? {
        if (message == null) return null
        val resId = messageToResource[message] ?: return message
        return context.getString(resId)
    }
}
