package com.davotoula.lce.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.davotoula.lce.R
import com.davotoula.lce.ui.components.SettingsSummary

@Composable
fun CollapsibleSettingsCard(
    uiState: MainUiState,
    onAction: (MainAction) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column {
            // Header row - always visible, clickable to toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAction(MainAction.ToggleSettings) }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (uiState.isSettingsExpanded) {
                    Text(
                        text = "Settings",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    SettingsSummary(
                        resolution = uiState.selectedResolution,
                        customResolution = uiState.customResolution,
                        codec = uiState.selectedCodec,
                        bitrateKbps = uiState.bitrateKbps,
                        isStreamable = uiState.isStreamableEnabled,
                        modifier = Modifier.weight(1f)
                    )
                }
                Icon(
                    imageVector = if (uiState.isSettingsExpanded)
                        Icons.Default.KeyboardArrowUp
                    else
                        Icons.Default.KeyboardArrowDown,
                    contentDescription = if (uiState.isSettingsExpanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Expandable content
            AnimatedVisibility(
                visible = uiState.isSettingsExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
                ) {
                    // Resolution Section
                    Text(
                        text = stringResource(R.string.resize_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Resolution.entries.forEach { resolution ->
                            FilterChip(
                                selected = uiState.selectedResolution == resolution && uiState.customResolution == null,
                                onClick = { onAction(MainAction.SetResolution(resolution)) },
                                label = { Text(resolution.label) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = uiState.customResolutionInput,
                        onValueChange = { value ->
                            if (value.all { it.isDigit() }) {
                                onAction(MainAction.SetCustomResolutionInput(value))
                            }
                        },
                        label = { Text(stringResource(R.string.resize_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Codec Section
                    Text(
                        text = stringResource(R.string.codec_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Codec.entries.forEachIndexed { index, codec ->
                            SegmentedButton(
                                selected = uiState.selectedCodec == codec,
                                onClick = { onAction(MainAction.SetCodec(codec)) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = Codec.entries.size
                                )
                            ) {
                                Text(codec.displayName)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Streamable Section
                    Text(
                        text = stringResource(R.string.streamable_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        SegmentedButton(
                            selected = uiState.isStreamableEnabled,
                            onClick = { onAction(MainAction.SetStreamable(true)) },
                            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                        ) {
                            Text(stringResource(R.string.streamable_yes))
                        }
                        SegmentedButton(
                            selected = !uiState.isStreamableEnabled,
                            onClick = { onAction(MainAction.SetStreamable(false)) },
                            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                        ) {
                            Text(stringResource(R.string.streamable_no))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Bitrate Section
                    Text(
                        text = stringResource(R.string.bitrate_label),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = { onAction(MainAction.CalculateAutoBitrate) }
                        ) {
                            Text(stringResource(R.string.bitrate_auto))
                        }

                        Spacer(modifier = Modifier.width(16.dp))

                        OutlinedTextField(
                            value = uiState.bitrateInput,
                            onValueChange = { value ->
                                if (value.all { it.isDigit() }) {
                                    onAction(MainAction.SetBitrateInput(value))
                                }
                            },
                            label = { Text(stringResource(R.string.bitrate_hint)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}
