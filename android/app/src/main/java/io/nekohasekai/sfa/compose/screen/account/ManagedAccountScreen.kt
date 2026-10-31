package io.nekohasekai.sfa.compose.screen.account

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.os.ConfigurationCompat
import androidx.navigation.NavController
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.screen.settings.AccountRoutes
import io.nekohasekai.sfa.compose.screen.tools.TestGutter
import io.nekohasekai.sfa.compose.screen.tools.TestLink
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.weatherInk
import io.nekohasekai.sfa.compose.screen.tools.TestNote
import io.nekohasekai.sfa.compose.screen.tools.TestRow
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.compose.topbar.LocalScaffoldPadding
import io.nekohasekai.sfa.compose.topbar.OverrideTopBar
import io.nekohasekai.sfa.managed.ManagedSnapshot
import io.nekohasekai.sfa.managed.ManagedState
import io.nekohasekai.sfa.managed.ManagedStatus
import io.nekohasekai.sfa.managed.ManagedSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.Locale
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.KnineMotion

/**
 * The customer edition's account: how the binding to the service stands, the terms it states,
 * a sync on demand, and unlinking. Diagnostics stay off here until their disclosure and consent
 * design is approved (docs/windows/README.md, managed account protocol). The network tests' face: rows on
 * the gutter, a weather where something is wrong, and the reason one tap away. Unlinked, the App
 * returns to the access key page by itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManagedAccountScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = ManagedState.snapshot
    var syncing by remember { mutableStateOf(false) }
    // Unlinking asks in place: the first tap turns the row into the question, the second answers it.
    var unlinkArmed by remember { mutableStateOf(false) }
    var unlinking by remember { mutableStateOf(false) }
    var unlinkFailed by remember { mutableStateOf(false) }
    // Ticks on each minute, so "Synced 3 minutes ago" and an end date that has just passed change
    // together, and a new time zone is picked up with the time.
    val clock by produceState(AccountClock.read()) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            value = AccountClock.read()
        }
    }

    OverrideTopBar {
        TopAppBar(
            title = { Text(stringResource(R.string.kone_account)) },
            navigationIcon = {
                IconButton(onClick = { navController.navigateUp() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.content_description_back))
                }
            },
        )
    }

    fun syncNow() {
        if (syncing) return
        syncing = true
        scope.launch {
            // Failures land in ManagedState and show as the status row's weather.
            try { ManagedSync.sync(context.applicationContext) } finally { syncing = false }
        }
    }

    fun unlink() {
        if (unlinking) return
        unlinking = true
        unlinkFailed = false
        scope.launch {
            try {
                ManagedSync.unbind(context.applicationContext)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                unlinkFailed = true
            } finally {
                unlinking = false
                unlinkArmed = false
            }
        }
    }

    // Left unanswered, the question folds away again.
    LaunchedEffect(unlinkArmed, unlinking) {
        if (unlinkArmed && !unlinking) {
            delay(6_000)
            unlinkArmed = false
        }
    }

    BackdropContrastScope(Modifier.fillMaxSize().padding(LocalScaffoldPadding.current)) {
        // Unlinked, the App leaves this page for the access key page; nothing is left to show.
        if (state.bound) {
            ManagedAccountContent(
                state = state,
                clock = clock,
                syncing = syncing,
                unlinking = unlinking,
                unlinkFailed = unlinkFailed,
                unlinkArmed = unlinkArmed,
                onSync = ::syncNow,
                onFeedback = { navController.navigate(AccountRoutes.Feedback) },
                onUnlink = { if (unlinkArmed) unlink() else unlinkArmed = true },
            )
        }
    }
}

/** The page itself, from a snapshot of the binding; the screen above owns the work behind each action. */
@Composable
internal fun ManagedAccountContent(
    state: ManagedSnapshot,
    clock: AccountClock,
    syncing: Boolean,
    unlinking: Boolean,
    unlinkFailed: Boolean,
    onSync: () -> Unit,
    onFeedback: () -> Unit,
    onUnlink: () -> Unit,
    modifier: Modifier = Modifier,
    unlinkArmed: Boolean = false,
) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp)) {
        AccountStatus(state, clock.now)
        AccountTerms(state, clock)
        Spacer(Modifier.height(12.dp))
        SyncRow(syncing = syncing, onClick = onSync)
        TestLink(stringResource(R.string.kone_feedback), onClick = onFeedback)
        Spacer(Modifier.height(12.dp))
        UnlinkRow(busy = unlinking, armed = unlinkArmed, onClick = onUnlink)
        AnimatedVisibility(visible = unlinkFailed, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
            TestNote(stringResource(R.string.kone_account_unlink_failed), Modifier.padding(bottom = 8.dp))
        }
    }
}

/** The binding's state with its weather; when it last synced on the right; why, a tap below. */
@Composable
private fun AccountStatus(state: ManagedSnapshot, now: Long) {
    var open by remember(state.status, state.detail) { mutableStateOf(false) }
    val ago = if (state.syncedAt > 0L) DateUtils.getRelativeTimeSpanString(state.syncedAt, now, DateUtils.MINUTE_IN_MILLIS).toString() else null
    val healthy = state.status == ManagedStatus.Synced
    val hint = when (state.status) {
        ManagedStatus.Offline -> stringResource(R.string.kone_account_offline_hint)
        ManagedStatus.Waiting -> stringResource(R.string.kone_account_waiting_hint)
        else -> null
    }
    val synced = if (ago != null) stringResource(R.string.kone_account_synced_ago, ago) else stringResource(R.string.kone_account_never_synced)
    // While all is well the row reads "Synced · 3 minutes ago"; otherwise the state stands alone
    // and the console's reason, what it means here, and the last sync wait a tap below.
    TestRow(
        state.status.label,
        accountWeather(state.status),
        value = if (healthy) ago else null,
        detail = if (healthy) null else listOfNotNull(state.detail?.takeIf { it.isNotBlank() && it != state.status.label }, hint, synced).joinToString("\n"),
        open = open,
        onToggle = { open = !open },
    )
}

/** What the service states for this device: plan, route, data, devices, end date, its own name. */
@Composable
private fun AccountTerms(state: ManagedSnapshot, clock: AccountClock) {
    val context = LocalContext.current
    val policy = state.policy
    // The App's language may differ from the phone's; the configuration carries the one in use.
    val locale = ConfigurationCompat.getLocales(LocalConfiguration.current)[0] ?: Locale.getDefault()
    // Read again on every tick: an end within the hour must turn to Ended at its minute, not the next hour.
    val expiry = remember(policy?.expiresAt, clock) { accountExpiry(policy?.expiresAt, Instant.ofEpochMilli(clock.now), clock.zone) }
    var expiryOpen by remember(expiry) { mutableStateOf(false) }
    if (state.groupName.isNotBlank()) TestRow(stringResource(R.string.kone_account_plan), null, value = state.groupName, open = false, onToggle = {})
    if (state.configName.isNotBlank()) TestRow(stringResource(R.string.kone_account_route), null, value = state.configName, open = false, onToggle = {})
    if (policy != null && policy.quotaBytes > 0L) {
        val amount = Formatter.formatShortFileSize(context, policy.quotaBytes)
        val value = if (policy.period.isNotBlank()) stringResource(R.string.kone_account_data_period, amount, policy.period) else amount
        TestRow(stringResource(R.string.kone_account_data), null, value = value, open = false, onToggle = {})
    }
    if (policy != null && policy.deviceLimit > 0) {
        TestRow(stringResource(R.string.kone_account_devices), null, value = policy.deviceLimit.toString(), open = false, onToggle = {})
    }
    if (expiry != null) {
        val date = remember(expiry.date, locale) { expiryDateText(expiry.date, locale) }
        TestRow(
            if (expiry.state == TermState.Ended) stringResource(R.string.kone_account_ended) else stringResource(R.string.kone_account_expires),
            expiry.state.weather(),
            value = date,
            detail = if (expiry.state == TermState.Active) null else stringResource(R.string.kone_account_ending_hint),
            open = expiryOpen,
            onToggle = { expiryOpen = !expiryOpen },
        )
    }
    if (state.deviceName.isNotBlank()) TestRow(stringResource(R.string.kone_account_device), null, value = state.deviceName, open = false, onToggle = {})
}

/** Sync as a row: the words on the gutter, its arrows (or the sync under way) where rows keep their weather. */
@Composable
private fun SyncRow(syncing: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !syncing, role = Role.Button, onClick = onClick)
            .padding(horizontal = TestGutter, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(if (syncing) R.string.kone_account_syncing else R.string.kone_account_sync_now),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (syncing) CircularProgressIndicator(Modifier.size(16.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
            else Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

/**
 * The one way out, in cinnabar and apart from the rest. A first tap turns it into the question,
 * with what unlinking does unfolding beneath; a second tap within a few seconds unlinks.
 */
@Composable
private fun UnlinkRow(busy: Boolean, armed: Boolean, onClick: () -> Unit) {
    val cinnabar = weatherInk(NodeWeather.Cut, LocalContentColor.current)
    val stage = when {
        busy -> R.string.kone_account_unlinking
        armed -> R.string.kone_account_unlink_title
        else -> R.string.kone_account_unlink
    }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy, role = Role.Button, onClick = onClick)
                .padding(horizontal = TestGutter, vertical = 13.dp)
                .graphicsLayer { alpha = if (busy) 0.45f else 1f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = stage,
                transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
                label = "unlinkStage",
                modifier = Modifier.weight(1f),
            ) { shown ->
                Text(stringResource(shown), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = cinnabar)
            }
            AnimatedVisibility(visible = armed && !busy, enter = fadeIn(tween(220)), exit = fadeOut(tween(140))) {
                Text(
                    stringResource(R.string.kone_account_unlink_confirm),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = cinnabar,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        AnimatedVisibility(visible = armed, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
            TestNote(stringResource(R.string.kone_account_unlink_message), Modifier.padding(bottom = 8.dp))
        }
    }
}
