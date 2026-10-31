package io.nekohasekai.sfa.compose.screen.activation

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.compose.component.qr.QRScanSheet
import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult
import io.nekohasekai.sfa.compose.screen.dashboard.KnineArrive
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.WeatherGlyph
import io.nekohasekai.sfa.compose.screen.dashboard.knineTone
import io.nekohasekai.sfa.compose.screen.dashboard.rememberMotionOn
import io.nekohasekai.sfa.compose.screen.dashboard.weatherInk
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.compose.theme.KnineMotion
import io.nekohasekai.sfa.managed.ManagedClaimLink
import io.nekohasekai.sfa.managed.ManagedEndpoint
import io.nekohasekai.sfa.managed.ManagedException
import io.nekohasekai.sfa.managed.ManagedState
import io.nekohasekai.sfa.managed.ManagedStatus
import io.nekohasekai.sfa.managed.ManagedSync
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import io.nekohasekai.sfa.compose.theme.BackdropText as Text

/**
 * The edition handed to others opens here until a key is entered: a one-time key from the
 * KOne console (or a key link, typed, pasted or scanned). Nothing else in the App is reachable
 * before that, and the connection refuses to start (see BoxService). When the console takes the
 * service back, the App returns here.
 */
@Composable
fun ActivationScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = ManagedState.snapshot
    // A console address built into this copy spares typing it; otherwise it is asked for.
    val builtIn = remember { ManagedEndpoint.parse(BuildConfig.KONE_CONSOLE) }
    var address by remember { mutableStateOf(state.endpoint.orEmpty()) }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var problem by remember {
        mutableStateOf(if (state.status == ManagedStatus.Revoked) (state.detail ?: "Access revoked") + ". Enter a new access key." else null)
    }

    fun activate(link: ManagedClaimLink? = null) {
        val found = link ?: ManagedClaimLink.find(key) ?: ManagedClaimLink.find(address)
        val endpoint = found?.endpoint ?: builtIn ?: ManagedEndpoint.parse(address)
        val code = found?.code ?: key.trim()
        when {
            busy -> return
            endpoint == null -> problem = "The service URL must start with https://"
            !ManagedClaimLink.isInviteCode(code) -> problem = "Incomplete access key"
            else -> {
                busy = true
                problem = null
                scope.launch {
                    try {
                        ManagedSync.claim(context, endpoint, code)
                        // Bound: the App takes over from here as the state changes.
                    } catch (failure: ManagedException) {
                        problem = failure.shown
                    } catch (_: Exception) {
                        problem = "Could not activate. Try again."
                    } finally {
                        busy = false
                    }
                }
            }
        }
    }

    // The page arrives as the node page does: one line after another, settling from below.
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(720, easing = LinearEasing)) }
    fun Modifier.settle(order: Int): Modifier = graphicsLayer {
        val p = KnineArrive.transform(((appear.value - order * 0.08f) / 0.5f).coerceIn(0f, 1f))
        alpha = p
        translationY = (1f - p) * 16.dp.toPx()
    }
    // The last reason is kept while it folds away, so it leaves as it came rather than vanishing first.
    var lastProblem by remember { mutableStateOf("") }
    SideEffect { problem?.let { lastProblem = it } }
    val shownProblem = problem ?: lastProblem

    BackdropContrastScope(Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            // At the foot of the city, under its sky; with the keyboard up or large type it scrolls
            // rather than crowding the fields.
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .heightIn(min = maxHeight)
                    .padding(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.Bottom),
            ) {
                Text("KOne", fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, modifier = Modifier.settle(0))
                Text(
                    "Enter an access key to get started",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.settle(1).graphicsLayer { alpha = 0.8f },
                )
                Spacer(Modifier.height(4.dp))
                if (builtIn == null) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it; problem = null },
                        label = { Text("Service URL") },
                        placeholder = { Text("https://") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth().settle(2),
                    )
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it; problem = null },
                    label = { Text("Access key") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { scanning = true }) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Scan code")
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { activate() }),
                    modifier = Modifier.fillMaxWidth().settle(3),
                )
                // What went wrong, under the weather that says it; it unfolds and folds away in place.
                AnimatedVisibility(visible = problem != null, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                    val ink = LocalContentColor.current
                    Row(verticalAlignment = Alignment.Top) {
                        WeatherGlyph(NodeWeather.Cut, 18.dp, ink, Modifier.padding(top = 1.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(shownProblem, style = MaterialTheme.typography.bodyMedium, color = weatherInk(NodeWeather.Cut, ink))
                    }
                }
                Spacer(Modifier.height(4.dp))
                StartPill(
                    busy = busy,
                    enabled = key.isNotBlank() && (builtIn != null || address.isNotBlank() || ManagedClaimLink.find(key) != null),
                    modifier = Modifier.settle(4),
                ) { activate() }
                // A phone without a key can't report through the console, so a failed activation
                // goes to whoever gave the key, through whatever app the person already uses with them.
                AnimatedVisibility(
                    visible = problem != null && !busy,
                    enter = KnineMotion.reveal(),
                    exit = KnineMotion.conceal(),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        "Get help",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(role = Role.Button) { shareActivationProblem(context, shownProblem) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .graphicsLayer { alpha = 0.75f },
                    )
                }
            }
        }
    }

    if (scanning) {
        QRScanSheet(
            onDismiss = { scanning = false },
            onScanResult = { result ->
                scanning = false
                when (result) {
                    is QRScanResult.ClaimLink -> ManagedClaimLink.parse(result.link)?.let { link ->
                        key = link.code
                        if (builtIn == null) address = link.endpoint.base
                        activate(link)
                    }
                    else -> Toast.makeText(context, "This is not a KOne access key", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }
}

/** The report a person sends for help: what went wrong, on which build and phone. Never the key. */
internal fun activationReport(problem: String, now: Date = Date()): String {
    val time = SimpleDateFormat("yyyy-MM-dd HH:mm 'UTC'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
    return "KOne activation problem\n" +
        "Problem: $problem\n" +
        "App: KOne ${BuildConfig.VERSION_NAME} · ${BuildConfig.VERSION_CODE}\n" +
        "Phone: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
        "Time: $time"
}

private fun shareActivationProblem(context: Context, problem: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, activationReport(problem))
    runCatching { context.startActivity(Intent.createChooser(send, "Get help with activation")) }
        .onFailure { Toast.makeText(context, "No app on this phone can send it", Toast.LENGTH_SHORT).show() }
}

/**
 * KOne's main action as a pill. Until there is a key to send it is only drawn in outline; the ink
 * fills it as the key is entered. While the key is checked a dot breathes beside the word.
 */
@Composable
private fun StartPill(busy: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    val tone = knineTone()
    val fill by animateFloatAsState(if (enabled || busy) 1f else 0f, tween(280, easing = KnineArrive), label = "pillFill")
    val shape = RoundedCornerShape(26.dp)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(ink.copy(alpha = fill))
            .border(1.dp, ink.copy(alpha = 0.32f * (1f - fill)), shape)
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick)
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(visible = busy, enter = fadeIn(tween(200)) + expandHorizontally(tween(280, easing = KnineArrive)), exit = fadeOut(tween(140)) + shrinkHorizontally(tween(240))) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BreathingDot(tone)
                Spacer(Modifier.width(10.dp))
            }
        }
        AnimatedContent(
            targetState = busy,
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
            label = "pillLabel",
        ) { checking ->
            androidx.compose.material3.Text(
                if (checking) "Verifying" else "Get started",
                color = lerp(ink.copy(alpha = 0.55f), tone, fill),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** A dot in [color] breathing slowly; with the phone's animations off it simply shows. */
@Composable
private fun BreathingDot(color: Color) {
    val moving = rememberMotionOn()
    val breath by rememberInfiniteTransition(label = "pillBreath").animateFloat(
        0.3f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    Canvas(Modifier.size(10.dp)) {
        drawCircle(color.copy(alpha = if (moving) breath else 0.8f), radius = size.minDimension / 2f * (if (moving) 0.75f + 0.25f * breath else 1f))
    }
}
