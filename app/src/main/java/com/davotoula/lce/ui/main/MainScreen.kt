package com.davotoula.lce.ui.main

import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
 * @param initialVideoUris Videos shared via Android "Share to" intent
 * @param viewModel The MainViewModel that manages the UI state and business logic
 * @param onNavigateToPlayer Callback invoked when user wants to play a compressed video
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    initialVideoUris: List<Uri> = emptyList(),
    viewModel: MainViewModel = viewModel(),
    onNavigateToPlayer: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Handle videos shared via "Share to" intent
    LaunchedEffect(initialVideoUris) {
        if (initialVideoUris.isNotEmpty()) {
            viewModel.onAction(MainAction.SelectVideos(initialVideoUris))
        }
    }

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
            // Main content (scrollable)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
                // Collapsible Settings Card
                CollapsibleSettingsCard(
                    uiState = uiState,
                    onAction = viewModel::onAction
                )

                Spacer(modifier = Modifier.height(16.dp))

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
