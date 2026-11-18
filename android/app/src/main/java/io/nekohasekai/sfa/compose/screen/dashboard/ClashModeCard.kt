package io.nekohasekai.sfa.compose.screen.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.outlined.Tune
import io.nekohasekai.sfa.compose.theme.BackdropCard as Card
import io.nekohasekai.sfa.compose.theme.KnineDropdownMenu as DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import io.nekohasekai.sfa.compose.theme.BackdropIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import io.nekohasekai.sfa.compose.theme.BackdropClickableSurface as Surface
import io.nekohasekai.sfa.compose.theme.BackdropText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.nekohasekai.sfa.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClashModeCard(modes: List<String>, selectedMode: String, onModeSelected: (String) -> Unit, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Tune,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.knine_current_mode),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
            ) {
                val textMeasurer = rememberTextMeasurer()
                val textStyle = MaterialTheme.typography.labelLarge
                val density = LocalDensity.current

                val labels = remember(modes) { modes.map { modeLabel(it) } }
                val labelWidth = remember(labels, textStyle, textMeasurer, density) {
                    labels.maxOfOrNull { label ->
                        textMeasurer.measure(label, textStyle.copy(fontWeight = FontWeight.SemiBold)).size.width
                    } ?: 0
                }
                // Segments have equal widths. Measure what is actually displayed,
                // including the reserved checkmark and the button's inner spacing.
                val segmentChrome = with(density) { 50.dp.roundToPx() }
                val estimatedWidth = (labelWidth + segmentChrome) * modes.size
                val availableWidth = with(density) { maxWidth.roundToPx() }

                val useDropdown = estimatedWidth > availableWidth

                if (useDropdown) {
                    ModeDropdown(
                        modes = modes,
                        selectedMode = selectedMode,
                        onModeSelected = onModeSelected,
                    )
                } else {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        modes.forEachIndexed { index, mode ->
                            val selected = mode == selectedMode
                            val indicatorColor = MaterialTheme.colorScheme.primary
                            SegmentedButton(
                                modifier = Modifier.heightIn(min = 48.dp).drawBehind {
                                    if (selected) {
                                        val halfWidth = 12.dp.toPx()
                                        drawLine(
                                            color = indicatorColor,
                                            start = Offset(size.width / 2 - halfWidth, size.height - 3.dp.toPx()),
                                            end = Offset(size.width / 2 + halfWidth, size.height - 3.dp.toPx()),
                                            strokeWidth = 2.dp.toPx(),
                                            cap = StrokeCap.Round,
                                        )
                                    }
                                },
                                shape =
                                SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = modes.size,
                                ),
                                onClick = { onModeSelected(mode) },
                                selected = selected,
                                icon = {
                                    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                                        if (selected) {
                                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp), emphasized = true)
                                        }
                                    }
                                },
                                border = BorderStroke(0.dp, Color.Transparent),
                                colors = SegmentedButtonDefaults.colors(
                                    activeContainerColor = Color.Transparent,
                                    inactiveContainerColor = Color.Transparent,
                                    activeContentColor = MaterialTheme.colorScheme.primary,
                                    inactiveContentColor = MaterialTheme.colorScheme.onSurface,
                                ),
                            ) {
                                Text(
                                    text = modeLabel(mode),
                                    emphasized = selected,
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                            }
                        }
                    }
                }
            }
            modeHint(selectedMode)?.let { hint ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ModeDropdown(modes: List<String>, selectedMode: String, onModeSelected: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = modeLabel(selectedMode),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    emphasized = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Default.UnfoldMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            modes.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(modeLabel(mode), fontWeight = if (mode == selectedMode) FontWeight.SemiBold else FontWeight.Normal) },
                    onClick = {
                        onModeSelected(mode)
                        expanded = false
                    },
                    leadingIcon = {
                        if (mode == selectedMode) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun modeLabel(mode: String): String = when (mode.lowercase()) {
    "rule" -> stringResource(R.string.knine_mode_rule)
    "global" -> stringResource(R.string.knine_mode_global)
    "direct" -> stringResource(R.string.knine_mode_direct)
    else -> mode
}

@Composable
private fun modeHint(mode: String): String? = when (mode.lowercase()) {
    "rule" -> stringResource(R.string.knine_mode_rule_hint)
    "global" -> stringResource(R.string.knine_mode_global_hint)
    "direct" -> stringResource(R.string.knine_mode_direct_hint)
    else -> null
}
