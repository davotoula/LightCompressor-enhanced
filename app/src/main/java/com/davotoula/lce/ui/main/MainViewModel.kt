package com.davotoula.lce.ui.main

import android.app.Application
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCodec
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import com.abedelazizshe.lightcompressorlibrary.config.SaveLocation
import com.abedelazizshe.lightcompressorlibrary.config.SharedStorageConfiguration
import com.abedelazizshe.lightcompressorlibrary.config.VideoResizer
import com.davotoula.lce.AnalyticsTracker
import com.davotoula.lce.R
import com.davotoula.lce.VideoDetailsModel
import com.davotoula.lce.data.VideoSettingsPreferences
import com.davotoula.lce.getFileSize
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import androidx.core.net.toUri

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<MainEvent>()
    val events: SharedFlow<MainEvent> = _events.asSharedFlow()

    private val context get() = getApplication<Application>()
    private val originalVideoSizes = mutableMapOf<Int, Long>()
    private val videoSettingsPreferences = VideoSettingsPreferences(application)

    init {
        loadSavedSettings()
    }

    private fun loadSavedSettings() {
        viewModelScope.launch {
            val settings = videoSettingsPreferences.settings.first()
            _uiState.update { state ->
                state.copy(
                    selectedResolution = settings.resolution,
                    selectedCodec = settings.codec,
                    isStreamableEnabled = settings.isStreamableEnabled,
                    bitrateKbps = settings.bitrateKbps ?: state.bitrateKbps,
                    bitrateInput = settings.bitrateKbps?.toString() ?: state.bitrateInput
                )
            }
            if (settings.bitrateKbps == null) {
                calculateAutoBitrate()
            }
        }
    }

    private fun saveSettings() {
        viewModelScope.launch {
            val state = _uiState.value
            videoSettingsPreferences.saveSettings(
                resolution = state.selectedResolution,
                codec = state.selectedCodec,
                isStreamableEnabled = state.isStreamableEnabled,
                bitrateKbps = state.bitrateKbps
            )
        }
    }

    fun onAction(action: MainAction) {
        when (action) {
            is MainAction.SelectVideos -> handleSelectVideos(action.uris)
            is MainAction.SetResolution -> handleSetResolution(action.resolution)
            is MainAction.SetCustomResolution -> handleSetCustomResolution(action.pixels)
            is MainAction.SetCustomResolutionInput -> handleSetCustomResolutionInput(action.value)
            is MainAction.SetCodec -> handleSetCodec(action.codec)
            is MainAction.SetStreamable -> handleSetStreamable(action.enabled)
            is MainAction.SetBitrate -> handleSetBitrate(action.kbps)
            is MainAction.SetBitrateInput -> handleSetBitrateInput(action.value)
            MainAction.CalculateAutoBitrate -> calculateAutoBitrate()
            MainAction.StartCompression -> startCompression()
            MainAction.CancelCompression -> cancelCompression()
            is MainAction.PlayVideo -> handlePlayVideo(action.path)
            MainAction.ClearToast -> clearToast()
            MainAction.ToggleSettings -> handleToggleSettings()
        }
    }

    private fun handleToggleSettings() {
        _uiState.update { it.copy(isSettingsExpanded = !it.isSettingsExpanded) }
    }

    private fun handleSelectVideos(uris: List<Uri>) {
        if (uris.isEmpty()) return

        AnalyticsTracker.logVideoSelection(
            source = "picker",
            count = uris.size
        )

        originalVideoSizes.clear()
        _uiState.update { state ->
            state.copy(
                pendingUris = uris,
                videos = uris.map { uri ->
                    VideoDetailsModel(
                        playableVideoPath = null,
                        uri = uri,
                        newSize = "Pending...",
                        progress = 0f
                    )
                },
                errorMessage = null,
                isSettingsExpanded = false
            )
        }

        // Auto-calculate bitrate when videos are selected
        calculateAutoBitrate()
    }

    private fun handleSetResolution(resolution: Resolution) {
        _uiState.update { state ->
            state.copy(
                selectedResolution = resolution,
                customResolution = null,
                customResolutionInput = ""
            )
        }
        calculateAutoBitrate()
        saveSettings()
    }

    private fun handleSetCustomResolution(pixels: Int) {
        _uiState.update { state ->
            state.copy(
                customResolution = pixels,
                customResolutionInput = pixels.toString()
            )
        }
        calculateAutoBitrate()
    }

    private fun handleSetCustomResolutionInput(value: String) {
        val pixels = parsePositiveInt(value)
        _uiState.update { state ->
            state.copy(
                customResolutionInput = value,
                customResolution = pixels
            )
        }
        if (pixels != null) {
            calculateAutoBitrate()
        }
    }

    private fun handleSetCodec(codec: Codec) {
        val effectiveCodec = if (codec == Codec.H265 && !isH265Supported()) {
            showToast("H.265 not supported on this device, falling back to H.264")
            Codec.H264
        } else {
            codec
        }

        _uiState.update { state ->
            state.copy(selectedCodec = effectiveCodec)
        }
        calculateAutoBitrate()
        saveSettings()
    }

    private fun handleSetStreamable(enabled: Boolean) {
        _uiState.update { state ->
            state.copy(isStreamableEnabled = enabled)
        }
        saveSettings()
    }

    private fun handleSetBitrate(kbps: Int) {
        _uiState.update { state ->
            val clamped = kbps.coerceIn(100, 50000)
            state.copy(
                bitrateKbps = clamped,
                bitrateInput = clamped.toString()
            )
        }
        saveSettings()
    }

    private fun handleSetBitrateInput(value: String) {
        val kbps = parsePositiveInt(value)
        _uiState.update { state ->
            state.copy(
                bitrateInput = value,
                bitrateKbps = kbps?.coerceIn(100, 50000) ?: state.bitrateKbps
            )
        }
    }

    private fun calculateAutoBitrate() {
        val state = _uiState.value
        val resolutionPixels = state.customResolution ?: state.selectedResolution.pixels

        // Calculate recommended bitrate based on resolution and codec
        // H.265 typically achieves same quality at ~40% lower bitrate
        val baseBitrate = when {
            resolutionPixels >= 3840 -> 8000  // 4K: 8 Mbps base
            resolutionPixels >= 1920 -> 4000  // 1080p: 4 Mbps base
            resolutionPixels >= 1280 -> 2000  // 720p: 2 Mbps base
            resolutionPixels >= 960 -> 1500   // 540p: 1.5 Mbps base
            else -> 1000                       // Lower: 1 Mbps base
        }

        val codecMultiplier = if (state.selectedCodec == Codec.H265) 0.6 else 1.0
        val recommendedBitrate = (baseBitrate * codecMultiplier).toInt()

        _uiState.update {
            it.copy(
                bitrateKbps = recommendedBitrate,
                bitrateInput = recommendedBitrate.toString()
            )
        }
    }

    private fun parsePositiveInt(value: String): Int? {
        return value.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun startCompression() {
        val state = _uiState.value

        if (state.pendingUris.isEmpty()) {
            showToast("No videos selected")
            return
        }

        if (state.isCompressing) {
            showToast("Compression already in progress")
            return
        }

        val resolutionPixels = (state.customResolution ?: state.selectedResolution.pixels).toDouble()
        val videoCodec = when (state.selectedCodec) {
            Codec.H264 -> VideoCodec.H264
            Codec.H265 -> if (isH265Supported()) VideoCodec.H265 else VideoCodec.H264
        }

        val videoNames = state.pendingUris.mapIndexed { index, _ ->
            "compressed_${System.currentTimeMillis()}_$index"
        }

        AnalyticsTracker.logCompressionStarted(
            bitrateKbps = state.bitrateKbps.toLong(),
            maxResolution = resolutionPixels.toInt(),
            codec = videoCodec.name,
            streamable = state.isStreamableEnabled,
            videoCount = state.pendingUris.size,
            source = "main_screen"
        )

        _uiState.update { it.copy(isCompressing = true, errorMessage = null) }

        val configuration = Configuration.withBitrateInBps(
            isMinBitrateCheckEnabled = false,
            videoBitrateInBps = state.bitrateKbps.toLong() * 1000L,
            resizer = VideoResizer.limitSize(resolutionPixels),
            videoNames = videoNames,
            videoCodec = videoCodec
        )

        VideoCompressor.start(
            context = context,
            uris = state.pendingUris,
            isStreamable = state.isStreamableEnabled,
            storageConfiguration = SharedStorageConfiguration(
                saveAt = SaveLocation.MOVIES,
                subFolderName = "lce-compressed"
            ),
            configureWith = configuration,
            listener = object : CompressionListener {
                override fun onStart(index: Int) {
                    viewModelScope.launch {
                        val originalSize = getOriginalVideoSize(state.pendingUris[index])
                        originalVideoSizes[index] = originalSize
                        Log.i(
                            "MainViewModel",
                            "Original video size for index $index: ${getFileSize(originalSize)}"
                        )
                        updateVideoProgress(index, 0f, "Compressing...")
                    }
                }

                override fun onSuccess(index: Int, size: Long, path: String?) {
                    viewModelScope.launch {
                        val originalSize = originalVideoSizes[index]
                            ?: getOriginalVideoSize(state.pendingUris[index])

                        if (originalSize in 1..<size) {
                            Log.w(
                                "MainViewModel",
                                "Compressed video ($size bytes) is larger than original ($originalSize bytes). Not saving."
                            )
                            path?.let { deleteCompressedFile(it) }
                            showToast(
                                context.getString(
                                    R.string.video_not_saved_larger_toast,
                                    getFileSize(size),
                                    getFileSize(originalSize)
                                )
                            )
                            updateVideoNotSaved(index)
                        } else {
                            val sizeString = getFileSize(size)
                            updateVideoComplete(index, path, sizeString)
                        }
                        checkCompressionComplete()

                        AnalyticsTracker.logCompressionResult(
                            status = "success",
                            codec = videoCodec.name,
                            source = "main_screen",
                            videoCount = state.pendingUris.size
                        )
                    }
                }

                override fun onFailure(index: Int, failureMessage: String) {
                    viewModelScope.launch {
                        updateVideoError(index, failureMessage)
                        checkCompressionComplete()

                        AnalyticsTracker.recordCompressionFailure(
                            message = failureMessage,
                            codec = videoCodec.name,
                            bitrateKbps = state.bitrateKbps.toLong(),
                            streamable = state.isStreamableEnabled,
                            source = "main_screen"
                        )

                        AnalyticsTracker.logCompressionResult(
                            status = "failure",
                            codec = videoCodec.name,
                            source = "main_screen",
                            videoCount = state.pendingUris.size
                        )
                    }
                }

                override fun onProgress(index: Int, percent: Float) {
                    viewModelScope.launch {
                        updateVideoProgress(index, percent / 100f, "Compressing... ${percent.toInt()}%")
                    }
                }

                override fun onCancelled(index: Int) {
                    viewModelScope.launch {
                        updateVideoError(index, "Cancelled")
                        _uiState.update { it.copy(isCompressing = false) }

                        AnalyticsTracker.logCompressionCancelled(
                            source = "main_screen",
                            videoCount = state.pendingUris.size
                        )
                    }
                }
            }
        )
    }

    private fun cancelCompression() {
        VideoCompressor.cancel()
        _uiState.update { state ->
            state.copy(
                isCompressing = false,
                toastMessage = "Compression cancelled"
            )
        }
    }

    private fun handlePlayVideo(path: String) {
        viewModelScope.launch {
            AnalyticsTracker.logVideoPlayback(path)
            _events.emit(MainEvent.NavigateToPlayer(path))
        }
    }

    private fun clearToast() {
        _uiState.update { it.copy(toastMessage = null) }
    }

    private fun showToast(message: String) {
        _uiState.update { it.copy(toastMessage = message) }
    }

    private fun updateVideoProgress(index: Int, progress: Float, status: String) {
        _uiState.update { state ->
            val updatedVideos = state.videos.toMutableList()
            if (index < updatedVideos.size) {
                updatedVideos[index] = updatedVideos[index].copy(
                    progress = progress,
                    newSize = status
                )
            }
            state.copy(videos = updatedVideos)
        }
    }

    private fun updateVideoComplete(index: Int, path: String?, sizeString: String) {
        _uiState.update { state ->
            val updatedVideos = state.videos.toMutableList()
            if (index < updatedVideos.size) {
                updatedVideos[index] = updatedVideos[index].copy(
                    playableVideoPath = path,
                    progress = 1f,
                    newSize = sizeString
                )
            }
            state.copy(videos = updatedVideos)
        }
    }

    private fun updateVideoNotSaved(index: Int) {
        val status = context.getString(R.string.video_not_saved_status)
        _uiState.update { state ->
            val updatedVideos = state.videos.toMutableList()
            if (index < updatedVideos.size) {
                updatedVideos[index] = updatedVideos[index].copy(
                    playableVideoPath = null,
                    progress = 1f,
                    newSize = status
                )
            }
            state.copy(videos = updatedVideos)
        }
    }

    private fun updateVideoError(index: Int, errorMessage: String) {
        _uiState.update { state ->
            val updatedVideos = state.videos.toMutableList()
            if (index < updatedVideos.size) {
                updatedVideos[index] = updatedVideos[index].copy(
                    newSize = "Error: $errorMessage",
                    progress = 0f
                )
            }
            state.copy(videos = updatedVideos, errorMessage = errorMessage)
        }
    }

    private fun getOriginalVideoSize(uri: Uri): Long {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize
            } ?: 0L
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to get original video size for $uri", e)
            0L
        }
    }

    private fun deleteCompressedFile(path: String) {
        try {
            val file = java.io.File(path)
            if (file.exists() && file.delete()) {
                Log.i("MainViewModel", "Deleted compressed file: $path")
            } else {
                try {
                    val uri = path.toUri()
                    val deleted = context.contentResolver.delete(uri, null, null)
                    if (deleted > 0) {
                        Log.i("MainViewModel", "Deleted compressed file via ContentResolver: $path")
                    }
                } catch (e: Exception) {
                    Log.w("MainViewModel", "Could not delete compressed file: $path", e)
                }
            }
        } catch (e: Exception) {
            Log.e("MainViewModel", "Failed to delete compressed file: $path", e)
        }
    }

    private fun checkCompressionComplete() {
        val state = _uiState.value
        val allComplete = state.videos.all { video ->
            video.progress >= 1f || video.newSize.startsWith("Error:")
        }

        if (allComplete) {
            _uiState.update { it.copy(isCompressing = false) }
            val notSavedStatus = context.getString(R.string.video_not_saved_status)
            val hasOversizedOutput = state.videos.any { it.newSize == notSavedStatus }
            if (!hasOversizedOutput) {
                showToast("Compression complete")
            }
        }
    }

    private fun isH265Supported(): Boolean {
        return try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            codecList.codecInfos.any { codecInfo ->
                codecInfo.isEncoder && codecInfo.supportedTypes.any { type ->
                    type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                }
            }
        } catch (e: Exception) {
            Log.d("MainViewModel", "Error checking H.265 support: ${e.message}")
            false
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Cancel any ongoing compression when ViewModel is cleared
        if (_uiState.value.isCompressing) {
            VideoCompressor.cancel()
        }
    }
}
