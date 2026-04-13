package com.davotoula.lce.ui.hls

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davotoula.lce.R
import com.davotoula.lce.ui.Codec
import kotlinx.coroutines.flow.collectLatest

private const val HLS_PERCENT_DIVISOR = 100f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HlsScreen(
    viewModel: HlsViewModel,
    onBack: () -> Unit,
    onNavigateToPlayer: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val hlsVideoPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) {
                viewModel.onAction(HlsAction.StartPreparation(uri))
            }
        }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                HlsEvent.LaunchPicker ->
                    hlsVideoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.toastMessages.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hls_preparation_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.hls_back_description),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HlsControlsRow(
                selectedCodec = uiState.hlsCodec,
                isRunning = uiState.testState?.isRunning == true,
                onSelectCodec = { codec -> viewModel.onAction(HlsAction.SetCodec(codec)) },
                onPrepareHls = { viewModel.onAction(HlsAction.PickVideo) },
            )

            uiState.testState?.let { testState ->
                HlsTestStatusCard(
                    state = testState,
                    onCancel = { viewModel.onAction(HlsAction.CancelPreparation) },
                    onClose = { viewModel.onAction(HlsAction.CloseTestState) },
                    onPlay = { path -> onNavigateToPlayer(path) },
                )
            }
        }
    }
}

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
                    Text(
                        text = stringResource(R.string.hls_rendition_pending),
                        style = MaterialTheme.typography.bodyMedium,
                    )
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
