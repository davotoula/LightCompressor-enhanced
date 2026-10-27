package com.davotoula.lce.ui.main

import com.davotoula.lce.data.VideoSettings
import com.davotoula.lce.ui.Codec
import com.davotoula.lightcompressor.Resolution
import com.davotoula.lightcompressor.config.HdrMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateTest {
    @Test
    fun h264_disablesKeepHdrAndTonemaps() {
        val state = MainUiState(selectedCodec = Codec.H264, selectedHdrMode = HdrMode.KEEP_IF_SUPPORTED)
        assertFalse(state.isKeepHdrAvailable)
        assertEquals(HdrMode.TONE_MAP_TO_SDR, state.effectiveHdrMode)
    }

    @Test
    fun h265_usesSelectedHdrMode() {
        HdrMode.entries.forEach { mode ->
            val state = MainUiState(selectedCodec = Codec.H265, selectedHdrMode = mode)
            assertTrue(state.isKeepHdrAvailable)
            assertEquals(mode, state.effectiveHdrMode)
        }
    }

    @Test
    fun savedH265_withoutHevcEncoder_loadsAsH264() {
        val state = MainUiState().withSavedSettings(VideoSettings(codec = Codec.H265), isHevcSupported = false)
        assertEquals(Codec.H264, state.selectedCodec)
        assertFalse(state.isKeepHdrAvailable)
    }

    @Test
    fun savedH265_withHevcEncoder_loadsAsH265() {
        val state = MainUiState().withSavedSettings(VideoSettings(codec = Codec.H265), isHevcSupported = true)
        assertEquals(Codec.H265, state.selectedCodec)
        assertEquals(HdrMode.KEEP_IF_SUPPORTED, state.effectiveHdrMode)
    }

    @Test
    fun savedSettings_markLoadedAndApplyValues() {
        val saved =
            VideoSettings(
                resolution = Resolution.FHD_1080,
                isStreamableEnabled = false,
                bitrateKbps = 4000,
                hdrMode = HdrMode.TONE_MAP_TO_SDR,
            )
        assertFalse(MainUiState().isSettingsLoaded)
        val state = MainUiState().withSavedSettings(saved, isHevcSupported = true)
        assertTrue(state.isSettingsLoaded)
        assertEquals(Resolution.FHD_1080, state.selectedResolution)
        assertFalse(state.isStreamableEnabled)
        assertEquals(4000, state.bitrateKbps)
        assertEquals("4000", state.bitrateInput)
        assertEquals(HdrMode.TONE_MAP_TO_SDR, state.selectedHdrMode)
    }

    @Test
    fun noSavedBitrate_keepsCurrentBitrate() {
        val state =
            MainUiState(bitrateKbps = 1500, bitrateInput = "1500")
                .withSavedSettings(VideoSettings(bitrateKbps = null), isHevcSupported = true)
        assertEquals(1500, state.bitrateKbps)
        assertEquals("1500", state.bitrateInput)
    }
}
