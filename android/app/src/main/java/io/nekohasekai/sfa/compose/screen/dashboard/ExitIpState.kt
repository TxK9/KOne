package io.nekohasekai.sfa.compose.screen.dashboard

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.sfa.utils.CommandClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** [detecting] is true only while a check for the current state is in flight. */
internal data class ExitIpUi(val info: ExitInfo? = null, val detecting: Boolean = false)

@Composable
internal fun rememberExitIp(profileId: Long, status: Status, routeMode: String, remote: Boolean, commandClient: CommandClient): State<ExitIpUi> {
    val context = LocalContext.current.applicationContext
    val detector = remember(context) { ExitIpDetector(context) }
    val scope = rememberCoroutineScope()
    val selectedOutbounds = remember(commandClient) { mutableStateOf(emptyList<Pair<String, String>>()) }
    DisposableEffect(commandClient) {
        // Observe the existing group's selected tags without changing selection or subscriptions.
        val handler = object : CommandClient.Handler {
            override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
                val selected = newGroups.map { it.tag to it.selected }.sortedBy { it.first }
                scope.launch { selectedOutbounds.value = selected }
            }
        }
        commandClient.addHandler(handler)
        onDispose { commandClient.removeHandler(handler) }
    }
    // New state immediately hides an old IP during a profile, mode, or connection change.
    val state = remember(profileId, status, routeMode, remote, selectedOutbounds.value) { mutableStateOf(ExitIpUi()) }
    LaunchedEffect(state) {
        if (remote || profileId < 0 || status != Status.Started) return@LaunchedEffect
        try {
            detector.networks().collectLatest { network ->
                state.value = ExitIpUi()
                if (network == null) {
                    Log.i("KnineExitIp", "IP hidden: this app's VPN network is unavailable")
                    return@collectLatest
                }
                state.value = ExitIpUi(detecting = true)
                try {
                    val info = detector.detect(network)
                    state.value = ExitIpUi(info)
                    if (info == null) Log.i("KnineExitIp", "IP hidden: no verified response")
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    state.value = ExitIpUi()
                    Log.i("KnineExitIp", "IP hidden: detection failed")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.i("KnineExitIp", "IP hidden: network observation unavailable")
        }
    }
    return state
}
