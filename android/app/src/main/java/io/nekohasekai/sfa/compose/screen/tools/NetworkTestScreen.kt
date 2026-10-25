package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.KnineSegmented
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.udp.NativeUdpBinding
import io.nekohasekai.sfa.udp.RunningUdpProfile
import io.nekohasekai.sfa.udp.UdpCheckController
import io.nekohasekai.sfa.udp.UdpProbe

internal enum class NetworkTestTab(val label: String) {
    Speed("Speed"),
    Udp("UDP"),
    Check("Setup"),
}

/** Both tools share a page and only its visible, resumed tool may run. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkTestScreen(
    navController: NavController,
    serviceStatus: Status,
    selections: Map<String, String>,
    currentMode: String? = null,
    commandConnected: Boolean = false,
    selectedProfileId: Long? = null,
    initialTab: NetworkTestTab = NetworkTestTab.Speed,
    requestedUdpProfileId: Long? = null,
    speedViewModel: NetworkQualityViewModel = viewModel(),
) {
    var tabName by rememberSaveable { mutableStateOf(initialTab.name) }
    val selectedTab = NetworkTestTab.valueOf(tabName)
    val scope = rememberCoroutineScope()
    val udpChecker = remember { UdpCheckController(scope, UdpProbe(NativeUdpBinding::bind)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsState()
    val canRun = lifecycleState == Lifecycle.State.RESUMED

    fun cancelTests() {
        speedViewModel.cancelTest()
        speedViewModel.dismissMeteredWarning()
        udpChecker.cancel()
    }

    // Navigation transitions can keep the old page composed. Cancel as soon as it pauses.
    DisposableEffect(lifecycleOwner, speedViewModel, udpChecker) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) cancelTests()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cancelTests()
        }
    }
    LaunchedEffect(canRun) { if (!canRun) cancelTests() }

    OverrideTopBar {
        NetworkTestTopBar(onBack = {
            cancelTests()
            navController.navigateUp()
        })
    }

    NetworkTestPage(
        selectedTab = selectedTab,
        onTabSelected = { next ->
            if (next != selectedTab) {
                cancelTests()
                tabName = next.name
            }
        },
        modifier = Modifier.fillMaxSize().padding(LocalScaffoldPadding.current),
    ) { tab ->
        when (tab) {
            NetworkTestTab.Speed -> SpeedTestScreen(
                serviceStatus = serviceStatus,
                viewModel = speedViewModel,
                enabled = canRun,
            )
            NetworkTestTab.Udp -> UdpTestScreen(
                profileId = requestedUdpProfileId,
                selections = selections,
                serviceStatus = serviceStatus,
                checker = udpChecker,
                enabled = canRun,
            )
            NetworkTestTab.Check -> ConnectionCheckScreen(
                serviceStatus = serviceStatus,
                selections = selections,
                currentMode = currentMode,
                commandConnected = commandConnected,
                selectedProfileId = selectedProfileId,
                enabled = canRun,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NetworkTestTopBar(onBack: () -> Unit) {
    TopAppBar(
        title = { Text("Network tests") },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
    )
}

@Composable
internal fun NetworkTestPage(
    selectedTab: NetworkTestTab,
    onTabSelected: (NetworkTestTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (NetworkTestTab) -> Unit,
) {
    Column(modifier) {
        // The tests as one small ink control, its knob under the open one; no rule across the page.
        Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
            KnineSegmented(
                options = NetworkTestTab.entries.map { it.label },
                selected = selectedTab.ordinal,
                onSelect = { onTabSelected(NetworkTestTab.entries[it]) },
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(selectedTab) { content(selectedTab) }
        }
    }
}

/** A restored node-specific route retains that node; it must never silently test another. */
internal fun udpProfileForTest(
    requestedProfileId: Long?,
    running: RunningUdpProfile?,
    serviceStarted: Boolean,
    remote: Boolean,
): RunningUdpProfile? = running?.takeIf {
    serviceStarted && !remote && (requestedProfileId == null || it.id == requestedProfileId)
}
