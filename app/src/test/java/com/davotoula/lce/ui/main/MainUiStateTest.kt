package com.davotoula.lce.ui.main

import com.davotoula.lce.ui.Codec
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
        assertEquals(HdrMode.KEEP_IF_SUPPORTED, state.selectedHdrMode)
    }

    @Test
    fun h265_usesSelectedHdrMode() {
        HdrMode.entries.forEach { mode ->
            val state = MainUiState(selectedCodec = Codec.H265, selectedHdrMode = mode)
            assertTrue(state.isKeepHdrAvailable)
            assertEquals(mode, state.effectiveHdrMode)
        }
    }
}
