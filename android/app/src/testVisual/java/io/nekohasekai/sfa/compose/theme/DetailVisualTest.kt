package io.nekohasekai.sfa.compose.theme

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.qr.QRCodeDialog
import io.nekohasekai.sfa.compose.screen.tools.ResultItem
import io.nekohasekai.sfa.compose.screen.tools.ToolDetailValue
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.selfuse.PairedProfileDialog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

/** Production components with local sample state; does not start services. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
class DetailVisualTest {
    @get:Rule val compose = createComposeRule()
    private val ready = AtomicBoolean(false)

    @Test fun formBright() = render("form-bright", "form")
    @Test fun formDark() = render("form-dark", "form", dark = true)
    @Test fun formLargeType() = render("form-font150", "form", scale = 1.5f)
    @Test fun emptyBright() = render("search-empty-bright", "empty")
    @Test fun longDiagnosticValues() = render("diagnostic-long-values", "details")
    @Test fun selectionState() = render("selected-and-disabled", "selection")
    @Test
    @Config(qualifiers = "zh-rCN-w640dp-h320dp-xhdpi")
    fun qrShortWindow() = render("qr-short-window", "qr", scale = 1.5f)
    @Test fun qrShareWindow() = render("qr-share-window", "qr")
    @Test fun remoteBar() = render("remote-long-name", "remote", scale = 1.5f)
    @Test fun importWindow() = render("vless-import-window", "import")

    @OptIn(ExperimentalMaterial3Api::class)
    private fun render(name: String, state: String, dark: Boolean = false, scale: Float = 1.15f) {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, scale)) {
                Theme(dynamicColor = false, portraitBackdrop = true, backdropRes = if (dark) R.drawable.owner_wallpaper_4 else R.drawable.owner_wallpaper_2) {
                    val backdrop = LocalBackdrop.current
                    SideEffect { ready.set(backdrop?.pixels != null && backdrop.previousPixels == null) }
                    Scaffold(
                        containerColor = Color.Transparent,
                        topBar = { TopAppBar(title = { BackdropText(when (state) { "form" -> "Edit profile"; "details" -> "Network diagnostics"; "empty" -> "Search"; else -> "UI state" }) },
                            navigationIcon = { IconButton(onClick = {}) { BackdropIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) },
                    ) { padding ->
                        CompositionLocalProvider(LocalScaffoldPadding provides padding) {
                            WallpaperContentFrame(preservePortrait = false) {
                                if (state == "empty") KnineEmptyState("No matching items", "Try another name or clear the search.", modifier = Modifier.fillMaxSize())
                                else Column(Modifier.fillMaxSize().padding(LocalScaffoldPadding.current).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    when (state) {
                                        "form" -> {
                                            BackdropText("Connection details", style = MaterialTheme.typography.titleMedium)
                                            var nameValue by remember { mutableStateOf("Daily connection · Example") }
                                            BackdropOutlinedTextField(nameValue, { nameValue = it }, Modifier.fillMaxWidth().testTag("editable"), label = { Text("Name") }, singleLine = true)
                                            BackdropOutlinedTextField("https://example.invalid/config", {}, Modifier.fillMaxWidth(), label = { Text("Remote address") }, singleLine = true)
                                            BackdropOutlinedTextField("Invalid port", {}, Modifier.fillMaxWidth(), label = { Text("Port") }, isError = true, supportingText = { Text("Enter a port from 1 to 65535") })
                                            BackdropOutlinedTextField("Automatic selection", {}, Modifier.fillMaxWidth().testTag("disabled"), label = { Text("Current route") }, enabled = false)
                                            BackdropButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { BackdropText("Save profile") }
                                        }
                                        "details" -> {
                                            ResultItem("Egress address", "2001:db8:1234:5678:90ab:cdef:1234:5678", true, false)
                                            HorizontalDivider()
                                            ToolDetailValue("Device name", "Example workstation for file synchronization")
                                            ToolDetailValue("Connection path", "home-office-desktop.example.invalid:443", mono = true)
                                            ResultItem("Network latency", "38 ms", true, false, accuracy = "Local sample; no network speed test")
                                        }
                                        "remote" -> io.nekohasekai.sfa.compose.component.RemoteStatusBar(
                                            visible = true, serverName = "Example remote management server", isConnected = true,
                                            startTime = null, groupsCount = 12, hasGroups = true, onGroupsClick = {},
                                            connectionsCount = 128, onConnectionsClick = {}, onDisconnectClick = {},
                                        )
                                        "selection" -> {
                                            BackdropCard(modifier = Modifier.fillMaxWidth().testTag("selected"), border = BorderStroke(1.25.dp, MaterialTheme.colorScheme.onSurface)) {
                                                BackdropText("✓ Selected", modifier = Modifier.padding(20.dp), style = MaterialTheme.typography.titleMedium)
                                            }
                                            BackdropCard(modifier = Modifier.fillMaxWidth()) { BackdropText("Other items", modifier = Modifier.padding(20.dp)) }
                                            BackdropButton(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().testTag("disabled-button")) { BackdropText("Unavailable") }
                                            KnineLoadingState("Loading profile")
                                        }
                                        else -> BackdropText("Example profile")
                                    }
                                }
                            }
                        }
                    }
                    if (state == "qr") QRCodeDialog("https://example.invalid/demo", {})
                    if (state == "import") PairedProfileDialog({}, {}, "vless://sample-only")
                }
            }
        }
        compose.waitUntil(15_000) { ready.get() }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        if (state == "form") {
            compose.onNode(hasSetTextAction() and hasText("Daily connection · Example")).assertIsEnabled()
            compose.onNodeWithText("Automatic selection").assertIsNotEnabled()
        }
        if (state == "selection") compose.onNodeWithText("Unavailable").assertIsNotEnabled()
        if (state == "import") compose.onNodeWithText("Import VLESS node").assertExists()
        if (state == "qr") {
            compose.onNodeWithText("Close").performScrollTo().assertIsDisplayed()
            val qrLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.content_description_qr_code)
            val qr = compose.onNodeWithContentDescription(qrLabel).fetchSemanticsNode().boundsInRoot
            if (name == "qr-short-window") assertTrue("The full square code must fit the short screen", qr.height <= 300f && qr.width <= 300f)
        }
        val bitmap = if (state in setOf("qr", "import")) requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            else compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue(bitmap.width >= 640 && bitmap.height >= 600)
        val output = File(System.getProperty("knine.previewDir"), "$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
