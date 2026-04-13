package com.davotoula.lce.ui.hls

import android.net.Uri
import com.davotoula.lce.ui.main.Codec

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

data class HlsUiState(
    val hlsCodec: Codec = Codec.H264,
    val testState: HlsTestState? = null,
)

sealed class HlsAction {
    data class SetCodec(
        val codec: Codec,
    ) : HlsAction()

    data object PickVideo : HlsAction()

    data class StartPreparation(
        val uri: Uri,
    ) : HlsAction()

    data object CancelPreparation : HlsAction()

    data object CloseTestState : HlsAction()
}

sealed class HlsEvent {
    data object LaunchPicker : HlsEvent()
}
