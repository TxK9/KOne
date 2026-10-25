package io.nekohasekai.sfa.udp

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import io.nekohasekai.sfa.compose.screen.tools.UdpTestPanel
import io.nekohasekai.sfa.compose.screen.tools.CallingOptionsEditor
import io.nekohasekai.sfa.connection.CallingOptions
import io.nekohasekai.sfa.connection.CallingDns
import io.nekohasekai.sfa.compose.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application=Application::class,sdk=[35],qualifiers="zh-rCN-w320dp-h720dp-xhdpi")
class UdpPanelTest {
    @get:Rule val compose=createComposeRule()
    private val target=UdpTarget(7,"Current node","node",3,40,false)
    @Test fun lightAndDarkResultsStayCompactAndNeverClaimWifiCallingRegistered() {
        var dark by mutableStateOf(false)
        var state by mutableStateOf(UdpCheckState(target))
        var starts=0;var settings=0
        var calling by mutableStateOf(CallingOptions())
        compose.setContent {
            val wallpaper=WallpaperTheme(dark)
            val paper=Color(wallpaper.readingSurface);val ink=Color(wallpaper.ink)
            val scheme=(if(dark) darkColorScheme() else lightColorScheme()).copy(surface=paper,onSurface=ink,primary=ink,onPrimary=paper,background=paper,onBackground=ink)
            MaterialTheme(colorScheme=scheme) {
                CompositionLocalProvider(LocalBackdrop provides BackdropContext(null,Rect(0f,0f,640f,1440f),theme=wallpaper),LocalContentColor provides ink) {
                    Surface(color=paper) {
                        UdpTestPanel("Current node",state,"Connect to this node first",{starts++},{},{settings++},
                            connectionSettings={CallingOptionsEditor(calling,false,""){calling=it}})
                    }
                }
            }
        }
        val variants=listOf(
            UdpCheckState(target) to "Start",
            UdpCheckState(target,UdpPhase.Running) to "Testing",
            UdpCheckState(target,UdpPhase.Passed,UdpMeasurement(true,UdpReply("203.0.113.10:54321",210),1)) to "Available",
            UdpCheckState(target,UdpPhase.Failed,UdpMeasurement(false,attempts=2)) to "No reply")
        for(d in listOf(false,true)) for((index,pair) in variants.withIndex()) {
            compose.runOnIdle{dark=d;state=pair.first};compose.waitForIdle()
            compose.onNodeWithText(pair.second).assertIsDisplayed()
            compose.onNodeWithText("Wi-Fi Calling connected").assertDoesNotExist()
            val out=File(System.getProperty("knine.previewDir"),"udp-${if(d) "dark" else "light"}-$index.png")
            out.parentFile!!.mkdirs()
            out.outputStream().use{compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        compose.onNodeWithText("Test again").performClick();assertEquals(1,starts)
        compose.onNodeWithText("Open system settings").assertDoesNotExist()
        compose.onNodeWithText("Wi-Fi Calling").performClick()
        compose.onNodeWithText("Call compatibility").performScrollTo().assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        compose.onNodeWithText("Advanced options").performScrollTo().performClick()
        compose.onNodeWithText("DNS").performScrollTo().performClick()
        compose.onNodeWithText("Prefer IPv6").performClick()
        compose.runOnIdle { assertEquals(CallingDns.PreferIpv6,calling.dns) }
        for(d in listOf(false,true)) {
            compose.runOnIdle{dark=d};compose.waitForIdle()
            compose.onNodeWithText("Call compatibility").performScrollTo()
            val out=File(System.getProperty("knine.previewDir"),"calling-${if(d) "dark" else "light"}-settings.png")
            out.outputStream().use{compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)}
        }
        compose.onNodeWithText("Open system settings").performScrollTo().performClick();assertEquals(1,settings)
        compose.runOnIdle{state=UdpCheckState(null)}
        compose.onNodeWithText("Start").performScrollTo().assertIsNotEnabled()
    }
}
