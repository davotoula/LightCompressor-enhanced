package com.davotoula.lce.ui.main

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.davotoula.lce.data.VideoSettingsPreferences
import com.davotoula.lce.ui.Codec
import com.davotoula.lightcompressor.Resolution
import com.davotoula.lightcompressor.config.HdrMode
import com.davotoula.lightcompressor.utils.CompressorUtils
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class MainViewModelSettingsTest {
    private val dispatcher = StandardTestDispatcher()
    private lateinit var application: Application

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        mockkObject(CompressorUtils)
    }

    @After
    fun tearDown() {
        unmockkObject(CompressorUtils)
        Dispatchers.resetMain()
    }

    private suspend fun loadedState(
        savedCodec: Codec,
        hevcSupported: Boolean,
    ): MainUiState {
        every { CompressorUtils.isHevcEncodingSupported() } returns hevcSupported
        VideoSettingsPreferences(application).saveSettings(
            resolution = Resolution.HD_720,
            codec = savedCodec,
            isStreamableEnabled = true,
            bitrateKbps = 1500,
            hdrMode = HdrMode.KEEP_IF_SUPPORTED,
        )
        val vm = MainViewModel(application)
        assertFalse(vm.uiState.value.isSettingsLoaded)
        return vm.uiState.first { it.isSettingsLoaded }
    }

    @Test
    fun savedH265_withoutHevcEncoder_loadsAsH264() =
        runTest(dispatcher) {
            val state = loadedState(Codec.H265, hevcSupported = false)
            assertEquals(Codec.H264, state.selectedCodec)
            assertFalse(state.isKeepHdrAvailable)
        }

    @Test
    fun savedH265_withHevcEncoder_loadsAsH265() =
        runTest(dispatcher) {
            val state = loadedState(Codec.H265, hevcSupported = true)
            assertEquals(Codec.H265, state.selectedCodec)
            assertEquals(HdrMode.KEEP_IF_SUPPORTED, state.effectiveHdrMode)
        }
}
