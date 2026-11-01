package io.nekohasekai.sfa.compose.component

import android.content.Context
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.managed.ManagedFailure
import io.nekohasekai.sfa.managed.ManagedStatus

/** Translate fixed system states at the UI boundary; stored values and server text stay intact. */
internal fun Context.managedStatusText(status: ManagedStatus): String = getString(
    when (status) {
        ManagedStatus.Unbound -> R.string.kone_status_unbound
        ManagedStatus.Synced -> R.string.kone_status_synced
        ManagedStatus.Waiting -> R.string.kone_status_waiting
        ManagedStatus.Refused -> R.string.kone_status_refused
        ManagedStatus.Revoked -> R.string.kone_status_revoked
        ManagedStatus.Offline -> R.string.kone_status_offline
        ManagedStatus.Failed -> R.string.kone_status_failed
    },
)

internal fun Context.managedFailureText(failure: ManagedFailure): String = getString(
    when (failure) {
        ManagedFailure.Offline -> R.string.kone_status_offline
        ManagedFailure.Server -> R.string.kone_service_unavailable
        ManagedFailure.InviteInvalid -> R.string.kone_key_invalid
        ManagedFailure.DeviceLimit -> R.string.kone_device_limit
        ManagedFailure.Revoked -> R.string.kone_status_revoked
        ManagedFailure.Refused -> R.string.kone_status_refused
        ManagedFailure.Waiting -> R.string.kone_status_waiting
        ManagedFailure.ConfigInvalid -> R.string.kone_config_invalid
        ManagedFailure.ApplyFailed -> R.string.kone_config_apply_failed
        ManagedFailure.Stale -> R.string.kone_config_updated
    },
)

/** Only known client messages are translated; provider-authored reasons are not rewritten. */
internal fun Context.managedDetailText(detail: String): String {
    ManagedFailure.entries.firstOrNull { it.message == detail }?.let { return managedFailureText(it) }
    ManagedStatus.entries.firstOrNull { it.label == detail }?.let { return managedStatusText(it) }
    return detail
}
