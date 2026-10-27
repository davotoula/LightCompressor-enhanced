package com.davotoula.lce.ui.main

import android.net.Uri
import com.davotoula.lce.VideoDetailsModel
import com.davotoula.lce.ui.Codec
import com.davotoula.lightcompressor.Resolution
import com.davotoula.lightcompressor.config.HdrMode

data class MainUiState(
    val videos: List<VideoDetailsModel> = emptyList(),
    val selectedResolution: Resolution = Resolution.HD_720,
    val customResolution: Int? = null,
    val customResolutionInput: String = "",
    val selectedCodec: Codec = Codec.H264,
    val isStreamableEnabled: Boolean = true,
    val bitrateKbps: Int = 1500,
    val bitrateInput: String = "1500",
    val selectedHdrMode: HdrMode = HdrMode.KEEP_IF_SUPPORTED,
    val isCompressing: Boolean = false,
    val pendingUris: List<Uri> = emptyList(),
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val isSettingsExpanded: Boolean = true,
    // False until the saved settings load, so defaults (H.264) don't flash the H.264 HDR hint.
    val isSettingsLoaded: Boolean = false,
) {
    /** Keeping HDR needs H.265: the library always tone maps H.264 output (see [HdrMode]). */
    val isKeepHdrAvailable: Boolean get() = selectedCodec == Codec.H265

    /**
     * The HDR mode a compression actually gets, shown as selected in the settings. [selectedHdrMode]
     * keeps the user's choice, so Keep HDR comes back when H.265 is selected again.
     */
    val effectiveHdrMode: HdrMode
        get() = if (isKeepHdrAvailable) selectedHdrMode else HdrMode.TONE_MAP_TO_SDR
}

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

    data class SetHdrMode(
        val mode: HdrMode,
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
}

sealed class MainEvent {
    data class NavigateToPlayer(
        val videoPath: String,
    ) : MainEvent()
}
