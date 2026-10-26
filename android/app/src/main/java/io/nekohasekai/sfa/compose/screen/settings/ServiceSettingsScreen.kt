package io.nekohasekai.sfa.compose.screen.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.automirrored.outlined.CallSplit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.bg.ServiceConnection
import io.nekohasekai.sfa.compose.base.UiEvent
import io.nekohasekai.sfa.compose.base.rememberApplyServiceChangeNotifier
import io.nekohasekai.sfa.compose.component.KnineRowGroup
import io.nekohasekai.sfa.compose.theme.KnineMotion
import io.nekohasekai.sfa.compose.theme.KnineSectionLabel
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.database.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceSettingsScreen(
    navController: NavController,
    @Suppress("UNUSED_PARAMETER") serviceConnection: ServiceConnection? = null,
    serviceStatus: Status = Status.Stopped,
) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.service)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.content_description_back),
                    )
                }
            },
        )
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var batteryOptimizationIgnored by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    var allowBypass by remember { mutableStateOf(Settings.allowBypass) }
    val notifyApplyChange = rememberApplyServiceChangeNotifier(serviceStatus)
    // Read again on return, as the answer may come from Android's own page as well as the request.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context)
    }
    val requestBatteryOptimization =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            batteryOptimizationIgnored = isIgnoringBatteryOptimizations(context)
        }

    val scaffoldPadding = LocalScaffoldPadding.current

    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(
                top = scaffoldPadding.calculateTopPadding() + 8.dp,
                bottom = scaffoldPadding.calculateBottomPadding() + 8.dp,
            ),
    ) {
        // Only while Android may stop KOne in the background: one row, and tapping it asks Android.
        AnimatedVisibility(!batteryOptimizationIgnored, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
            Column {
                Spacer(Modifier.height(8.dp))
                KnineRowGroup {
                    SettingsLinkRow(
                        title = stringResource(R.string.kone_settings_background),
                        icon = Icons.Outlined.BatteryChargingFull,
                        onClick = {
                            requestBatteryOptimization.launch(
                                Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    Uri.parse("package:${context.packageName}"),
                                ),
                            )
                        },
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        KnineSectionLabel(text = "VPN")
        KnineRowGroup {
            SettingsSwitchRow(
                title = stringResource(R.string.kone_settings_allow_bypass),
                icon = Icons.AutoMirrored.Outlined.CallSplit,
                checked = allowBypass,
                onCheckedChange = { checked ->
                    allowBypass = checked
                    scope.launch {
                        withContext(Dispatchers.IO) { Settings.allowBypass = checked }
                        notifyApplyChange(UiEvent.ApplyServiceChange.Mode.Reload)
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
    return context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
}
