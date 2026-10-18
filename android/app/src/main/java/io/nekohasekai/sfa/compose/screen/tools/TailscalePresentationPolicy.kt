package io.nekohasekai.sfa.compose.screen.tools

import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.selfuse.PairedProfile

/** Keep local settings reachable even before an endpoint exists; never add them to remote control. */
internal fun tailscaleEntryTags(runtimeTags: List<String>, local: Boolean): List<String> =
    if (local) (listOf(PairedProfile.HOME_TAG) + runtimeTags).distinct() else runtimeTags.distinct()

internal fun tailscaleConnectionLabel(enabled: Boolean, service: Status, backendState: String?, hasUpdate: Boolean): String = when {
    !enabled -> "Off"
    service == Status.Stopped -> "Disconnected"
    service == Status.Stopping -> "Disconnecting"
    service == Status.Starting -> "Connecting"
    backendState == "Running" -> "Connected"
    backendState == "NeedsLogin" -> "Login required"
    backendState == "NeedsMachineAuth" -> "Approval required"
    backendState == "Starting" -> "Connecting"
    backendState == "Stopped" -> "Stopped"
    backendState == null && hasUpdate -> "Disabled in the current profile"
    else -> "Synchronizing status"
}
