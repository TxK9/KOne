package io.nekohasekai.sfa.compose.screen.activation

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.nekohasekai.sfa.BuildConfig
import io.nekohasekai.sfa.compose.component.qr.QRScanSheet
import io.nekohasekai.sfa.compose.screen.qrscan.QRScanResult
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.compose.theme.knineSurface
import io.nekohasekai.sfa.managed.ManagedClaimLink
import io.nekohasekai.sfa.managed.ManagedEndpoint
import io.nekohasekai.sfa.managed.ManagedException
import io.nekohasekai.sfa.managed.ManagedState
import io.nekohasekai.sfa.managed.ManagedStatus
import io.nekohasekai.sfa.managed.ManagedSync
import kotlinx.coroutines.launch
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

    BackdropContrastScope(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding()) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 28.dp, end = 28.dp, bottom = 36.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("KOne", fontSize = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
                Text("Enter an access key to get started", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.graphicsLayer { alpha = 0.8f })
                Spacer(Modifier.height(4.dp))
                if (builtIn == null) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it; problem = null },
                        label = { Text("Service URL") },
                        placeholder = { Text("https://") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
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
                    modifier = Modifier.fillMaxWidth(),
                )
                if (problem != null) {
                    Text(problem!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(4.dp))
                StartPill(busy = busy, enabled = key.isNotBlank() && (builtIn != null || address.isNotBlank() || ManagedClaimLink.find(key) != null)) { activate() }
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

/** The solid ink pill of KOne's main actions. */
@Composable
private fun StartPill(busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    val surface = knineSurface()
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(ink.copy(alpha = if (enabled || busy) 1f else 0.3f))
            .clickable(enabled = enabled && !busy, onClick = onClick)
            .padding(vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = surface, strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
        }
        androidx.compose.material3.Text(
            if (busy) "Verifying" else "Get started",
            color = surface,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
