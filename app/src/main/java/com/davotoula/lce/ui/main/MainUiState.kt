package com.davotoula.lce.ui.main

import android.net.Uri
import com.davotoula.lce.VideoDetailsModel
import com.davotoula.lightcompressor.Resolution

enum class Codec(
    val displayName: String,
) {
    H264("H.264"),
    H265("H.265 (HEVC)"),
}

enum class HlsRenditionStatus { Pending, Active, Complete, Failed }

data class HlsRenditionState(
    val label: String,
    val status: HlsRenditionStatus,
    val progressPercent: Int = 0,
    val segmentCount: Int = 0,
)

sealed interface HlsTerminal {
    data class Succeeded(
        val masterPlaylistPath: String,
    ) : HlsTerminal

    data class Failed(
        val message: String,
    ) : HlsTerminal

    data object Cancelled : HlsTerminal
}

data class HlsTestState(
    val isRunning: Boolean = false,
    val renditions: List<HlsRenditionState> = emptyList(),
    val terminal: HlsTerminal? = null,
)

data class MainUiState(
    val videos: List<VideoDetailsModel> = emptyList(),
    val selectedResolution: Resolution = Resolution.HD_720,
    val customResolution: Int? = null,
    val customResolutionInput: String = "",
    val selectedCodec: Codec = Codec.H264,
    val isStreamableEnabled: Boolean = true,
    val bitrateKbps: Int = 1500,
    val bitrateInput: String = "1500",
    val isCompressing: Boolean = false,
    val pendingUris: List<Uri> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val isSettingsExpanded: Boolean = true,
    val hlsCodec: Codec = Codec.H264,
    val hlsTestState: HlsTestState? = null,
)

sealed class MainAction {
    data class SelectVideos(
        val uris: List<Uri>,
    ) : MainAction()

    data class SetResolution(
        val resolution: Resolution,
    ) : MainAction()

    data class SetCustomResolution(
        val pixels: Int,
    ) : MainAction()

    data class SetCustomResolutionInput(
        val value: String,
    ) : MainAction()

    data class SetCodec(
        val codec: Codec,
    ) : MainAction()

    data class SetStreamable(
        val enabled: Boolean,
    ) : MainAction()

    data class SetBitrate(
        val kbps: Int,
    ) : MainAction()

    data class SetBitrateInput(
        val value: String,
    ) : MainAction()

    object CalculateAutoBitrate : MainAction()

    object StartCompression : MainAction()

    object CancelCompression : MainAction()

    data class PlayVideo(
        val path: String,
    ) : MainAction()

    object ClearToast : MainAction()

    data object ToggleSettings : MainAction()

    data class SetHlsCodec(
        val codec: Codec,
    ) : MainAction()

    data object PickHlsVideo : MainAction()

    data class StartHlsPreparation(
        val uri: Uri,
    ) : MainAction()

    data object CancelHlsPreparation : MainAction()

    data object CloseHlsTestState : MainAction()
}

sealed class MainEvent {
    data class NavigateToPlayer(
        val videoPath: String,
    ) : MainEvent()

    data object LaunchHlsPicker : MainEvent()
}
