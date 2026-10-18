package io.nekohasekai.sfa.compose.screen.dashboard

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import io.nekohasekai.sfa.compose.theme.BackdropOutlinedTextField as OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import io.nekohasekai.sfa.compose.theme.KnineGlassAlertDialog as AlertDialog
import io.nekohasekai.sfa.compose.theme.WallpaperGlass
import io.nekohasekai.sfa.compose.theme.LocalBackdrop
import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.constant.Status
import kotlinx.coroutines.delay

@Composable
internal fun CountryProfileDeck(
    profiles: List<Profile>,
    selectedProfileId: Long,
    onProfileSelected: (Long) -> Unit,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    serviceStatus: Status = Status.Stopped,
    onConnectRequested: () -> Unit = {},
) {
    val context = LocalContext.current
    val hasPhoto = LocalBackdrop.current != null
    val transparent = LocalBackdrop.current?.theme?.brightComposition == false
    val locale = LocalConfiguration.current.locales[0]
    val preferences = remember(context) { context.getSharedPreferences("knine_profile_categories", Context.MODE_PRIVATE) }
    var assignments by remember(preferences) {
        mutableStateOf(preferences.all.mapNotNull { (id, value) ->
            val profileId = id.toLongOrNull()
            if (profileId != null && value is String && CountryProfileGrouping.validKey(value)) profileId to value else null
        }.toMap())
    }
    val groups = remember(profiles, assignments) { CountryProfileGrouping.group(profiles, assignments) }
    var openCountry by rememberSaveable { mutableStateOf<String?>(null) }
    var defaultOpened by rememberSaveable { mutableStateOf(false) }
    var showAllCountry by rememberSaveable { mutableStateOf<String?>(null) }
    var editingProfileId by remember { mutableStateOf<Long?>(null) }
    var tappedProfileId by remember { mutableStateOf<Long?>(null) }
    var selectingProfileId by remember { mutableStateOf<Long?>(null) }
    var connectProfileId by remember { mutableStateOf<Long?>(null) }
    var tapStartIssued by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }
    var showCustomName by remember { mutableStateOf(false) }

    LaunchedEffect(profiles.map { it.id }) {
        if (!defaultOpened && profiles.isNotEmpty()) {
            openCountry = groups.firstOrNull { it.profiles.any { p -> p.id == selectedProfileId } }?.key
                ?: groups.firstOrNull { it.profiles.isNotEmpty() }?.key
            defaultOpened = true
        }
    }

    LaunchedEffect(selectingProfileId, selectedProfileId) {
        val id = selectingProfileId ?: return@LaunchedEffect
        if (selectedProfileId == id) selectingProfileId = null
        else {
            delay(5_000)
            if (selectingProfileId == id) selectingProfileId = null
        }
    }

    LaunchedEffect(connectProfileId, selectedProfileId, isLoading, serviceStatus) {
        val id = connectProfileId ?: return@LaunchedEffect
        if (serviceStatus != Status.Stopped) {
            connectProfileId = null
        } else if (selectedProfileId == id && !isLoading) {
            connectProfileId = null
            if (!tapStartIssued) {
                tapStartIssued = true
                onConnectRequested()
            }
        } else {
            // The original profile picker commits asynchronously. A quick
            // second tap must wait for that commit rather than start the old node.
            delay(5_000)
            if (connectProfileId == id) connectProfileId = null
        }
    }

    LaunchedEffect(tapStartIssued, serviceStatus) {
        if (tapStartIssued) {
            if (serviceStatus == Status.Stopped) delay(1_000)
            tapStartIssued = false
        }
    }

    fun tapProfile(profile: Profile) {
        val secondTap = tappedProfileId == profile.id &&
            (selectedProfileId == profile.id || selectingProfileId == profile.id)
        if (secondTap) {
            if (serviceStatus == Status.Stopped && !tapStartIssued) connectProfileId = profile.id
        } else {
            tappedProfileId = profile.id
            connectProfileId = null
            if (selectedProfileId != profile.id) {
                selectingProfileId = profile.id
                onProfileSelected(profile.id)
            }
        }
    }

    fun assign(profileId: Long, key: String) {
        if (!CountryProfileGrouping.validKey(key)) return
        preferences.edit().putString(profileId.toString(), key).apply()
        assignments = assignments + (profileId to key)
        openCountry = key
        showAllCountry = null
        editingProfileId = null
        showCustomName = false
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(if (hasPhoto) (-6).dp else 6.dp)) {
        groups.forEachIndexed { index, group ->
            val open = openCountry == group.key
            val all = showAllCountry == group.key
            val label = CountryProfileGrouping.label(group.key, locale)
            val shape = RoundedCornerShape(if (hasPhoto) 18.dp else 12.dp)
            val ink = MaterialTheme.colorScheme.onSurface
            Box(Modifier.fillMaxWidth().padding(start = if (hasPhoto) (index.coerceAtMost(3) * 8).dp else 0.dp)) {
                WallpaperGlass(
                    modifier = Modifier.fillMaxWidth()
                        .then(if (transparent) Modifier else Modifier.border(0.65.dp, ink.copy(alpha = 0.22f), shape))
                        .clip(shape),
                    shape = shape,
                    showBorder = !transparent,
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp).padding(bottom = 10.dp)) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable {
                                openCountry = if (open) null else group.key
                                showAllCountry = null
                            }.padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, Modifier.size(17.dp))
                            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(group.profiles.size.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (open && group.profiles.size > 2) TextButton(
                                onClick = { showAllCountry = if (all) null else group.key },
                                modifier = Modifier.widthIn(max = 104.dp),
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    if (all) stringResource(R.string.knine_country_show_two)
                                    else stringResource(R.string.knine_country_more, group.profiles.size - 2),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (open) {
                            if (group.profiles.isEmpty()) {
                                val selected = profiles.firstOrNull { it.id == selectedProfileId }
                                if (selected != null) TextButton(onClick = { assign(selected.id, group.key) }) {
                                    Text(stringResource(R.string.knine_classify_current), style = MaterialTheme.typography.labelMedium)
                                }
                                else Text(stringResource(R.string.knine_country_empty), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                            } else {
                                group.profiles.take(if (all) group.profiles.size else 2).forEach { profile ->
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Row(
                                            Modifier.weight(1f).heightIn(min = 44.dp)
                                                .clickable(enabled = !isLoading || tappedProfileId == profile.id) { tapProfile(profile) }
                                                .padding(horizontal = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        ) {
                                            Text(profile.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (profile.id == selectedProfileId) Icon(Icons.Default.Check, stringResource(R.string.knine_country_selected), Modifier.size(15.dp))
                                        }
                                        IconButton(onClick = { editingProfileId = profile.id; showCustomName = false }) {
                                            Icon(Icons.Default.MoreHoriz, stringResource(R.string.knine_country_change), Modifier.size(17.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (editingProfileId != null) {
        AlertDialog(
            onDismissRequest = { editingProfileId = null },
            title = { Text(stringResource(R.string.knine_country_change)) },
            text = {
                if (showCustomName) OutlinedTextField(
                    value = customName,
                    onValueChange = { customName = it.take(24).replace("\n", "") },
                    label = { Text(stringResource(R.string.knine_country_custom_name)) },
                    singleLine = true,
                ) else Column {
                    LazyColumn(Modifier.heightIn(max = 280.dp)) {
                        items(listOf(CountryProfileGrouping.UNCLASSIFIED) + CountryProfileGrouping.countries) { key ->
                            TextButton(onClick = { editingProfileId?.let { assign(it, key) } }, modifier = Modifier.fillMaxWidth()) {
                                Text(CountryProfileGrouping.label(key, locale), modifier = Modifier.fillMaxWidth())
                            }
                        }
                    }
                    TextButton(onClick = { showCustomName = true; customName = "" }) {
                        Text(stringResource(R.string.knine_country_custom_name))
                    }
                }
            },
            confirmButton = {
                if (showCustomName) TextButton(
                    onClick = { editingProfileId?.let { assign(it, "custom:${customName.trim()}") } },
                    enabled = customName.isNotBlank(),
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { editingProfileId = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}
