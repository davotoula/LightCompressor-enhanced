package com.davotoula.lce.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Stream
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.davotoula.lce.BuildConfig
import com.davotoula.lce.R
import com.davotoula.lce.ui.components.VideoListItem
import com.davotoula.lce.ui.hls.HlsRenditionState
import com.davotoula.lce.ui.hls.HlsRenditionStatus
import com.davotoula.lce.ui.hls.HlsTerminal
import com.davotoula.lce.ui.hls.HlsTestState
import com.davotoula.lce.ui.hls.HlsViewModel
import kotlinx.coroutines.flow.collectLatest
import java.io.File

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
@Suppress("LongMethod")
@Composable
fun MainScreen(
    initialVideoUris: List<Uri> = emptyList(),
    viewModel: MainViewModel = viewModel(),
    hlsViewModel: HlsViewModel,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onNavigateToHls: () -> Unit,
    onNavigateToPlayer: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val hlsUiState by hlsViewModel.uiState.collectAsStateWithLifecycle()
    val isHlsRunning = hlsUiState.testState?.isRunning == true
    val context = LocalContext.current

    // Handle videos shared via "Share to" intent
    LaunchedEffect(initialVideoUris) {
        if (initialVideoUris.isNotEmpty()) {
            viewModel.onAction(MainAction.SelectVideos(initialVideoUris))
        }
    }

    // Video picker launcher
    val videoPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                viewModel.onAction(MainAction.SelectVideos(uris))
            }
        }

    val hlsVideoPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) {
                viewModel.onAction(MainAction.StartHlsPreparation(uri))
            }
        }

    // GIF picker launcher (separate so we can constrain to image/gif)
    val gifPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(),
        ) { uris ->
            if (uris.isNotEmpty()) {
                viewModel.onAction(MainAction.SelectVideos(uris))
            }
        }

    val permissionRequiredMessage = stringResource(R.string.permission_required_pick_videos)
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = RequestMultiplePermissions(),
        ) { permissions ->
            val granted = permissions.any { it.value }
            if (granted) {
                videoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                )
            } else {
                Toast.makeText(context, permissionRequiredMessage, Toast.LENGTH_SHORT).show()
            }
        }

    // Video capture state and launcher
    var captureVideoUri by remember { mutableStateOf<Uri?>(null) }

    val videoCaptureContract =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CaptureVideo(),
        ) { success ->
            if (success) {
                captureVideoUri?.let { uri ->
                    viewModel.onAction(MainAction.SelectVideos(listOf(uri)))
                }
            }
        }

    val cameraPermissionMessage = stringResource(R.string.permission_required_camera)
    val cameraPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            if (granted) {
                launchVideoCapture(context) { uri ->
                    captureVideoUri = uri
                    videoCaptureContract.launch(uri)
                }
            } else {
                Toast.makeText(context, cameraPermissionMessage, Toast.LENGTH_SHORT).show()
            }
        }

    // Handle navigation events
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is MainEvent.NavigateToPlayer -> onNavigateToPlayer(event.videoPath)
                MainEvent.LaunchHlsPicker ->
                    hlsVideoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = stringResource(R.string.home_title))
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onToggleTheme) {
                            val icon =
                                if (isDarkTheme) {
                                    Icons.Default.LightMode
                                } else {
                                    Icons.Default.DarkMode
                                }
                            val description =
                                if (isDarkTheme) {
                                    stringResource(R.string.switch_to_light_mode)
                                } else {
                                    stringResource(R.string.switch_to_dark_mode)
                                }
                            Icon(
                                imageVector = icon,
                                contentDescription = description,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
                actions = {
                    IconButton(onClick = onNavigateToHls) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.Stream,
                                contentDescription = stringResource(R.string.hls_icon_description),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (isHlsRunning) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(8.dp)
                                            .offset(x = 6.dp, y = (-2).dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error),
                                )
                            }
                        }
                    }
                    if (uiState.isCompressing) {
                        IconButton(onClick = { viewModel.onAction(MainAction.CancelCompression) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel_compression),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            // Main content (scrollable)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
            ) {
                // Collapsible Settings Card
                CollapsibleSettingsCard(
                    uiState = uiState,
                    onAction = viewModel::onAction,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Video List Section
                if (uiState.videos.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.selected_videos),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
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
                            onShare = {
                                video.playableVideoPath?.let { path ->
                                    shareVideo(context, path)
                                }
                            },
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }

                uiState.hlsTestState?.let { hlsState ->
                    Spacer(modifier = Modifier.height(16.dp))
                    HlsTestStatusCard(
                        state = hlsState,
                        onCancel = { viewModel.onAction(MainAction.CancelHlsPreparation) },
                        onClose = { viewModel.onAction(MainAction.CloseHlsTestState) },
                        onPlay = { path -> viewModel.onAction(MainAction.PlayVideo(path)) },
                    )
                }
            }

            // Bottom Buttons Section
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HlsControlsRow(
                    selectedCodec = uiState.hlsCodec,
                    isRunning = uiState.hlsTestState?.isRunning == true,
                    onSelectCodec = { codec -> viewModel.onAction(MainAction.SetHlsCodec(codec)) },
                    onPrepareHls = { viewModel.onAction(MainAction.PickHlsVideo) },
                )

                // Start Compression Button (shown when videos are selected)
                if (uiState.videos.isNotEmpty() && !uiState.isCompressing) {
                    Button(
                        onClick = { viewModel.onAction(MainAction.StartCompression) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.start_compression))
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (hasVideoPermission(context)) {
                                videoPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                                )
                            } else {
                                permissionLauncher.launch(requiredVideoPermissions())
                            }
                        },
                        enabled = !uiState.isCompressing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.pick_video))
                    }

                    OutlinedButton(
                        onClick = {
                            if (hasCameraPermission(context)) {
                                launchVideoCapture(context) { uri ->
                                    captureVideoUri = uri
                                    videoCaptureContract.launch(uri)
                                }
                            } else {
                                cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
                            }
                        },
                        enabled = !uiState.isCompressing,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.record_video))
                    }
                }

                OutlinedButton(
                    onClick = {
                        // The system photo picker does not require runtime permissions
                        // on Android 11+; on older releases PickVisualMedia falls back
                        // to GetContent which uses its own access model. Launching the
                        // picker directly keeps the flow simple and works across versions.
                        gifPickerLauncher.launch(
                            PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.SingleMimeType("image/gif"),
                            ),
                        )
                    },
                    enabled = !uiState.isCompressing,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.pick_gif))
                }
            }

            // Version info
            Text(
                text = "App v${BuildConfig.VERSION_NAME} • Lib v${BuildConfig.LIBRARY_VERSION}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

private fun hasVideoPermission(context: Context): Boolean {
    val permissions = requiredVideoPermissions()
    return permissions.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
}

private fun requiredVideoPermissions(): Array<String> =
    when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            arrayOf(
                android.Manifest.permission.READ_MEDIA_VIDEO,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            arrayOf(
                android.Manifest.permission.READ_MEDIA_VIDEO,
            )
        else ->
            arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
            )
    }

private fun shareVideo(
    context: Context,
    videoPath: String,
) {
    val videoFile = File(videoPath)
    if (!videoFile.exists()) {
        Toast.makeText(context, context.getString(R.string.video_file_not_found), Toast.LENGTH_SHORT).show()
        return
    }

    val contentUri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile,
        )

    val shareIntent =
        Intent(Intent.ACTION_SEND).apply {
            type = "video/*"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    val chooserTitle = context.getString(R.string.share_video)
    context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        android.Manifest.permission.CAMERA,
    ) == PackageManager.PERMISSION_GRANTED

private fun launchVideoCapture(
    context: Context,
    onUriCreated: (Uri) -> Unit,
) {
    val videoFile = File(context.cacheDir, "recorded_video_${System.currentTimeMillis()}.mp4")
    val uri =
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile,
        )
    onUriCreated(uri)
}

private const val HLS_PERCENT_DIVISOR = 100f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HlsControlsRow(
    selectedCodec: Codec,
    isRunning: Boolean,
    onSelectCodec: (Codec) -> Unit,
    onPrepareHls: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (!isRunning) expanded = !expanded },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selectedCodec.displayName,
                onValueChange = {},
                readOnly = true,
                enabled = !isRunning,
                label = { Text(stringResource(R.string.hls_codec_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                Codec.entries.forEach { codec ->
                    DropdownMenuItem(
                        text = { Text(codec.displayName) },
                        onClick = {
                            onSelectCodec(codec)
                            expanded = false
                        },
                    )
                }
            }
        }

        Button(
            onClick = onPrepareHls,
            enabled = !isRunning,
            modifier = Modifier.weight(1f),
        ) {
            Text(stringResource(R.string.hls_prepare_button))
        }
    }
}

@Composable
private fun HlsTestStatusCard(
    state: HlsTestState,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onPlay: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.hls_preparation_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    onClick = onClose,
                    enabled = !state.isRunning,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.hls_close_description),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            state.renditions.forEach { row ->
                HlsRenditionRow(row)
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                state.isRunning ->
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hls_cancel_button))
                    }
                state.terminal is HlsTerminal.Succeeded -> {
                    Button(
                        onClick = { onPlay(state.terminal.masterPlaylistPath) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hls_play_button))
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.terminal.masterPlaylistPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.terminal is HlsTerminal.Failed ->
                    Text(
                        text = state.terminal.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                state.terminal is HlsTerminal.Cancelled ->
                    Text(
                        text = stringResource(R.string.hls_cancelled_status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else -> Unit
            }
        }
    }
}

@Composable
private fun HlsRenditionRow(row: HlsRenditionState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(56.dp),
        )

        Box(
            modifier = Modifier.width(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (row.status) {
                HlsRenditionStatus.Pending ->
                    Text(text = "—", style = MaterialTheme.typography.bodyMedium)
                HlsRenditionStatus.Active ->
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp,
                    )
                HlsRenditionStatus.Complete ->
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.hls_complete_description),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                HlsRenditionStatus.Failed ->
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.hls_failed_description),
                        tint = MaterialTheme.colorScheme.error,
                    )
            }
        }

        if (row.status == HlsRenditionStatus.Active || row.status == HlsRenditionStatus.Complete) {
            LinearProgressIndicator(
                progress = { row.progressPercent / HLS_PERCENT_DIVISOR },
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        Text(
            text = "${row.segmentCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
