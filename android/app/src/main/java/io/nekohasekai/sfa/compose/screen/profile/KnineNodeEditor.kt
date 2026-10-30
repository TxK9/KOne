package io.nekohasekai.sfa.compose.screen.profile

import android.widget.Toast
import androidx.compose.foundation.relocation.bringIntoViewRequester
import io.nekohasekai.sfa.compose.theme.cityLine
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Link

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.compose.screen.dashboard.FitText
import io.nekohasekai.sfa.compose.screen.dashboard.FlagStrip
import io.nekohasekai.sfa.compose.screen.dashboard.HealthLabel
import io.nekohasekai.sfa.compose.screen.dashboard.KnineArrive
import io.nekohasekai.sfa.compose.screen.dashboard.NodeCategories
import io.nekohasekai.sfa.compose.screen.dashboard.NodeCategoryStore
import io.nekohasekai.sfa.compose.screen.dashboard.NodeLatency
import io.nekohasekai.sfa.compose.screen.dashboard.NodeWeather
import io.nekohasekai.sfa.compose.screen.dashboard.knineTone
import io.nekohasekai.sfa.compose.screen.dashboard.weatherInk
import io.nekohasekai.sfa.compose.theme.BackdropCard
import io.nekohasekai.sfa.compose.theme.BackdropContrastScope
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as BText
import io.nekohasekai.sfa.compose.theme.KnineMotion
import io.nekohasekai.sfa.compose.theme.KnineSwitch
import io.nekohasekai.sfa.compose.util.FlagGlyph
import io.nekohasekai.sfa.compose.util.FlagIcons
import io.nekohasekai.sfa.compose.util.ProfileIconGlyph
import io.nekohasekai.sfa.compose.util.RelativeTimeFormatter
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.ProfileManager
import io.nekohasekai.sfa.database.TypedProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Node appearance and update preferences. Configuration and subscription details stay
 * inside the service and are never exposed through the editor.
 */
@Composable
internal fun KnineNodeEditor(
    state: EditProfileUiState,
    onName: (String) -> Unit,
    onIcon: (String?) -> Unit,
    onMoreIcons: () -> Unit,
    onAutoUpdate: (Boolean) -> Unit,
    onAutoUpdateInterval: (String) -> Unit,
    onLeaveOut: (String) -> Unit,
    onUpdateNow: () -> Unit,
    onDeleted: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remote = state.profileType == TypedProfile.Type.Remote
    var flagsOpen by remember { mutableStateOf(false) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(760, easing = LinearEasing)) }
    fun Modifier.settle(order: Int): Modifier = graphicsLayer {
        val p = KnineArrive.transform(((appear.value - order * 0.08f) / 0.5f).coerceIn(0f, 1f))
        alpha = p
        translationY = (1f - p) * 18.dp.toPx()
    }

    val managed = io.nekohasekai.sfa.managed.ManagedState.isManaged(profile.id)
    BackdropContrastScope(modifier) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            NodeBadge(icon = state.icon, open = flagsOpen, onClick = { flagsOpen = !flagsOpen }, modifier = Modifier.settle(0).padding(top = 8.dp))
            AnimatedVisibility(visible = flagsOpen, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
                FlagStrip(
                    current = FlagIcons.codeOf(state.icon),
                    onPick = { code -> onIcon(code?.let(FlagIcons::idFor)) },
                    onFinish = { flagsOpen = false },
                    modifier = Modifier.padding(top = 10.dp),
                    onMore = onMoreIcons,
                )
            }
            NameField(name = state.name, onName = onName, modifier = Modifier.settle(1).padding(top = 14.dp))
            NodeSummary(profile = profile, savedName = state.originalName, modifier = Modifier.settle(2).padding(top = 4.dp))
            Spacer(Modifier.height(26.dp))
            BackdropCard(Modifier.fillMaxWidth().settle(3)) {
                CategoryChoice(profile = profile, savedName = state.originalName)
                if (io.nekohasekai.sfa.compose.component.canShareNode(profile)) {
                    Hairline()
                    ChainChoice(profile = profile)
                    Hairline()
                    ShareChoice(profile = profile)
                }
                if (remote) {
                    Hairline()
                    RemoteOptions(state, onAutoUpdate, onAutoUpdateInterval, onLeaveOut, onUpdateNow)
                }
            }
            if (managed) {
                Text(
                    "Managed by the claim service",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.settle(4).padding(top = 18.dp, bottom = 24.dp).graphicsLayer { alpha = 0.7f },
                )
            } else {
                DeleteNode(profile = profile, onDeleted = onDeleted, modifier = Modifier.settle(4).padding(top = 22.dp, bottom = 24.dp))
            }
        }
    }

}

/** The node's flag in a circle; tapping it lays the flags out below. */
@Composable
private fun NodeBadge(icon: String?, open: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val ink = LocalContentColor.current
    val surface = knineTone()
    val lift by animateFloatAsState(if (open) 1.06f else 1f, tween(280, easing = KnineArrive), label = "badgeLift")
    Box(modifier.size(92.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(84.dp)
                .graphicsLayer {
                    scaleX = lift
                    scaleY = lift
                }
                .clip(CircleShape)
                .background(ink.copy(alpha = 0.08f))
                .border(0.8.dp, ink.copy(alpha = if (open) 0.55f else 0.2f), CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = icon,
                transitionSpec = {
                    (scaleIn(tween(320, easing = KnineArrive), initialScale = 0.6f) + fadeIn(tween(200, delayMillis = 60)))
                        .togetherWith(scaleOut(tween(160), targetScale = 0.6f) + fadeOut(tween(140)))
                },
                label = "badgeIcon",
            ) { id ->
                ProfileIconGlyph(iconId = id, fallback = Icons.AutoMirrored.Filled.InsertDriveFile, size = 42.dp)
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 4.dp, bottom = 4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(ink),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(Icons.Default.Edit, contentDescription = "Change icon", tint = surface, modifier = Modifier.size(13.dp))
        }
    }
}

/** The name, large and centred; it is typed over in place and underlined while it has focus. */
@Composable
private fun NameField(name: String, onName: (String) -> Unit, modifier: Modifier) {
    val ink = LocalContentColor.current
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    val underline by animateFloatAsState(if (focused) 1f else 0f, tween(220), label = "nameUnderline")
    BasicTextField(
        value = name,
        onValueChange = { onName(it.replace('\n', ' ').take(40)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.headlineSmall.copy(
            color = ink,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        ),
        cursorBrush = SolidColor(ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        // The city's shade gathers behind the name, as behind every label (CityCanvas).
        modifier = modifier
            .fillMaxWidth(0.82f)
            .cityLine(large = true)
            .onFocusChanged { focused = it.isFocused }
            .drawBehind {
                if (underline > 0f) {
                    val half = size.width * 0.5f * underline
                    val y = size.height
                    drawLine(ink.copy(alpha = 0.5f), Offset(size.width / 2f - half, y), Offset(size.width / 2f + half, y), strokeWidth = 1.2.dp.toPx())
                }
            }
            .padding(vertical = 4.dp),
    )
}

/** Live health and the category, in one quiet line under the name. */
@Composable
private fun NodeSummary(profile: Profile, savedName: String, modifier: Modifier) {
    val state = NodeCategoryStore.state
    val group = NodeCategories.categoryFor(profile.id, savedName, state).let { id ->
        if (id == NodeCategories.FALLBACK_ID) state.fallbackName to state.fallbackFlag else state.categories.firstOrNull { it.id == id }?.let { it.name to it.flag }
    }
    val reading = NodeLatency[profile.id]
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (reading != null) {
            HealthLabel(reading, large = true)
            if (group != null) {
                BText("·", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 8.dp).graphicsLayer { alpha = 0.6f })
            }
        }
        group?.let { (name, flag) ->
            flag?.let {
                FlagGlyph(it, size = 15.dp)
                Spacer(Modifier.width(4.dp))
            }
            BText(name, style = MaterialTheme.typography.labelLarge, modifier = Modifier.graphicsLayer { alpha = 0.8f })
        }
    }
}

/** Every category as a chip; the node's own one is filled. Choosing moves the node at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChoice(profile: Profile, savedName: String) {
    val state = NodeCategoryStore.state
    val current = NodeCategories.categoryFor(profile.id, savedName, state)
    val targets = listOf(Triple(NodeCategories.FALLBACK_ID, state.fallbackName, state.fallbackFlag)) +
        state.categories.map { Triple(it.id, it.name, it.flag) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
        BText("Categories", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 2.dp, bottom = 10.dp).graphicsLayer { alpha = 0.7f })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            targets.forEach { (id, label, flag) ->
                CategoryChip(label = label, flag = flag, chosen = id == current) { NodeCategoryStore.assign(profile.id, id) }
            }
        }
    }
}

/**
 * The node in front of this one, chosen in place: Off, or any other node of the person's own. A pick
 * is tried on the core first and kept only if the chained configuration holds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainChoice(profile: Profile) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var trying by remember { mutableStateOf(false) }
    @Suppress("UNUSED_VARIABLE") val revision = io.nekohasekai.sfa.selfuse.NodeChain.revision
    val via = io.nekohasekai.sfa.selfuse.NodeChain.via(profile.id)
    val others by androidx.compose.runtime.produceState(emptyList<Profile>(), profile.id) {
        value = withContext(Dispatchers.IO) { ProfileManager.list() }.filter { other ->
            other.id != profile.id && !io.nekohasekai.sfa.managed.ManagedState.isManaged(other.id) &&
                io.nekohasekai.sfa.selfuse.NodeChain.via(other.id) != profile.id
        }
    }
    val front = others.firstOrNull { it.id == via }
    fun pick(choice: Profile?) {
        if (choice == null) { io.nekohasekai.sfa.selfuse.NodeChain.set(profile.id, null); return }
        trying = true
        scope.launch {
            val problem = withContext(Dispatchers.IO) {
                runCatching {
                    val exit = java.io.File(profile.typed.path).readText()
                    val chained = io.nekohasekai.sfa.selfuse.NodeChain.chain(exit, java.io.File(choice.typed.path).readText(), choice.name)
                        ?: return@runCatching "${choice.name} cannot carry another node."
                    io.nekohasekai.libbox.Libbox.checkConfig(io.nekohasekai.sfa.selfuse.HomeSettings.prepare(chained))
                    null
                }.getOrElse { it.message?.lineSequence()?.firstOrNull()?.take(120) ?: "This chain does not hold." }
            }
            trying = false
            if (problem == null) io.nekohasekai.sfa.selfuse.NodeChain.set(profile.id, choice.id)
            else Toast.makeText(context, problem, Toast.LENGTH_SHORT).show()
        }
    }
    ActionRow(Icons.Outlined.Link, "Chain through", onClick = { open = !open }) {
        // The front node's whole name: smaller when long, then on two lines, never cut.
        Row(Modifier.graphicsLayer { alpha = if (trying) 0.4f else 0.75f }, verticalAlignment = Alignment.CenterVertically) {
            val frontFlag = front?.let { FlagIcons.codeFor(it.icon, it.name) }
            frontFlag?.let { FlagGlyph(it, size = 16.dp); Spacer(Modifier.width(6.dp)) }
            FitText(front?.name ?: "Off", MaterialTheme.typography.bodyMedium, alignEnd = true, minSp = 11f, lines = 2, modifier = Modifier.weight(1f, fill = false))
        }
    }
    AnimatedVisibility(visible = open, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
        val bloom = rememberBloom()
        FlowRow(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CategoryChip(label = "Off", flag = null, chosen = front == null, modifier = Modifier.bloomIn(bloom, 0)) { pick(null) }
            others.forEachIndexed { index, other ->
                CategoryChip(
                    label = other.name,
                    flag = FlagIcons.codeFor(other.icon, other.name),
                    chosen = other.id == via,
                    modifier = Modifier.bloomIn(bloom, index + 1),
                ) { if (!trying) pick(other) }
            }
        }
    }
}

/** The node's QR code and link, unfolding under the row and scrolled into view with its buttons. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ShareChoice(profile: Profile) {
    var open by remember { mutableStateOf(false) }
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    LaunchedEffect(open) {
        // Once the panel has unfolded, so the page scrolls by its full height.
        if (open) { kotlinx.coroutines.delay(380); bring.bringIntoView() }
    }
    ActionRow(Icons.Outlined.QrCode2, "Share", onClick = { open = !open }) {
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            modifier = Modifier.size(18.dp).graphicsLayer { alpha = 0.5f },
        )
    }
    AnimatedVisibility(visible = open, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
        io.nekohasekai.sfa.compose.component.NodeSharePanel(profile, Modifier.bringIntoViewRequester(bring))
    }
}

/** The clock for a row of choices unfolding: each one fades and settles a beat after the one before. */
@Composable
internal fun rememberBloom(): Animatable<Float, *> {
    val bloom = remember { Animatable(0f) }
    LaunchedEffect(Unit) { bloom.animateTo(1f, tween(560, easing = LinearEasing)) }
    return bloom
}

internal fun Modifier.bloomIn(bloom: Animatable<Float, *>, index: Int): Modifier = graphicsLayer {
    val p = KnineArrive.transform(((bloom.value - minOf(index, 8) * 0.05f) / 0.55f).coerceIn(0f, 1f))
    alpha = p
    translationY = (1f - p) * 6.dp.toPx()
}

/** A choice in ink: a faint wash, filled with the ink itself once chosen. A long name wraps inside it. */
@Composable
internal fun CategoryChip(label: String, flag: String?, chosen: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val ink = LocalContentColor.current
    val surface = knineTone()
    val fill by animateFloatAsState(if (chosen) 1f else 0f, tween(240), label = "chipFill")
    Row(
        modifier
            .clip(RoundedCornerShape(17.dp))
            .background(lerp(ink.copy(alpha = 0.07f), ink, fill))
            .clickable(enabled = !chosen, onClick = onClick)
            .heightIn(min = 34.dp)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        flag?.let {
            FlagGlyph(it, size = 16.dp)
            Spacer(Modifier.width(5.dp))
        }
        Text(label, color = lerp(ink, surface, fill), style = MaterialTheme.typography.labelLarge, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Medium)
    }
}

@Composable
private fun Hairline() {
    val ink = LocalContentColor.current
    Box(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .height(0.7.dp)
            .background(ink.copy(alpha = 0.12f)),
    )
}

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 54.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        BText(label, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(12.dp))
        // Whatever the label leaves, the value on the right may use, so it is never cut short.
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
            if (trailing != null) {
                trailing()
            } else {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(18.dp).graphicsLayer { alpha = 0.5f })
            }
        }
    }
}

/** Refresh preferences without exposing a subscription address. */
@Composable
private fun RemoteOptions(
    state: EditProfileUiState,
    onAutoUpdate: (Boolean) -> Unit,
    onAutoUpdateInterval: (String) -> Unit,
    onLeaveOut: (String) -> Unit,
    onUpdateNow: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.padding(vertical = 6.dp)) {
        // Words that leave nodes out, as KOne for Windows offers per subscription.
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            BText("Leave out nodes named", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp).graphicsLayer { alpha = 0.7f })
            PillField(value = state.leaveOut, onValueChange = onLeaveOut, modifier = Modifier.fillMaxWidth(), keyboardType = KeyboardType.Text, placeholder = "Trial | Expired")
        }
        ActionRow(Icons.Default.Update, "Automatic updates", onClick = { onAutoUpdate(!state.autoUpdate) }) {
            KnineSwitch(checked = state.autoUpdate, onCheckedChange = onAutoUpdate)
        }
        AnimatedVisibility(visible = state.autoUpdate, enter = KnineMotion.reveal(), exit = KnineMotion.conceal()) {
            Column(Modifier.padding(start = 50.dp, end = 16.dp, bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BText("Interval (minutes)", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    PillField(
                        value = state.autoUpdateInterval.toString(),
                        onValueChange = onAutoUpdateInterval,
                        keyboardType = KeyboardType.Number,
                        modifier = Modifier.width(96.dp),
                    )
                }
                state.autoUpdateIntervalError?.let {
                    BText(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        ActionRow(
            Icons.Default.Update,
            "Update now",
            onClick = { if (!state.isUpdating && !state.showUpdateSuccess) onUpdateNow() },
        ) {
            when {
                state.isUpdating -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
                state.showUpdateSuccess -> Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                else -> state.lastUpdated?.let {
                    BText(RelativeTimeFormatter.format(context, it), style = MaterialTheme.typography.labelMedium, modifier = Modifier.graphicsLayer { alpha = 0.7f })
                }
            }
        }
    }
}

@Composable
private fun PillField(value: String, onValueChange: (String) -> Unit, modifier: Modifier, keyboardType: KeyboardType = KeyboardType.Uri, placeholder: String? = null) {
    val ink = LocalContentColor.current
    val focusManager = LocalFocusManager.current
    val shape = RoundedCornerShape(16.dp)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = ink),
        cursorBrush = SolidColor(ink),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier
            .clip(shape)
            .background(ink.copy(alpha = 0.07f))
            .border(0.7.dp, ink.copy(alpha = 0.16f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        decorationBox = { field ->
            Box {
                if (value.isEmpty() && placeholder != null) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = ink.copy(alpha = 0.45f), maxLines = 1)
                }
                field()
            }
        },
    )
}

/** Delete takes a second tap within three seconds. */
@Composable
private fun DeleteNode(profile: Profile, onDeleted: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val ink = LocalContentColor.current
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3_000)
            armed = false
        }
    }
    val surface = knineTone()
    // Cinnabar, the weather's own red for a node that is gone.
    val danger = weatherInk(NodeWeather.Cut, ink)
    val fill by animateFloatAsState(if (armed) 1f else 0f, tween(200), label = "deleteFill")
    // A frosted pill, so the red reads over any photograph; armed, it turns red itself.
    Row(
        modifier
            .clip(RoundedCornerShape(22.dp))
            .background(lerp(surface.copy(alpha = 0.62f), danger.copy(alpha = 0.24f), fill))
            .border(0.7.dp, danger.copy(alpha = 0.35f + 0.3f * fill), RoundedCornerShape(22.dp))
            .clickable {
                if (!armed) {
                    armed = true
                } else {
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { ProfileManager.delete(profile) }
                            NodeCategoryStore.forget(profile.id)
                            NodeLatency.forget(profile.id)
                            android.widget.Toast.makeText(context, "Deleted ${profile.name}", android.widget.Toast.LENGTH_SHORT).show()
                            onDeleted()
                        } catch (e: Exception) {
                            android.widget.Toast.makeText(context, "Deletion incomplete. Try again later.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(Icons.Outlined.DeleteOutline, contentDescription = null, tint = danger, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        AnimatedContent(
            targetState = armed,
            transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(140)) },
            label = "deleteLabel",
        ) { second ->
            Text(if (second) "Tap again to delete" else "Delete node", color = danger, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}
