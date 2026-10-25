package io.nekohasekai.sfa.compose.base

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.nekohasekai.sfa.constant.Status

@Composable
fun rememberApplyServiceChangeNotifier(
    serviceStatus: Status,
    automatic: Boolean = false,
): (UiEvent.ApplyServiceChange.Mode) -> Unit = remember(serviceStatus, automatic) {
    { mode ->
        if (serviceStatus == Status.Started) {
            GlobalEventBus.tryEmit(UiEvent.ApplyServiceChange(mode, automatic))
        }
    }
}
