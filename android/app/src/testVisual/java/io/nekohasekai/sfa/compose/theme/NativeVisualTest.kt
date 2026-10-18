package io.nekohasekai.sfa.compose.theme

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.component.KnineConnectionBar
import io.nekohasekai.sfa.compose.component.KnineStartAction
import io.nekohasekai.sfa.compose.navigation.Screen
import io.nekohasekai.sfa.compose.navigation.bottomNavigationScreens
import io.nekohasekai.sfa.compose.screen.dashboard.ClashModeCard
import io.nekohasekai.sfa.compose.screen.dashboard.DownloadTrafficCard
import io.nekohasekai.sfa.compose.screen.dashboard.ProfilesCard
import io.nekohasekai.sfa.compose.screen.dashboard.CountryProfileDeck
import io.nekohasekai.sfa.compose.screen.dashboard.UploadTrafficCard
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Profile
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/**
 * Host-rendered Android/Compose components, with illustrative data only.
 * No production Application, VPN, databases, network, or phone is started.
 * These images validate component layout; they are not a device acceptance run.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(application = Application::class, sdk = [35], qualifiers = "zh-rCN-w320dp-h720dp-xhdpi")
class NativeVisualTest {
    @get:Rule
    val compose = createComposeRule()
    private val photoReady = AtomicBoolean(false)

    @Test fun dashboardDark() = render("dashboard-dark-320-font115", R.drawable.owner_wallpaper_4, PreviewPage.Dashboard)
    @Test fun dashboardPortraitClearBottom() = render("dashboard-portrait-clear-bottom-320-font115", R.drawable.portrait_backdrop, PreviewPage.Dashboard)
    @Test fun countryCardsPortrait() = render("country-cards-portrait-320-font115", R.drawable.portrait_backdrop, PreviewPage.Countries)
    @Test fun countryCardsBright() = render("country-cards-bright-320-font115", R.drawable.owner_wallpaper_2, PreviewPage.Countries)
    @Test fun plainDashboard() = render("plain-dashboard-320-font115", R.drawable.portrait_backdrop, PreviewPage.Plain)
    @Test fun plainConnected() = render("plain-connected-320-font115", R.drawable.portrait_backdrop, PreviewPage.PlainConnected)
    @Test fun brightConnectedReadingSpace() = render("bright-connected-reading-320-font115", R.drawable.owner_wallpaper_2, PreviewPage.CountriesConnected)
    @Test fun dashboardBright() = render("dashboard-bright-320-font115", R.drawable.owner_wallpaper_2, PreviewPage.Dashboard)
    @Test fun trafficDark() = render("component-traffic-dark-320-font115", R.drawable.owner_wallpaper_4, PreviewPage.Traffic)
    @Test fun trafficBright() = render("component-traffic-bright-320-font115", R.drawable.owner_wallpaper_2, PreviewPage.Traffic)
    @Test fun toolsDark() = render("tools-dark-320-font115", R.drawable.owner_wallpaper_4, PreviewPage.Tools)
    @Test fun toolsBright() = render("tools-bright-320-font115", R.drawable.owner_wallpaper_2, PreviewPage.Tools)
    @Test fun largeTypeLongName() = render("long-name-dark-320-font150", R.drawable.owner_wallpaper_4, PreviewPage.Dashboard, fontScale = 1.5f)
    @Test fun menuBright() = render("menu-bright-native-window", R.drawable.owner_wallpaper_2, PreviewPage.Dashboard, overlay = "menu")
    @Test fun menuDark() = render("menu-dark-transparent-window", R.drawable.portrait_backdrop, PreviewPage.Dashboard, overlay = "menu")
    @Test fun sheetDark() = render("sheet-dark-native-window", R.drawable.owner_wallpaper_4, PreviewPage.Dashboard, overlay = "sheet")

    @Test fun contentStateSurvivesReadingLayoutChanges() {
        var preservePortrait by mutableStateOf(true)
        var groupedContent by mutableStateOf(false)
        val mounts = AtomicInteger(0)
        compose.setContent {
            Theme(dynamicColor = false, portraitBackdrop = true, backdropRes = R.drawable.owner_wallpaper_2) {
                val backdrop = LocalBackdrop.current
                SideEffect { photoReady.set(backdrop?.pixels != null && backdrop.previousPixels == null) }
                WallpaperContentFrame(preservePortrait = preservePortrait, groupedContent = groupedContent) {
                    val identity = remember { mounts.incrementAndGet() }
                    Text("Preserve page state $identity")
                }
            }
        }
        compose.waitUntil(timeoutMillis = 15_000) { photoReady.get() }
        compose.waitForIdle()
        val initialMounts = mounts.get()
        compose.runOnIdle { preservePortrait = false }
        compose.waitForIdle()
        compose.runOnIdle { groupedContent = true }
        compose.waitForIdle()
        compose.runOnIdle { preservePortrait = true }
        compose.waitForIdle()
        assertEquals("Changing the reading material must keep remembered page state", initialMounts, mounts.get())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Test fun countryGroupsOnlyRevealTwoNodesUntilRequestedAndSelectionStillWorks() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("knine_profile_categories", Application.MODE_PRIVATE)
            .edit().putString("101", "ZZ").commit()
        val selected = AtomicInteger(0)
        val starts = AtomicInteger(0)
        var selectedId by mutableStateOf(101L)
        compose.setContent {
            Theme(dynamicColor = false, portraitBackdrop = true, backdropRes = R.drawable.portrait_backdrop) {
                val backdrop = LocalBackdrop.current
                SideEffect { photoReady.set(backdrop?.pixels != null && backdrop.previousPixels == null) }
                CountryProfileDeck(countryPreviewProfiles(), selectedId, {
                    selected.set(it.toInt()); selectedId = it
                }, Modifier.fillMaxWidth(), onConnectRequested = { starts.incrementAndGet() })
            }
        }
        compose.waitUntil(timeoutMillis = 15_000) { photoReady.get() }
        compose.waitForIdle()
        compose.onNodeWithText("Local · Backup").assertExists()
        compose.onNodeWithText("Local · Third").assertDoesNotExist()
        compose.onNodeWithText("1 more node").performClick()
        compose.onNodeWithText("Local · Third").assertExists()
        compose.onNodeWithText("Collapse others").performClick()
        compose.onNodeWithText("Local · Third").assertDoesNotExist()
        compose.onNodeWithText("United Kingdom").performClick()
        compose.onNodeWithText("United Kingdom · Third").assertDoesNotExist()
        compose.onNodeWithText("United Kingdom · Backup").performClick()
        assertEquals(202, selected.get())
        assertEquals(0, starts.get())
        compose.onNodeWithText("Node preview").assertDoesNotExist()
        compose.onNodeWithText("United Kingdom · Backup").performClick()
        compose.waitForIdle()
        assertEquals(1, starts.get())
    }

    @Test fun secondTapWaitsForTheSelectedNodeAndNeverStartsThePreviousNode() {
        var selectedId by mutableStateOf(101L)
        var loading by mutableStateOf(false)
        var status by mutableStateOf(Status.Stopped)
        val starts = mutableListOf<Long>()
        compose.setContent {
            Theme(dynamicColor = false, plainKnine = true) {
                CountryProfileDeck(countryPreviewProfiles(), selectedId, { loading = true }, Modifier.fillMaxWidth(),
                    isLoading = loading, serviceStatus = status,
                    onConnectRequested = { starts += selectedId; status = Status.Starting },
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Local · Backup").performClick()
        compose.onNodeWithText("Local · Backup").performClick()
        compose.waitForIdle()
        assertTrue(starts.isEmpty())
        compose.runOnIdle { selectedId = 102L; loading = false }
        compose.waitForIdle()
        assertEquals(listOf(102L), starts)
        compose.onNodeWithText("Local · Backup").performClick()
        compose.waitForIdle()
        assertEquals(listOf(102L), starts)
        compose.runOnIdle { status = Status.Started }
        compose.onNodeWithText("Local · Backup").performClick()
        compose.waitForIdle()
        assertEquals(listOf(102L), starts)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    private fun render(name: String, wallpaper: Int, page: PreviewPage, fontScale: Float = 1.15f, overlay: String? = null) {
        if (page.isCountryPage()) {
            InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences("knine_profile_categories", Application.MODE_PRIVATE)
                .edit().putString("101", "ZZ").commit()
        }
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = fontScale)) {
                Theme(dynamicColor = false, portraitBackdrop = !page.isPlain(), plainKnine = page.isPlain(), backdropRes = wallpaper) {
                    val backdrop = LocalBackdrop.current
                    SideEffect { photoReady.set(page.isPlain() || (backdrop?.pixels != null && backdrop.previousPixels == null)) }
                    NativePreviewScene(page, longName = fontScale > 1.2f)
                    if (overlay == "menu") Box(Modifier.fillMaxSize().padding(top = 72.dp, end = 16.dp), contentAlignment = Alignment.TopEnd) {
                        Box {
                            KnineDropdownMenu(expanded = true, onDismissRequest = {}) {
                                DropdownMenuItem(text = { Text("Dashboard cards") }, onClick = {})
                                DropdownMenuItem(text = { Text("Import profile") }, onClick = {})
                                DropdownMenuItem(text = { Text("View connections") }, onClick = {})
                            }
                        }
                    }
                    if (overlay == "sheet") KnineGlassBottomSheet(onDismissRequest = {}, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            Text("Add profile", style = MaterialTheme.typography.titleLarge)
                            Text("Import from file", style = MaterialTheme.typography.bodyLarge)
                            Text("Scan QR code", style = MaterialTheme.typography.bodyLarge)
                            Text("Add manually", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        }
        // Decoding runs on Dispatchers.Default; wait for the committed image,
        // then finish only the local Compose transitions before capture.
        compose.waitUntil(timeoutMillis = 15_000) { photoReady.get() }
        compose.mainClock.advanceTimeBy(1_200)
        compose.waitForIdle()
        if (overlay == "menu") compose.onNodeWithText("Dashboard cards").assertExists()
        if (overlay == "sheet") compose.onNodeWithText("Add profile").assertExists()
        // Compose's node PixelCopy path captures the Activity surface for
        // popup roots in this host runtime. UiAutomation captures every native
        // WindowManager window, including the actual menu/dialog surface.
        val bitmap = if (overlay == null) compose.onRoot().captureToImage().asAndroidBitmap()
        else requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        assertTrue("Expected a full Android display", bitmap.width >= 320 && bitmap.height >= 600)
        val output = File(System.getProperty("knine.previewDir"), "$name.png")
        requireNotNull(output.parentFile).mkdirs()
        output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun nodeListSelectionAndExpandedStateSurviveAConnectionStateChange() {
        var loaded by mutableStateOf(emptyList<Profile>())
        var started by mutableStateOf(false)
        compose.setContent {
            Theme(dynamicColor = false, portraitBackdrop = false, plainKnine = true) {
                Column {
                    Text(if (started) "Connected" else "Connect")
                    CountryProfileDeck(loaded, 101, {}, Modifier.fillMaxWidth())
                }
            }
        }
        compose.runOnIdle { loaded = countryPreviewProfiles() }
        compose.waitForIdle()
        compose.onNodeWithText("Verizon").assertExists()
        compose.onNodeWithText("United Kingdom").performClick()
        compose.onNodeWithText("United Kingdom · Daily").assertExists()
        compose.runOnIdle { started = true }
        compose.waitForIdle()
        compose.onNodeWithText("United Kingdom · Daily").assertExists()
        compose.onNodeWithText("Verizon").assertDoesNotExist()
    }
}

private enum class PreviewPage { Dashboard, Countries, CountriesConnected, Plain, PlainConnected, Traffic, Tools }
private fun PreviewPage.isPlain() = this == PreviewPage.Plain || this == PreviewPage.PlainConnected
private fun PreviewPage.isCountryPage() = isPlain() || this == PreviewPage.Countries || this == PreviewPage.CountriesConnected
private fun PreviewPage.isConnectedCountryPage() = this == PreviewPage.PlainConnected || this == PreviewPage.CountriesConnected

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NativePreviewScene(page: PreviewPage, longName: Boolean) {
    val density = LocalDensity.current
    var connectionHeight by remember(density) { mutableStateOf(80.dp) }
    val startTime = remember { System.currentTimeMillis() - 60_000 }
    Scaffold(
        containerColor = if (page.isPlain()) MaterialTheme.colorScheme.background else Color.Transparent,
        topBar = {
            TopAppBar(
                title = {
                    if (page == PreviewPage.Tools) Text("Tools") else Column {
                        Text("Knine", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text("Dashboard", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = { if (page != PreviewPage.Tools) IconButton(onClick = {}) { Icon(Icons.Default.MoreVert, "More") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp) {
                bottomNavigationScreens.forEach { screen ->
                    val selected = screen == if (page == PreviewPage.Tools) Screen.Tools else Screen.Dashboard
                    NavigationBarItem(
                        selected = selected,
                        onClick = {},
                        colors = NavigationBarItemDefaults.colors(indicatorColor = Color.Transparent),
                        icon = { Icon(screen.icon, null, emphasized = selected) },
                        label = { Text(stringResource(screen.titleRes), emphasized = selected, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().consumeWindowInsets(padding)) {
            CompositionLocalProvider(LocalScaffoldPadding provides padding) {
                WallpaperContentFrame(preservePortrait = page == PreviewPage.Dashboard || page.isCountryPage(), serviceRunning = page.isConnectedCountryPage() || !page.isCountryPage(), groupedContent = page == PreviewPage.Tools, modifier = Modifier.fillMaxSize().padding(bottom = connectionHeight)) {
                    val inset = LocalScaffoldPadding.current
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(inset),
                        contentPadding = PaddingValues(horizontal = if (page == PreviewPage.Tools) 0.dp else 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (page.isCountryPage()) {
                            item { ExampleProfilesCard(longName, countryPreview = true) }
                            if (page.isConnectedCountryPage()) item { ClashModeCard(listOf("Rule", "Global", "Direct"), "Rule", {}) }
                            if (page.isConnectedCountryPage()) item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DownloadTrafficCard("2.6 MB/s", "128.4 MB", listOf(1f, 3f, 2f, 4f), Modifier.weight(1f))
                                    UploadTrafficCard("86 KB/s", "12.8 MB", listOf(1f, 1.4f, 2f, 1.3f), Modifier.weight(1f))
                                }
                            }
                        } else if (page == PreviewPage.Dashboard) {
                            item { ExampleProfilesCard(longName) }
                            item { ClashModeCard(listOf("Rule", "Global", "Direct"), "Rule", {}) }
                        } else if (page == PreviewPage.Traffic) {
                            item { ClashModeCard(listOf("Rule", "Global", "Direct"), "Rule", {}) }
                            item {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    DownloadTrafficCard("2.6 MB/s", "128.4 MB", listOf(1f, 3f, 2f, 4f, 3.4f), Modifier.weight(1f))
                                    UploadTrafficCard("86 KB/s", "12.8 MB", listOf(1f, 1.4f, 2f, 1.3f, 2.4f), Modifier.weight(1f))
                                }
                            }
                        } else {
                            item { ExampleToolsGroup() }
                        }
                    }
                }
            }
            if (page.isCountryPage() && !page.isConnectedCountryPage()) Box(
                Modifier.align(Alignment.BottomEnd).padding(bottom = padding.calculateBottomPadding() + 16.dp, end = 16.dp),
            ) {
                KnineStartAction {}
            } else KnineConnectionBar(
                visible = true,
                serviceStatus = Status.Started,
                startTime = startTime,
                groupsCount = 2,
                hasGroups = true,
                onGroupsClick = {},
                connectionsCount = 7,
                onConnectionsClick = {},
                onStopClick = {},
                homeStatus = "Home online",
                onHeightChanged = { height -> connectionHeight = with(density) { height.toDp() } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = padding.calculateBottomPadding()),
            )
        }
    }
}

@Composable
private fun ExampleProfilesCard(longName: Boolean, countryPreview: Boolean = false) {
    ProfilesCard(
        profiles = if (countryPreview) countryPreviewProfiles() else listOf(Profile(id = 1L, name = if (longName) "Daily connection · Long home and mobile network example" else "Daily connection · Example")),
        selectedProfileId = if (countryPreview) 101L else 1L,
        isLoading = false,
        showAddProfileSheet = false,
        showProfilePickerSheet = false,
        onProfileSelected = {}, onProfileEdit = {}, onProfileDelete = {},
        onProfileShare = {}, onProfileShareURL = {}, onProfileUpdate = {},
        onProfileMove = { _, _ -> }, onShowAddProfileSheet = {}, onHideAddProfileSheet = {},
        onShowProfilePickerSheet = {}, onHideProfilePickerSheet = {}, onOpenNewProfile = {},
    )
}

private fun countryPreviewProfiles() = listOf(
    Profile(id = 101, name = "Verizon"),
    Profile(id = 102, name = "Local · Backup"),
    Profile(id = 103, name = "Local · Third"),
    Profile(id = 201, name = "United Kingdom · Daily"),
    Profile(id = 202, name = "United Kingdom · Backup"),
    Profile(id = 203, name = "United Kingdom · Third"),
    Profile(id = 301, name = "Germany · Self-hosted"),
)

@Composable
private fun ExampleToolsGroup() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val groups = listOf(
            "" to listOf("Tailscale" to Icons.Outlined.Hub),
            "Network" to listOf("Network quality" to Icons.Outlined.Speed, "STUN test" to Icons.Outlined.SwapHoriz),
            "Debug" to listOf("Crash report" to Icons.Outlined.BugReport, "Out-of-memory report" to Icons.Outlined.Memory, "Power report" to Icons.Outlined.Bolt),
        )
        groups.forEach { (heading, rows) ->
            if (heading.isNotEmpty()) KnineSectionLabel(heading)
            Card(modifier = Modifier.padding(horizontal = 16.dp)) {
                rows.forEach { (title, icon) ->
                    ListItem(
                        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
                        leadingContent = { Icon(icon, null) },
                        trailingContent = {
                            if (title == "Crash report") KnineBadge(containerColor = MaterialTheme.colorScheme.primary) { Text("5") }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    )
                }
            }
        }
    }
}
