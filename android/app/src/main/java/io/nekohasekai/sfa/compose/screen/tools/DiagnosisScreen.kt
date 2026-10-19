package io.nekohasekai.sfa.compose.screen.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
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
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.constant.Status
import io.nekohasekai.sfa.selfuse.PhoneDiagnosis
import io.nekohasekai.sfa.selfuse.PhoneProbe
import io.nekohasekai.sfa.selfuse.RoutingSettings
import kotlinx.coroutines.launch
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/**
 * Tools → Diagnose: the phone's own causes first (network, clock, another VPN, Private DNS,
 * battery, Data Saver), then names and sites the way apps reach them, each shown as it finishes,
 * and the one thing to do.
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

    val scaffoldPadding = LocalScaffoldPadding.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .verticalScroll(rememberScrollState())
            .padding(top = scaffoldPadding.calculateTopPadding() + 8.dp, bottom = scaffoldPadding.calculateBottomPadding() + 8.dp),
    ) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.knine_diagnose_detail), style = MaterialTheme.typography.bodyMedium)
                checks.forEach { check -> CheckRow(check) }
                if (running) Text(stringResource(R.string.knine_diagnose_running), style = MaterialTheme.typography.bodyMedium)
                if (done) {
                    val advice = PhoneDiagnosis.advice(checks)
                    Text(
                        advice ?: stringResource(R.string.knine_diagnose_fine),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (advice != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    // The result as plain text for whoever helps, as on Windows: no key, no node addresses.
                    if (done) TextButton(onClick = {
                        val report = Checks.report(
                            checks, PhoneDiagnosis.advice(checks),
                            listOf(
                                "Version" to io.nekohasekai.sfa.BuildConfig.VERSION_NAME,
                                "Android" to android.os.Build.VERSION.RELEASE,
                                "Region" to RoutingSettings.current().region,
                                "Connected" to (serviceStatus == Status.Started).toString(),
                            ),
                        )
                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("KOne diagnosis", report))
                        android.widget.Toast.makeText(context, context.getString(R.string.knine_diagnose_copied), android.widget.Toast.LENGTH_SHORT).show()
                    }) {
                        Text(stringResource(R.string.knine_diagnose_copy))
                    }
                    TextButton(onClick = ::start, enabled = !running) {
                        Text(stringResource(if (done) R.string.knine_diagnose_again else R.string.knine_diagnose_run))
                    }
                }
            }
        }
    }
}

@Composable
private fun CheckRow(check: Check) {
    val mark = when (check.outcome) {
        Outcome.Pass -> "✓"
        Outcome.Warn -> "!"
        Outcome.Fail -> "✕"
        Outcome.Running -> "…"
    }
    val color = when (check.outcome) {
        Outcome.Fail -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(check.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(110.dp))
        Text(mark, style = MaterialTheme.typography.bodyLarge, color = color, modifier = Modifier.width(24.dp))
        Text(check.detail, style = MaterialTheme.typography.bodyMedium, color = color, modifier = Modifier.weight(1f))
    }
}
