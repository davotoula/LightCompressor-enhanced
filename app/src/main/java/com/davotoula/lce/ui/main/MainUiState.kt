package com.davotoula.lce.ui.main

import android.net.Uri
import com.davotoula.lce.VideoDetailsModel

enum class Resolution(val pixels: Int, val label: String) {
    UHD_4K(3840, "4K"),
    FHD_1080(1920, "1080p"),
    HD_720(1280, "720p"),
    SD_540(960, "540p")
}

enum class Codec(val displayName: String) {
    H264("H.264"),
    H265("H.265 (HEVC)")
}

data class MainUiState(
    val videos: List<VideoDetailsModel> = emptyList(),
    val selectedResolution: Resolution = Resolution.HD_720,
    val customResolution: Int? = null,
    val selectedCodec: Codec = Codec.H264,
    val isStreamableEnabled: Boolean = true,
    val bitrateKbps: Int = 1500,
    val isCompressing: Boolean = false,
    val pendingUris: List<Uri> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null
)

sealed class MainAction {
    data class SelectVideos(val uris: List<Uri>) : MainAction()
    data class SetResolution(val resolution: Resolution) : MainAction()
    data class SetCustomResolution(val pixels: Int) : MainAction()
    data class SetCodec(val codec: Codec) : MainAction()
    data class SetStreamable(val enabled: Boolean) : MainAction()
    data class SetBitrate(val kbps: Int) : MainAction()
    object CalculateAutoBitrate : MainAction()
    object StartCompression : MainAction()
    object CancelCompression : MainAction()
    data class PlayVideo(val path: String) : MainAction()
    object ClearToast : MainAction()
}

sealed class MainEvent {
    data class NavigateToPlayer(val videoPath: String) : MainEvent()
}
