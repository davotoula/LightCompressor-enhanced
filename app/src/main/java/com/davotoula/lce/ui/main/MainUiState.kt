package com.davotoula.lce.ui.main

import android.net.Uri
import com.davotoula.lce.VideoDetailsModel
import com.davotoula.lce.data.VideoSettings
import com.davotoula.lce.ui.Codec
import com.davotoula.lightcompressor.Resolution
import com.davotoula.lightcompressor.config.HdrMode
import com.davotoula.lightcompressor.hdr.HdrInput
import com.davotoula.lightcompressor.hdr.HdrProbe

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
    // One per pending video once VideoCompressor.probeHdr has run on all of them; null until then.
    val hdrProbes: List<HdrProbe>? = null,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val isSettingsExpanded: Boolean = true,
    // False until the saved settings load, so defaults (H.264) don't flash the H.264 HDR hint.
    val isSettingsLoaded: Boolean = false,
) {
    /**
     * Why Keep HDR can't apply, or null when it can. Keeping HDR needs H.265 (the library always tone
     * maps H.264 output, see [HdrMode]), and once the picked videos are probed, at least one of them
     * must be HDR that can be kept on this device.
     */
    val keepHdrBlocker: KeepHdrBlocker?
        get() =
            when {
                selectedCodec != Codec.H265 -> KeepHdrBlocker.H264
                hdrProbes == null -> null
                hdrProbes.all { it.input == HdrInput.SDR } -> KeepHdrBlocker.NO_HDR_VIDEO
                hdrProbes.none { it.canKeepHdr } -> KeepHdrBlocker.CANT_KEEP
                else -> null
            }

    val isKeepHdrAvailable: Boolean get() = keepHdrBlocker == null

    /**
     * The HDR mode a compression actually gets, shown as selected in the settings. [selectedHdrMode]
     * keeps the user's choice, so Keep HDR comes back when H.265 is selected again.
     */
    val effectiveHdrMode: HdrMode
        get() = if (isKeepHdrAvailable) selectedHdrMode else HdrMode.TONE_MAP_TO_SDR
}

enum class KeepHdrBlocker {
    H264,
    NO_HDR_VIDEO,
    CANT_KEEP,
}

/** [codec], or H.264 when it is H.265 and the device can't encode HEVC. */
internal fun supportedCodec(
    codec: Codec,
    isHevcSupported: Boolean,
): Codec = if (codec == Codec.H265 && !isHevcSupported) Codec.H264 else codec

/** This state with the saved [settings] applied. */
internal fun MainUiState.withSavedSettings(
    settings: VideoSettings,
    isHevcSupported: Boolean,
): MainUiState =
    copy(
        selectedResolution = settings.resolution,
        customResolutionInput = settings.resolution.shortSide.toString(),
        // Settings restored from another device may name a codec this one can't encode.
        selectedCodec = supportedCodec(settings.codec, isHevcSupported),
        isStreamableEnabled = settings.isStreamableEnabled,
        bitrateKbps = settings.bitrateKbps ?: bitrateKbps,
        bitrateInput = settings.bitrateKbps?.toString() ?: bitrateInput,
        selectedHdrMode = settings.hdrMode,
        isSettingsLoaded = true,
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
