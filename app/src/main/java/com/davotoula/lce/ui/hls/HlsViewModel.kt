package com.davotoula.lce.ui.hls

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.davotoula.lce.AnalyticsTracker
import com.davotoula.lce.R
import com.davotoula.lce.data.VideoSettingsPreferences
import com.davotoula.lce.ui.Codec
import com.davotoula.lightcompressor.HlsPreparer
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.hls.HlsConfig
import com.davotoula.lightcompressor.hls.HlsLadder
import com.davotoula.lightcompressor.utils.CompressorUtils
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class HlsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(HlsUiState())
    val uiState: StateFlow<HlsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HlsEvent>()
    val events: SharedFlow<HlsEvent> = _events.asSharedFlow()

    private val _toastMessages = MutableSharedFlow<String>()
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    private val context get() = getApplication<Application>()
    private val videoSettingsPreferences = VideoSettingsPreferences(application)

    init {
        viewModelScope.launch {
            val settings = videoSettingsPreferences.settings.first()
            _uiState.update { it.copy(hlsCodec = settings.hlsCodec) }
        }
    }

    fun onAction(action: HlsAction) {
        when (action) {
            is HlsAction.SetCodec -> handleSetCodec(action.codec)
            is HlsAction.SetSingleFilePerRendition -> handleSetSingleFilePerRendition(action.enabled)
            HlsAction.PickVideo -> handlePickVideo()
            is HlsAction.StartPreparation -> handleStartPreparation(action.uri)
            HlsAction.CancelPreparation -> handleCancelPreparation()
            HlsAction.CloseTestState -> handleCloseTestState()
        }
    }

    private fun handleSetSingleFilePerRendition(enabled: Boolean) {
        if (_uiState.value.testState?.isRunning == true) return
        _uiState.update { it.copy(singleFilePerRendition = enabled) }
    }

    private fun handleSetCodec(codec: Codec) {
        val effectiveCodec =
            if (codec == Codec.H265 && !CompressorUtils.isHevcEncodingSupported()) {
                viewModelScope.launch {
                    _toastMessages.emit(context.getString(R.string.h265_not_supported_fallback))
                }
                Codec.H264
            } else {
                codec
            }
        _uiState.update { it.copy(hlsCodec = effectiveCodec) }
        viewModelScope.launch {
            videoSettingsPreferences.saveHlsCodec(effectiveCodec)
        }
    }

    private fun handlePickVideo() {
        if (_uiState.value.testState?.isRunning == true) return
        viewModelScope.launch {
            _events.emit(HlsEvent.LaunchPicker)
        }
    }

    private fun handleStartPreparation(uri: Uri) {
        val state = _uiState.value
        if (state.testState?.isRunning == true) return

        val rootDir = File(context.filesDir, "hls/current")
        rootDir.deleteRecursively()
        if (!rootDir.mkdirs()) {
            viewModelScope.launch {
                _toastMessages.emit(context.getString(R.string.hls_output_dir_failed))
            }
            return
        }

        val ladder = HlsLadder.default()
        val seededRows =
            ladder.renditions.map { rendition ->
                HlsRenditionState(
                    label = rendition.resolution.label,
                    status = HlsRenditionStatus.Pending,
                )
            }
        _uiState.update {
            it.copy(
                testState =
                    HlsTestState(
                        isRunning = true,
                        renditions = seededRows,
                        terminal = null,
                    ),
            )
        }

        val videoCodec =
            when (state.hlsCodec) {
                Codec.H264 -> VideoCodec.H264
                Codec.H265 -> if (CompressorUtils.isHevcEncodingSupported()) VideoCodec.H265 else VideoCodec.H264
            }
        val config =
            HlsConfig(
                ladder = ladder,
                codec = videoCodec,
                singleFilePerRendition = state.singleFilePerRendition,
            )

        val session =
            HlsTestSession(
                rootDir = rootDir,
                updateState = { transform ->
                    _uiState.update { current ->
                        current.copy(testState = transform(current.testState))
                    }
                },
                onIoFailure = { HlsPreparer.cancel() },
                onTerminal = { status ->
                    AnalyticsTracker.logHlsPreparationResult(
                        status = status,
                        codec = videoCodec.name,
                    )
                },
            )

        AnalyticsTracker.logHlsPreparationStarted(codec = videoCodec.name)

        HlsPreparer.start(
            context = context,
            uri = uri,
            config = config,
            listener = session,
        )
    }

    private fun handleCancelPreparation() {
        HlsPreparer.cancel()
    }

    private fun handleCloseTestState() {
        if (_uiState.value.testState?.isRunning == true) return
        _uiState.update { it.copy(testState = null) }
    }

    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.testState?.isRunning == true) {
            HlsPreparer.cancel()
        }
    }
}
