package io.nekohasekai.sfa.compose.screen.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import cc.txwd.kone.shared.Check
import cc.txwd.kone.shared.Checks
import cc.txwd.kone.shared.Outcome
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.WeatherGlyph
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.selfuse.PhoneDiagnosis
import io.nekohasekai.sfa.selfuse.PhoneProbe
import io.nekohasekai.sfa.selfuse.RoutingSettings
import kotlinx.coroutines.launch
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/**
 * Tools → Diagnose: the phone's own causes first (network, clock, another VPN, Private DNS,
 * battery, Data Saver), then names and sites the way apps reach them, each shown as it finishes,
 * and the one thing to do. It wears the network tests' face: the ring to tap, the verdict under
 * it, then one row per check with its weather.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosisScreen(navController: NavController, serviceStatus: Status = Status.Stopped) {
    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.knine_diagnose)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                }
            },
        )
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checks by remember { mutableStateOf<List<Check>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }

    fun start() {
        if (running) return
        running = true
        done = false
        checks = emptyList()
        scope.launch {
            checks = PhoneProbe.diagnose(context, serviceStatus == Status.Started) { checks = it }
            running = false
            done = true
        }
    }

    // The result as plain text for whoever helps, as on Windows: no key, no node addresses.
    fun copyReport() {
        val report = Checks.report(
            checks, PhoneDiagnosis.advice(checks),
            listOf(
                "Version" to BuildConfig.VERSION_NAME,
                "Android" to Build.VERSION.RELEASE,
                "Region" to RoutingSettings.current().region,
                "Connected" to (serviceStatus == Status.Started).toString(),
            ),
        )
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("KOne diagnosis", report))
        Toast.makeText(context, context.getString(R.string.knine_diagnose_copied), Toast.LENGTH_SHORT).show()
    }

    val verdict = if (done) checks.weather() else null
    BackdropContrastScope(Modifier.fillMaxSize().padding(LocalScaffoldPadding.current)) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TestRing(
                phase = when {
                    running -> RingPhase.Running
                    verdict != null -> RingPhase.Done
                    else -> RingPhase.Idle
                },
                onTap = ::start,
                enabled = !running,
                accent = verdict?.let { ringAccent(it) },
            ) {
                when {
                    running -> {
                        RingNumber("…")
                        RingCaption(stringResource(R.string.knine_diagnose_running))
                    }
                    verdict != null -> {
                        WeatherGlyph(verdict, size = 44.dp, modifier = Modifier.padding(bottom = 10.dp))
                        RingCaption(stringResource(R.string.knine_diagnose_again))
                    }
                    else -> RingWord(stringResource(R.string.knine_diagnose_run))
                }
            }
            Spacer(Modifier.height(18.dp))
            if (done) RingLine(PhoneDiagnosis.advice(checks) ?: stringResource(R.string.knine_diagnose_fine))
            Spacer(Modifier.height(24.dp))
            // Each check as it finishes: its name, its few words, its weather.
            checks.forEach { check ->
                TestRow(
                    title = check.title,
                    weather = check.outcome.weather(),
                    value = check.detail.ifBlank { if (check.outcome == Outcome.Running) "…" else null },
                    open = false,
                    onToggle = {},
                )
            }
            if (done) {
                Spacer(Modifier.height(12.dp))
                TestLink(stringResource(R.string.knine_diagnose_copy), ::copyReport, icon = Icons.Outlined.ContentCopy)
            }
        }
    }
}

/** A check's weather: passed is clear, worth a look is mist, failed is the storm; one still running has none. */
private fun Outcome.weather(): NodeWeather? = when (this) {
    Outcome.Pass -> NodeWeather.Clear
    Outcome.Warn -> NodeWeather.Mist
    Outcome.Fail -> NodeWeather.Cut
    Outcome.Running -> null
}

/** The whole diagnosis in one weather: the worst of its checks. */
private fun List<Check>.weather(): NodeWeather = when {
    any { it.outcome == Outcome.Fail } -> NodeWeather.Cut
    any { it.outcome == Outcome.Warn } -> NodeWeather.Mist
    else -> NodeWeather.Clear
}
