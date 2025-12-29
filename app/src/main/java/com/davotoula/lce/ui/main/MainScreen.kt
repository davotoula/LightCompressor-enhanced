package com.davotoula.lce.ui.main

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.davotoula.lce.R
import com.davotoula.lce.ui.components.VideoListItem
import kotlinx.coroutines.flow.collectLatest

/**
 * Main screen composable for the LCE app.
 *
 * This screen provides the primary user interface for video compression, including:
 * - Settings section with resolution presets, codec selection, streamable toggle, and bitrate control
 * - Video list showing selected videos and their compression progress
 * - Bottom action buttons for picking or recording videos
 *
 * @param viewModel The MainViewModel that manages the UI state and business logic
 * @param onNavigateToPlayer Callback invoked when user wants to play a compressed video
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel = viewModel(),
    onNavigateToPlayer: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Video picker launcher
    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.onAction(MainAction.SelectVideos(uris))
        }
    }

    // Handle navigation events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is MainEvent.NavigateToPlayer -> onNavigateToPlayer(event.videoPath)
            }
        }
    }

    // Show toast messages
    LaunchedEffect(uiState.toastMessage) {
        uiState.toastMessage?.let { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            viewModel.onAction(MainAction.ClearToast)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(text = stringResource(R.string.home_title))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                actions = {
                    if (uiState.isCompressing) {
                        IconButton(onClick = { viewModel.onAction(MainAction.CancelCompression) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Cancel compression",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Settings Section (scrollable)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // 1. Resolution Section
                Text(
                    text = stringResource(R.string.resize_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Resolution Preset Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Resolution.entries.forEach { resolution ->
                        FilterChip(
                            selected = uiState.selectedResolution == resolution && uiState.customResolution == null,
                            onClick = { viewModel.onAction(MainAction.SetResolution(resolution)) },
                            label = { Text(resolution.label) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Custom Resolution TextField
                OutlinedTextField(
                    value = uiState.customResolutionInput,
                    onValueChange = { value ->
                        if (value.all { it.isDigit() }) {
                            viewModel.onAction(MainAction.SetCustomResolutionInput(value))
                        }
                    },
                    label = { Text(stringResource(R.string.resize_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 2. Codec Section
                Text(
                    text = stringResource(R.string.codec_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Codec.entries.forEachIndexed { index, codec ->
                        SegmentedButton(
                            selected = uiState.selectedCodec == codec,
                            onClick = { viewModel.onAction(MainAction.SetCodec(codec)) },
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = Codec.entries.size
                            )
                        ) {
                            Text(codec.displayName)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.codec_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 3. Streamable Section
                Text(
                    text = stringResource(R.string.streamable_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SegmentedButton(
                        selected = uiState.isStreamableEnabled,
                        onClick = { viewModel.onAction(MainAction.SetStreamable(true)) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                    ) {
                        Text(stringResource(R.string.streamable_yes))
                    }
                    SegmentedButton(
                        selected = !uiState.isStreamableEnabled,
                        onClick = { viewModel.onAction(MainAction.SetStreamable(false)) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                    ) {
                        Text(stringResource(R.string.streamable_no))
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.streamable_explanation),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                // 4. Bitrate Section
                Text(
                    text = stringResource(R.string.bitrate_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { viewModel.onAction(MainAction.CalculateAutoBitrate) }
                    ) {
                        Text(stringResource(R.string.bitrate_auto))
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    OutlinedTextField(
                        value = uiState.bitrateInput,
                        onValueChange = { value ->
                            if (value.all { it.isDigit() }) {
                                viewModel.onAction(MainAction.SetBitrateInput(value))
                            }
                        },
                        label = { Text(stringResource(R.string.bitrate_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Video List Section
                if (uiState.videos.isNotEmpty()) {
                    Text(
                        text = "Selected Videos",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    // Video items in a Column (inside the scrollable section)
                    uiState.videos.forEach { video ->
                        VideoListItem(
                            video = video,
                            onClick = {
                                video.playableVideoPath?.let { path ->
                                    viewModel.onAction(MainAction.PlayVideo(path))
                                }
                            },
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            // Bottom Buttons Section
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Start Compression Button (shown when videos are selected)
                if (uiState.videos.isNotEmpty() && !uiState.isCompressing) {
                    Button(
                        onClick = { viewModel.onAction(MainAction.StartCompression) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.start_compression))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            videoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                            )
                        },
                        enabled = !uiState.isCompressing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.pick_video))
                    }

                    OutlinedButton(
                        onClick = {
                            // Record video functionality would be handled here
                            // This typically opens the camera app with video capture intent
                        },
                        enabled = !uiState.isCompressing,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.record_video))
                    }
                }
            }
        }
    }
}
