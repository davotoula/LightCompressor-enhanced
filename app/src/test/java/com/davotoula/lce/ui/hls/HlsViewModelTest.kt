package com.davotoula.lce.ui.hls

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.core.app.ApplicationProvider
import com.davotoula.lce.ui.Codec
import com.davotoula.lightcompressor.utils.CompressorUtils
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for HlsViewModel.
 *
 * Note: Tests that require mocking HlsPreparer.start() are skipped because MockK's
 * auto-hint mechanism invokes the real method during recording, which fails under
 * Robolectric's Context proxy. The tested scenarios focus on:
 * - Codec fallback logic (via CompressorUtils mock)
 * - UI state transitions
 * - Event emissions
 * - Race guard behavior (via state inspection)
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [34])
class HlsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val testScope = TestScope(dispatcher)
    private lateinit var application: Application
    private lateinit var store: ViewModelStore

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        application = ApplicationProvider.getApplicationContext()
        store = ViewModelStore()
        clearHlsDir()
        // Clear the cached HEVC support value to ensure mock takes effect
        clearHevcCache()
        mockkObject(CompressorUtils)
        every { CompressorUtils.isHevcEncodingSupported() } returns true
    }

    private fun clearHevcCache() {
        try {
            val field = CompressorUtils::class.java.getDeclaredField("hevcSupportCache")
            field.isAccessible = true
            field.set(CompressorUtils, null)
        } catch (_: Exception) {
            // Ignore if field doesn't exist
        }
    }

    @After
    fun tearDown() {
        unmockkObject(CompressorUtils)
        Dispatchers.resetMain()
        clearHlsDir()
    }

    private fun clearHlsDir() {
        File(application.filesDir, "hls").deleteRecursively()
    }

    private fun TestScope.createViewModel(): HlsViewModel {
        val factory =
            viewModelFactory {
                initializer { HlsViewModel(application) }
            }
        val extras = MutableCreationExtras()
        val vm =
            ViewModelProvider.create(
                store = store,
                factory = factory,
                extras = extras,
            )[HlsViewModel::class]
        advanceUntilIdle()
        return vm
    }

    @Test
    fun `setCodec falls back to H264 when HEVC not supported and emits toast`() =
        testScope.runTest {
            every { CompressorUtils.isHevcEncodingSupported() } returns false
            val vm = createViewModel()

            val toasts = mutableListOf<String>()
            val job = launch { vm.toastMessages.collect { toasts += it } }
            advanceUntilIdle()

            vm.onAction(HlsAction.SetCodec(Codec.H265))
            advanceUntilIdle()

            assertEquals(Codec.H264, vm.uiState.value.hlsCodec)
            assertEquals(1, toasts.size)
            job.cancel()
        }

    @Test
    fun `setCodec to H264 succeeds regardless of HEVC support`() =
        testScope.runTest {
            val vm = createViewModel()

            vm.onAction(HlsAction.SetCodec(Codec.H264))
            advanceUntilIdle()

            assertEquals(Codec.H264, vm.uiState.value.hlsCodec)
        }

    @Test
    fun `setSingleFilePerRendition toggles the ui flag while idle`() =
        testScope.runTest {
            val vm = createViewModel()
            assertEquals(false, vm.uiState.value.singleFilePerRendition)

            vm.onAction(HlsAction.SetSingleFilePerRendition(true))
            advanceUntilIdle()
            assertEquals(true, vm.uiState.value.singleFilePerRendition)

            vm.onAction(HlsAction.SetSingleFilePerRendition(false))
            advanceUntilIdle()
            assertEquals(false, vm.uiState.value.singleFilePerRendition)
        }

    @Test
    fun `setSingleFilePerRendition is ignored while a run is in progress`() =
        testScope.runTest {
            val vm = createViewModel()

            // Force a running test state via the same reflection trick the other tests use.
            val runningState =
                HlsTestState(
                    isRunning = true,
                    renditions = emptyList(),
                    terminal = null,
                )
            val field = HlsViewModel::class.java.getDeclaredField("_uiState")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val stateFlow =
                field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<HlsUiState>
            stateFlow.value =
                stateFlow.value.copy(
                    testState = runningState,
                    singleFilePerRendition = false,
                )
            advanceUntilIdle()

            vm.onAction(HlsAction.SetSingleFilePerRendition(true))
            advanceUntilIdle()

            assertEquals(false, vm.uiState.value.singleFilePerRendition)
        }

    @Test
    fun `pickVideo emits LaunchPicker when idle`() =
        testScope.runTest {
            val vm = createViewModel()

            val events = mutableListOf<HlsEvent>()
            val job = launch { vm.events.collect { events += it } }
            advanceUntilIdle()

            vm.onAction(HlsAction.PickVideo)
            advanceUntilIdle()

            assertEquals(listOf(HlsEvent.LaunchPicker), events)
            job.cancel()
        }

    @Test
    fun `closeTestState clears state when not running`() =
        testScope.runTest {
            val vm = createViewModel()

            // Manually set a non-running terminal state to test closeTestState
            // This simulates a completed run without needing HlsPreparer
            val terminalState =
                HlsTestState(
                    isRunning = false,
                    renditions = emptyList(),
                    terminal = HlsTerminal.Cancelled,
                )
            // Access internal state via reflection for test setup
            val field = HlsViewModel::class.java.getDeclaredField("_uiState")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val stateFlow =
                field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<HlsUiState>
            stateFlow.value = stateFlow.value.copy(testState = terminalState)
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.testState)

            vm.onAction(HlsAction.CloseTestState)
            advanceUntilIdle()

            assertNull(vm.uiState.value.testState)
        }

    @Test
    fun `closeTestState is no-op when running`() =
        testScope.runTest {
            val vm = createViewModel()

            // Manually set a running state
            val runningState =
                HlsTestState(
                    isRunning = true,
                    renditions = emptyList(),
                    terminal = null,
                )
            val field = HlsViewModel::class.java.getDeclaredField("_uiState")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val stateFlow =
                field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<HlsUiState>
            stateFlow.value = stateFlow.value.copy(testState = runningState)
            advanceUntilIdle()

            vm.onAction(HlsAction.CloseTestState)
            advanceUntilIdle()

            assertNotNull("closeTestState should be no-op while running", vm.uiState.value.testState)
            assertTrue(
                vm.uiState.value.testState!!
                    .isRunning,
            )
        }

    @Test
    fun `pickVideo is no-op while running`() =
        testScope.runTest {
            val vm = createViewModel()

            // Manually set a running state
            val runningState =
                HlsTestState(
                    isRunning = true,
                    renditions = emptyList(),
                    terminal = null,
                )
            val field = HlsViewModel::class.java.getDeclaredField("_uiState")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val stateFlow =
                field.get(vm) as kotlinx.coroutines.flow.MutableStateFlow<HlsUiState>
            stateFlow.value = stateFlow.value.copy(testState = runningState)
            advanceUntilIdle()

            val events = mutableListOf<HlsEvent>()
            val job = launch { vm.events.collect { events += it } }
            advanceUntilIdle()

            vm.onAction(HlsAction.PickVideo)
            advanceUntilIdle()

            assertEquals(emptyList<HlsEvent>(), events)
            job.cancel()
        }

    @Test
    fun `startPreparation fails when root dir cannot be created and surfaces toast`() =
        testScope.runTest {
            val vm = createViewModel()

            // Block mkdirs by making the parent 'hls' a regular file
            val hlsPath = File(application.filesDir, "hls")
            if (hlsPath.exists()) hlsPath.deleteRecursively()
            hlsPath.writeText("blocker")

            val toasts = mutableListOf<String>()
            val job = launch { vm.toastMessages.collect { toasts += it } }
            advanceUntilIdle()

            vm.onAction(HlsAction.StartPreparation(Uri.parse("content://test/1")))
            advanceUntilIdle()

            // Should surface a toast about directory creation failure
            assertEquals(1, toasts.size)
            // testState should not be set (preparation didn't start)
            assertNull(vm.uiState.value.testState)
            job.cancel()
        }
}
