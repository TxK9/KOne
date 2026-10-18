package io.nekohasekai.sfa.compose.theme

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.dashboard.ExitIpDetector
import io.nekohasekai.sfa.compose.screen.dashboard.ProfileSelectorButton
import io.nekohasekai.sfa.database.Profile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
class TopIpVisualTest {
    @get:Rule val compose = createComposeRule()

    @Test fun darkIpIsSmallAndBelowTheOriginalProfileName() = renderIp(R.drawable.portrait_backdrop, "top-ip-dark")
    @Test fun brightIpRetainsTheExistingAppearance() = renderIp(R.drawable.owner_wallpaper_2, "top-ip-bright")

    private fun renderIp(wallpaper: Int, name: String) {
        val ready = AtomicBoolean(false)
        compose.setContent {
            Theme(dynamicColor = false, portraitBackdrop = true, backdropRes = wallpaper) {
                val backdrop = LocalBackdrop.current
                SideEffect { ready.set(backdrop?.pixels != null) }
                Box(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 108.dp)) {
                    BackdropCard {
                        ProfileSelectorButton(Profile(id = 1, name = "Verizon"), {}, Modifier.fillMaxWidth().padding(16.dp), "203.0.113.12")
                    }
                }
            }
        }
        compose.waitUntil(15_000) { ready.get() }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        val profile = compose.onNodeWithText("Verizon", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val ip = compose.onNodeWithText("203.0.113.12", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(ip.top >= profile.bottom)
        assertTrue(ip.height < profile.height)
        val output = File(System.getProperty("knine.previewDir"), "$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun missingIpLeavesNoPlaceholderAndIpv6FitsBelowTheName() {
        var ip by mutableStateOf<String?>(null)
        compose.setContent {
            Theme(dynamicColor = false, plainKnine = true) {
                ProfileSelectorButton(Profile(id = 1, name = "Verizon"), {}, Modifier.padding(32.dp), ip)
            }
        }
        compose.onNodeWithText("Verizon").assertIsDisplayed()
        compose.onNodeWithText("Not tested").assertDoesNotExist()
        compose.runOnIdle { ip = "2001:db8:1234:5678:90ab:cdef:1234:5678" }
        val text = compose.onNodeWithText(ip!!, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(text.left >= 0 && text.right <= compose.onRoot().fetchSemanticsNode().boundsInRoot.right)
        compose.runOnIdle { ip = null }
        compose.onNodeWithText("2001:db8:1234:5678:90ab:cdef:1234:5678").assertDoesNotExist()
        compose.onNodeWithText("Not tested").assertDoesNotExist()
    }

    @Test fun detectorRejectsPhysicalAndOtherAppsVpnNetworks() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = requireNotNull(manager.activeNetwork)
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        ReflectionHelpers.setField(capabilities, "mOwnerUid", Process.myUid())
        shadowOf(manager).setNetworkCapabilities(network, capabilities)
        val detector = ExitIpDetector(context)
        assertNull(detector.currentVpn())
        shadowOf(capabilities).removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN)
        ReflectionHelpers.setField(capabilities, "mOwnerUid", Process.myUid() + 1)
        assertNull(detector.currentVpn())
        ReflectionHelpers.setField(capabilities, "mOwnerUid", Process.myUid())
        assertEquals(network, detector.currentVpn())
    }
}
