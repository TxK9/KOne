package io.nekohasekai.sfa.compose.screen.tools

import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.selfuse.PairedProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class TailscalePresentationPolicyTest {
    @Test fun localEntrySurvivesStopAndDoesNotDuplicateOnReconnect() {
        val home = PairedProfile.HOME_TAG
        assertEquals(listOf(home), tailscaleEntryTags(emptyList(), local = true))
        assertEquals(listOf(home), tailscaleEntryTags(listOf(home), local = true))
        assertEquals(listOf(home, "custom"), tailscaleEntryTags(listOf("custom", home), local = true))
    }

    @Test fun remoteControlNeverInventsALocalSettingsEndpoint() {
        assertEquals(emptyList<String>(), tailscaleEntryTags(emptyList(), local = false))
        assertEquals(listOf("remote"), tailscaleEntryTags(listOf("remote"), local = false))
    }

    @Test fun staleRunningStatusCannotClaimConnectionAfterDisableOrStop() {
        assertEquals("Off", tailscaleConnectionLabel(false, Status.Started, "Running", true))
        assertEquals("Disconnected", tailscaleConnectionLabel(true, Status.Stopped, "Running", true))
        assertEquals("Disconnecting", tailscaleConnectionLabel(true, Status.Stopping, "Running", true))
        assertEquals("Connecting", tailscaleConnectionLabel(true, Status.Starting, null, false))
    }

    @Test fun missingEndpointDistinguishesSyncFromUnsupportedConfiguration() {
        assertEquals("Synchronizing status", tailscaleConnectionLabel(true, Status.Started, null, false))
        assertEquals("Disabled in the current profile", tailscaleConnectionLabel(true, Status.Started, null, true))
        assertEquals("Login required", tailscaleConnectionLabel(true, Status.Started, "NeedsLogin", true))
        assertEquals("Approval required", tailscaleConnectionLabel(true, Status.Started, "NeedsMachineAuth", true))
        assertEquals("Connected", tailscaleConnectionLabel(true, Status.Started, "Running", true))
    }
}
