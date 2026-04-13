# HLS Test Affordance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manual sample-app affordance that exercises the new `HlsPreparer` API end-to-end against a user-picked local video, then plays the resulting HLS bundle back via the existing Media3-based `PlayerScreen`.

**Architecture:** A new isolated path in the sample app, separate from the existing multi-video compression queue. Adds a "Prepare HLS" button + codec dropdown to `MainScreen`, a per-rendition status card, and an `HlsListener` implementation (`HlsTestSession`) that persists segments and playlists to `filesDir/hls/current/` while updating UI state. Playback reuses the existing `PlayerScreen` after adding the `media3-exoplayer-hls` source so ExoPlayer can detect HLS by extension.

**Tech Stack:** Kotlin, Jetpack Compose, Media3 ExoPlayer (with HLS source), DataStore preferences, JUnit 4. The library `HlsPreparer` API already exists.

**Spec:** `docs/specs/2026-04-12-hls-test-affordance-design.md`

---

## File Structure

### New files (app module)

| File | Responsibility |
|------|----------------|
| `app/src/main/java/com/davotoula/lce/hls/HlsTestSession.kt` | `HlsListener` impl that persists segments + playlists to a root dir and updates UI state via a `MutableStateFlow` |
| `app/src/test/java/com/davotoula/lce/hls/HlsTestSessionTest.kt` | Unit tests for `HlsTestSession` (file output, state mapping) |

### Modified files (app module)

| File | Change |
|------|--------|
| `gradle/libs.versions.toml` | Add `media3-exoplayer-hls` library entry to the `media3` bundle |
| `app/build.gradle` | (No edit needed — picks up the new library via the existing `libs.bundles.media3` import) |
| `app/src/main/java/com/davotoula/lce/data/VideoSettingsPreferences.kt` | Add a persisted `hlsCodec: Codec` field |
| `app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt` | Add `HlsTestState`, `HlsRenditionState`, `HlsRenditionStatus`, `HlsTerminal` types; add `hlsCodec` and `hlsTestState` to `MainUiState`; add new `MainAction`/`MainEvent` cases |
| `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt` | Load/save `hlsCodec`; add `handlePickHlsVideo`, `handleStartHlsPreparation`, `handleCancelHlsPreparation`, `handleCloseHlsTestState`, `handleSetHlsCodec` |
| `app/src/main/java/com/davotoula/lce/ui/main/MainScreen.kt` | Add the HLS codec dropdown + "Prepare HLS" button row, the status card, and the `LaunchHlsPicker` event handler |

### Files NOT touched

- `app/src/main/java/com/davotoula/lce/ui/player/PlayerScreen.kt` — ExoPlayer detects HLS by extension once `media3-exoplayer-hls` is on the classpath
- `app/src/main/java/com/davotoula/lce/navigation/LceNavigation.kt` — the existing `LceRoute.Player.createRoute(videoPath)` route accepts the master playlist path as-is
- The existing multi-video compression queue, `CollapsibleSettingsCard`, and the `CompressionListener` flow

---

## Conventions used in this plan

- All `cd` commands use the absolute repo path: `/Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls`
- Tests live next to existing tests under `app/src/test/java/com/davotoula/lce/...`
- The library exposes two `Codec` types — `com.davotoula.lce.ui.main.Codec` (the app's own enum used by `MainUiState`) and `com.davotoula.lightcompressor.VideoCodec` (the library API). This plan keeps them separate; conversion happens in `MainViewModel`.
- The library's `HlsLadder.default()` is sorted ascending by `Resolution.shortSide` and `HlsLadder.forSource()` keeps the lowest N renditions ≤ source short side. The status card seeds Pending rows from `HlsLadder.default()` and trims them in `onStart` based on the actual `renditionCount`.

---

## Task 1: Add `media3-exoplayer-hls` dependency

**Files:**
- Modify: `gradle/libs.versions.toml`

The HLS source is required so `ExoPlayer.Builder().build()` can play `master.m3u8` via the existing `PlayerScreen`. No code edit elsewhere — `app/build.gradle` already imports `libs.bundles.media3`.

- [ ] **Step 1: Add the library entry**

Edit `gradle/libs.versions.toml`. After the existing `media3-ui` line, add a new entry:

```toml
# Media3 (replaces ExoPlayer)
media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
media3-exoplayer-hls = { group = "androidx.media3", name = "media3-exoplayer-hls", version.ref = "media3" }
media3-ui = { group = "androidx.media3", name = "media3-ui", version.ref = "media3" }
```

Then update the `media3` bundle to include the new library:

```toml
# Media3
media3 = ["media3-exoplayer", "media3-exoplayer-hls", "media3-ui"]
```

- [ ] **Step 2: Sync and build**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`. The new dependency is resolved transitively via the bundle.

- [ ] **Step 3: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add gradle/libs.versions.toml
git commit -m "build(app): add media3-exoplayer-hls so PlayerScreen can play HLS"
```

---

## Task 2: Persist `hlsCodec` in `VideoSettingsPreferences`

**Files:**
- Modify: `app/src/main/java/com/davotoula/lce/data/VideoSettingsPreferences.kt`

Add a new persisted field `hlsCodec: Codec` (defaulting to `Codec.H264`) using the same string-based DataStore pattern as the existing `codec` field.

- [ ] **Step 1: Add the new key, field, and read/write code**

Replace the entire body of `VideoSettingsPreferences.kt` with:

```kotlin
package com.davotoula.lce.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.davotoula.lce.ui.main.Codec
import com.davotoula.lightcompressor.Resolution
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.videoSettingsDataStore by preferencesDataStore(name = "video_settings_preferences")

data class VideoSettings(
    val resolution: Resolution = Resolution.HD_720,
    val codec: Codec = Codec.H264,
    val isStreamableEnabled: Boolean = true,
    val bitrateKbps: Int? = null,
    val hlsCodec: Codec = Codec.H264,
)

class VideoSettingsPreferences(
    private val context: Context,
) {
    private val resolutionKey = stringPreferencesKey("resolution")
    private val codecKey = stringPreferencesKey("codec")
    private val streamableKey = booleanPreferencesKey("streamable")
    private val bitrateKey = intPreferencesKey("bitrate_kbps")
    private val hlsCodecKey = stringPreferencesKey("hls_codec")

    val settings: Flow<VideoSettings> =
        context.videoSettingsDataStore.data.map { prefs ->
            VideoSettings(
                resolution =
                    prefs[resolutionKey]?.let { name ->
                        Resolution.entries.find { it.name == name }
                    } ?: Resolution.HD_720,
                codec =
                    prefs[codecKey]?.let { name ->
                        Codec.entries.find { it.name == name }
                    } ?: Codec.H264,
                isStreamableEnabled = prefs[streamableKey] ?: true,
                bitrateKbps = prefs[bitrateKey],
                hlsCodec =
                    prefs[hlsCodecKey]?.let { name ->
                        Codec.entries.find { it.name == name }
                    } ?: Codec.H264,
            )
        }

    suspend fun saveSettings(
        resolution: Resolution,
        codec: Codec,
        isStreamableEnabled: Boolean,
        bitrateKbps: Int,
    ) {
        context.videoSettingsDataStore.edit { prefs ->
            prefs[resolutionKey] = resolution.name
            prefs[codecKey] = codec.name
            prefs[streamableKey] = isStreamableEnabled
            prefs[bitrateKey] = bitrateKbps
        }
    }

    suspend fun saveHlsCodec(hlsCodec: Codec) {
        context.videoSettingsDataStore.edit { prefs ->
            prefs[hlsCodecKey] = hlsCodec.name
        }
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :app:assembleDebug 2>&1 | tail -10`
Expected: `BUILD SUCCESSFUL`. (Nothing yet calls `saveHlsCodec` or reads `hlsCodec`, so this should compile cleanly.)

- [ ] **Step 3: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add app/src/main/java/com/davotoula/lce/data/VideoSettingsPreferences.kt
git commit -m "feat(app): persist hlsCodec preference for the HLS test affordance"
```

---

## Task 3: Add HLS state types and actions to `MainUiState.kt`

**Files:**
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt`

Add the new state types (`HlsTestState`, `HlsRenditionState`, `HlsRenditionStatus`, `HlsTerminal`), the `hlsCodec` and `hlsTestState` fields on `MainUiState`, and the new `MainAction` / `MainEvent` cases. The existing fields and actions remain untouched.

- [ ] **Step 1: Replace the file with the extended version**

Replace the entire body of `MainUiState.kt` with:

```kotlin
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
```

- [ ] **Step 2: Build to verify compilation**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :app:assembleDebug 2>&1 | tail -20`
Expected: BUILD FAILS in `MainViewModel.kt` at the `when (action)` dispatch — the new `MainAction` cases (`SetHlsCodec`, `PickHlsVideo`, `StartHlsPreparation`, `CancelHlsPreparation`, `CloseHlsTestState`) are not yet handled. This is expected; Task 5 wires them up.

If you see a different failure (e.g. an unrelated import error), fix it before moving on.

- [ ] **Step 3: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt
git commit -m "feat(app): add HLS test state types and actions to MainUiState"
```

(The build is intentionally red between Task 3 and Task 5 — Task 4 introduces `HlsTestSession` without touching the ViewModel. Task 5 fixes the `when` exhaustiveness.)

---

## Task 4: Implement `HlsTestSession` (TDD)

**Files:**
- Create: `app/src/main/java/com/davotoula/lce/hls/HlsTestSession.kt`
- Test: `app/src/test/java/com/davotoula/lce/hls/HlsTestSessionTest.kt`

`HlsTestSession` is a pure `HlsListener` that knows nothing about Android `Context`. It takes a `rootDir: File`, a `MutableStateFlow<HlsTestState?>`, and an `onIoFailure: () -> Unit` callback so the ViewModel can wire `HlsPreparer.cancel()` without coupling this class to the singleton.

Threading: per `HlsListener` KDoc, `onSegmentReady` and `onProgress` run on `Dispatchers.Default`; the rest run on Main. `MutableStateFlow.update` is thread-safe (atomic CAS), so no explicit dispatch swap is needed for state mutation. Disk I/O in `onSegmentReady` already runs on a worker thread.

- [ ] **Step 1: Create the test file with failing tests**

Create `app/src/test/java/com/davotoula/lce/hls/HlsTestSessionTest.kt`:

```kotlin
package com.davotoula.lce.hls

import com.davotoula.lce.ui.main.HlsRenditionState
import com.davotoula.lce.ui.main.HlsRenditionStatus
import com.davotoula.lce.ui.main.HlsTerminal
import com.davotoula.lce.ui.main.HlsTestState
import com.davotoula.lightcompressor.Resolution
import com.davotoula.lightcompressor.hls.HlsError
import com.davotoula.lightcompressor.hls.HlsSegment
import com.davotoula.lightcompressor.hls.Rendition
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HlsTestSessionTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var rootDir: File
    private lateinit var state: MutableStateFlow<HlsTestState?>
    private var ioFailureCount: Int = 0
    private lateinit var session: HlsTestSession

    private val rendition360 = Rendition(Resolution.SD_360, 500)
    private val rendition720 = Rendition(Resolution.HD_720, 2500)

    private fun seedPending(vararg renditions: Rendition): HlsTestState =
        HlsTestState(
            isRunning = false,
            renditions =
                renditions.map { r ->
                    HlsRenditionState(
                        label = r.resolution.label,
                        status = HlsRenditionStatus.Pending,
                    )
                },
        )

    @Before
    fun setUp() {
        rootDir = tempFolder.newFolder("hls-current")
        state = MutableStateFlow(seedPending(rendition360, rendition720))
        ioFailureCount = 0
        session =
            HlsTestSession(
                rootDir = rootDir,
                state = state,
                onIoFailure = { ioFailureCount++ },
            )
    }

    private fun makeSegment(
        index: Int,
        isInit: Boolean,
        contents: ByteArray = byteArrayOf(0x01, 0x02, 0x03),
    ): HlsSegment {
        val temp = tempFolder.newFile("seg-$index-$isInit.bin")
        temp.writeBytes(contents)
        return HlsSegment(
            file = temp,
            index = index,
            durationSeconds = 6.0,
            isInitSegment = isInit,
        )
    }

    @Test
    fun `onStart trims rendition rows to renditionCount and marks running`() {
        session.onStart(renditionCount = 1)

        val current = state.value!!
        assertTrue(current.isRunning)
        assertEquals(1, current.renditions.size)
        assertEquals("360p", current.renditions[0].label)
    }

    @Test
    fun `onRenditionStart marks the matching row Active and resets progress`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition720)

        val row = state.value!!.renditions.first { it.label == "720p" }
        assertEquals(HlsRenditionStatus.Active, row.status)
        assertEquals(0, row.progressPercent)
    }

    @Test
    fun `onSegmentReady with init segment writes init mp4 and does not bump segmentCount`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition360)
        session.onSegmentReady(rendition360, makeSegment(index = 0, isInit = true))

        val initFile = File(rootDir, "360p/init.mp4")
        assertTrue("init.mp4 should exist", initFile.exists())
        assertEquals(3L, initFile.length())

        val row = state.value!!.renditions.first { it.label == "360p" }
        assertEquals(0, row.segmentCount)
    }

    @Test
    fun `onSegmentReady with media segment writes segment file and bumps segmentCount`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition360)
        session.onSegmentReady(rendition360, makeSegment(index = 0, isInit = false))
        session.onSegmentReady(rendition360, makeSegment(index = 1, isInit = false))

        assertTrue(File(rootDir, "360p/segment_000.m4s").exists())
        assertTrue(File(rootDir, "360p/segment_001.m4s").exists())

        val row = state.value!!.renditions.first { it.label == "360p" }
        assertEquals(2, row.segmentCount)
    }

    @Test
    fun `onProgress updates the matching rendition's progressPercent`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition360)
        session.onProgress(rendition360, percent = 47.0f)

        val row = state.value!!.renditions.first { it.label == "360p" }
        assertEquals(47, row.progressPercent)
    }

    @Test
    fun `onRenditionComplete writes media m3u8 and marks row Complete`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition360)
        session.onRenditionComplete(rendition360, playlist = "#EXTM3U\n# fake\n")

        val mediaFile = File(rootDir, "360p/media.m3u8")
        assertTrue(mediaFile.exists())
        assertEquals("#EXTM3U\n# fake\n", mediaFile.readText())

        val row = state.value!!.renditions.first { it.label == "360p" }
        assertEquals(HlsRenditionStatus.Complete, row.status)
        assertEquals(100, row.progressPercent)
    }

    @Test
    fun `onComplete writes master m3u8 and emits Succeeded terminal with absolute path`() {
        session.onStart(renditionCount = 2)
        session.onComplete(masterPlaylist = "#EXTM3U\n# master\n")

        val masterFile = File(rootDir, "master.m3u8")
        assertTrue(masterFile.exists())
        assertEquals("#EXTM3U\n# master\n", masterFile.readText())

        val current = state.value!!
        assertFalse(current.isRunning)
        val terminal = current.terminal as HlsTerminal.Succeeded
        assertEquals(masterFile.absolutePath, terminal.masterPlaylistPath)
    }

    @Test
    fun `onFailure marks any Active row Failed and emits Failed terminal`() {
        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition720)
        session.onFailure(
            HlsError(
                message = "encoder died",
                failedRenditions = listOf(rendition720),
                completedRenditions = emptyList(),
            ),
        )

        val current = state.value!!
        assertFalse(current.isRunning)
        val terminal = current.terminal as HlsTerminal.Failed
        assertEquals("encoder died", terminal.message)

        val row = current.renditions.first { it.label == "720p" }
        assertEquals(HlsRenditionStatus.Failed, row.status)
    }

    @Test
    fun `onCancelled emits Cancelled terminal`() {
        session.onStart(renditionCount = 2)
        session.onCancelled()

        val current = state.value!!
        assertFalse(current.isRunning)
        assertEquals(HlsTerminal.Cancelled, current.terminal)
    }

    @Test
    fun `onSegmentReady with unwritable target invokes onIoFailure and sets Failed terminal`() {
        // Make the rendition output dir read-only by creating a file where the dir should go.
        File(rootDir, "360p").writeText("blocker")

        session.onStart(renditionCount = 2)
        session.onRenditionStart(rendition360)
        session.onSegmentReady(rendition360, makeSegment(index = 0, isInit = false))

        assertEquals(1, ioFailureCount)
        val current = state.value!!
        assertFalse(current.isRunning)
        assertTrue(current.terminal is HlsTerminal.Failed)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (no `HlsTestSession` yet)**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :app:testDebugUnitTest --tests "com.davotoula.lce.hls.HlsTestSessionTest" 2>&1 | tail -20`
Expected: Compilation failure citing `Unresolved reference: HlsTestSession`. (If you see any other failure unrelated to the missing class, investigate it before moving on.)

- [ ] **Step 3: Implement `HlsTestSession`**

Create `app/src/main/java/com/davotoula/lce/hls/HlsTestSession.kt`:

```kotlin
package com.davotoula.lce.hls

import com.davotoula.lce.ui.main.HlsRenditionState
import com.davotoula.lce.ui.main.HlsRenditionStatus
import com.davotoula.lce.ui.main.HlsTerminal
import com.davotoula.lce.ui.main.HlsTestState
import com.davotoula.lightcompressor.hls.HlsError
import com.davotoula.lightcompressor.hls.HlsListener
import com.davotoula.lightcompressor.hls.HlsSegment
import com.davotoula.lightcompressor.hls.Rendition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException

private const val PROGRESS_COMPLETE_PERCENT = 100
private const val SEGMENT_FILENAME_FORMAT = "segment_%03d.m4s"

/**
 * `HlsListener` implementation used by the sample app's "Prepare HLS" affordance.
 *
 * Persists segments and playlists under [rootDir] in the layout the playlists already
 * reference (`<label>/init.mp4`, `<label>/segment_NNN.m4s`, `<label>/media.m3u8`,
 * `master.m3u8`) so the result is directly playable by `ExoPlayer`.
 *
 * State updates are pushed into [state] via thread-safe `MutableStateFlow.update` calls,
 * so no explicit dispatcher hop is needed even though `onSegmentReady` and `onProgress`
 * run on `Dispatchers.Default` while the rest run on Main.
 */
class HlsTestSession(
    private val rootDir: File,
    private val state: MutableStateFlow<HlsTestState?>,
    private val onIoFailure: () -> Unit,
) : HlsListener {
    override fun onStart(renditionCount: Int) {
        state.update { current ->
            current?.copy(
                isRunning = true,
                renditions = current.renditions.take(renditionCount),
            )
        }
    }

    override fun onRenditionStart(rendition: Rendition) {
        updateRow(rendition) { row ->
            row.copy(status = HlsRenditionStatus.Active, progressPercent = 0)
        }
    }

    override fun onSegmentReady(
        rendition: Rendition,
        segment: HlsSegment,
    ) {
        try {
            val targetDir = File(rootDir, rendition.resolution.label)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IOException("Could not create $targetDir")
            }
            val targetFile =
                if (segment.isInitSegment) {
                    File(targetDir, "init.mp4")
                } else {
                    File(targetDir, SEGMENT_FILENAME_FORMAT.format(segment.index))
                }
            segment.file.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            failWithIoError("Failed to write segment ${segment.index}: ${e.message}")
            return
        }

        if (segment.isInitSegment) return

        updateRow(rendition) { row ->
            row.copy(segmentCount = row.segmentCount + 1)
        }
    }

    override fun onRenditionComplete(
        rendition: Rendition,
        playlist: String,
    ) {
        try {
            val targetDir = File(rootDir, rendition.resolution.label)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IOException("Could not create $targetDir")
            }
            File(targetDir, "media.m3u8").writeText(playlist)
        } catch (e: IOException) {
            failWithIoError("Failed to write media.m3u8: ${e.message}")
            return
        }

        updateRow(rendition) { row ->
            row.copy(
                status = HlsRenditionStatus.Complete,
                progressPercent = PROGRESS_COMPLETE_PERCENT,
            )
        }
    }

    override fun onComplete(masterPlaylist: String) {
        val masterFile: File
        try {
            if (!rootDir.exists() && !rootDir.mkdirs()) {
                throw IOException("Could not create $rootDir")
            }
            masterFile = File(rootDir, "master.m3u8")
            masterFile.writeText(masterPlaylist)
        } catch (e: IOException) {
            failWithIoError("Failed to write master.m3u8: ${e.message}")
            return
        }

        state.update { current ->
            current?.copy(
                isRunning = false,
                terminal = HlsTerminal.Succeeded(masterPlaylistPath = masterFile.absolutePath),
            )
        }
    }

    override fun onProgress(
        rendition: Rendition,
        percent: Float,
    ) {
        updateRow(rendition) { row ->
            row.copy(progressPercent = percent.toInt())
        }
    }

    override fun onFailure(error: HlsError) {
        state.update { current ->
            current?.copy(
                isRunning = false,
                terminal = HlsTerminal.Failed(error.message),
                renditions =
                    current.renditions.map { row ->
                        if (row.status == HlsRenditionStatus.Active) {
                            row.copy(status = HlsRenditionStatus.Failed)
                        } else {
                            row
                        }
                    },
            )
        }
    }

    override fun onCancelled() {
        state.update { current ->
            current?.copy(
                isRunning = false,
                terminal = HlsTerminal.Cancelled,
            )
        }
    }

    private fun updateRow(
        rendition: Rendition,
        transform: (HlsRenditionState) -> HlsRenditionState,
    ) {
        state.update { current ->
            current?.copy(
                renditions =
                    current.renditions.map { row ->
                        if (row.label == rendition.resolution.label) transform(row) else row
                    },
            )
        }
    }

    private fun failWithIoError(message: String) {
        state.update { current ->
            current?.copy(
                isRunning = false,
                terminal = HlsTerminal.Failed(message),
            )
        }
        onIoFailure()
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :app:testDebugUnitTest --tests "com.davotoula.lce.hls.HlsTestSessionTest" 2>&1 | tail -20`
Expected: All 10 tests PASS.

If `onSegmentReady with unwritable target` fails because the test environment allows writing inside a file-as-directory path, replace the blocker setup in that test with `tempFolder.newFolder("360p").apply { setReadOnly() }` and re-run. (On most macOS dev machines, `mkdirs()` returns false when a non-directory file already occupies the path, which is what the original test exercises.)

- [ ] **Step 5: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add app/src/main/java/com/davotoula/lce/hls/HlsTestSession.kt \
        app/src/test/java/com/davotoula/lce/hls/HlsTestSessionTest.kt
git commit -m "feat(app): add HlsTestSession listener that persists HLS output to disk"
```

---

## Task 5: Wire `MainViewModel` to drive HLS preparation

**Files:**
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt`

This task adds the new HLS handlers, loads/saves `hlsCodec`, and fixes the `when (action)` exhaustiveness gap from Task 3. Importantly, the existing compression flow is left untouched.

**Design note:** the screen reads `uiState.hlsTestState`, but `HlsTestSession` needs a `MutableStateFlow<HlsTestState?>` to update. Rather than pushing every HLS update through the full `MainUiState`, we keep a dedicated `_hlsTestState: MutableStateFlow<HlsTestState?>` next to the existing `_uiState`, then `combine` them into the public `uiState: StateFlow<MainUiState>`. `HlsTestSession` writes directly to `_hlsTestState` (thread-safe via `update`), and the screen sees the change through the combined flow.

- [ ] **Step 1: Add the new imports**

Edit `MainViewModel.kt`. Add these imports next to the existing ones (alphabetically appropriate spots):

```kotlin
import com.davotoula.lce.hls.HlsTestSession
import com.davotoula.lightcompressor.HlsPreparer
import com.davotoula.lightcompressor.hls.HlsConfig
import com.davotoula.lightcompressor.hls.HlsLadder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
```

(`java.io.File` is not currently imported — `deleteCompressedFile` uses `java.io.File` qualified — adding the import is fine.)

- [ ] **Step 2: Replace the `_uiState` / `uiState` declaration with the combined flow**

Find the existing pair (around `MainViewModel.kt:58-59`):

```kotlin
private val _uiState = MutableStateFlow(MainUiState())
val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
```

Replace it with:

```kotlin
private val _uiState = MutableStateFlow(MainUiState())
private val _hlsTestState = MutableStateFlow<HlsTestState?>(null)

val uiState: StateFlow<MainUiState> =
    combine(_uiState, _hlsTestState) { base, hlsState ->
        base.copy(hlsTestState = hlsState)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MainUiState(),
    )
```

The `kotlinx.coroutines.flow.asStateFlow` import becomes unused — remove it.

- [ ] **Step 3: Extend `loadSavedSettings` to also restore `hlsCodec`**

Find the existing `loadSavedSettings()` method (around `MainViewModel.kt:73`) and update its `_uiState.update { ... }` block to also set `hlsCodec`:

```kotlin
private fun loadSavedSettings() {
    viewModelScope.launch {
        val settings = videoSettingsPreferences.settings.first()
        _uiState.update { state ->
            state.copy(
                selectedResolution = settings.resolution,
                customResolutionInput = settings.resolution.shortSide.toString(),
                selectedCodec = settings.codec,
                isStreamableEnabled = settings.isStreamableEnabled,
                bitrateKbps = settings.bitrateKbps ?: state.bitrateKbps,
                bitrateInput = settings.bitrateKbps?.toString() ?: state.bitrateInput,
                hlsCodec = settings.hlsCodec,
            )
        }
        if (settings.bitrateKbps == null) {
            calculateAutoBitrate()
        }
    }
}
```

- [ ] **Step 4: Add the new `when (action)` branches**

Find the `onAction` method (around `MainViewModel.kt:104`) and extend the `when` to handle the five new actions. The full block should now read:

```kotlin
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
        is MainAction.SetHlsCodec -> handleSetHlsCodec(action.codec)
        MainAction.PickHlsVideo -> handlePickHlsVideo()
        is MainAction.StartHlsPreparation -> handleStartHlsPreparation(action.uri)
        MainAction.CancelHlsPreparation -> handleCancelHlsPreparation()
        MainAction.CloseHlsTestState -> handleCloseHlsTestState()
    }
}
```

- [ ] **Step 5: Add the HLS handler methods**

Append these private methods to the `MainViewModel` class, just above `onCleared()`:

```kotlin
private fun handleSetHlsCodec(codec: Codec) {
    val effectiveCodec =
        if (codec == Codec.H265 && !isH265Supported()) {
            showToast(context.getString(R.string.h265_not_supported_fallback))
            Codec.H264
        } else {
            codec
        }
    _uiState.update { it.copy(hlsCodec = effectiveCodec) }
    viewModelScope.launch {
        videoSettingsPreferences.saveHlsCodec(effectiveCodec)
    }
}

private fun handlePickHlsVideo() {
    if (_hlsTestState.value?.isRunning == true) return
    viewModelScope.launch {
        _events.emit(MainEvent.LaunchHlsPicker)
    }
}

private fun handleStartHlsPreparation(uri: Uri) {
    if (_hlsTestState.value?.isRunning == true) return

    val rootDir = File(context.filesDir, "hls/current")
    rootDir.deleteRecursively()
    if (!rootDir.mkdirs()) {
        showToast("Failed to prepare HLS output directory")
        return
    }

    val ladder = HlsLadder.default()
    val seededRows =
        ladder.renditions.map { rendition ->
            HlsRenditionState(
                label = rendition.resolution.label,
                status = HlsRenditionStatus.Pending,
            )
        }
    _hlsTestState.value =
        HlsTestState(
            isRunning = true,
            renditions = seededRows,
            terminal = null,
        )

    val videoCodec =
        when (_uiState.value.hlsCodec) {
            Codec.H264 -> VideoCodec.H264
            Codec.H265 -> if (isH265Supported()) VideoCodec.H265 else VideoCodec.H264
        }
    val config = HlsConfig(ladder = ladder, codec = videoCodec)

    val session =
        HlsTestSession(
            rootDir = rootDir,
            state = _hlsTestState,
            onIoFailure = { HlsPreparer.cancel() },
        )

    HlsPreparer.start(
        context = context,
        uri = uri,
        config = config,
        listener = session,
    )
}

private fun handleCancelHlsPreparation() {
    HlsPreparer.cancel()
}

private fun handleCloseHlsTestState() {
    if (_hlsTestState.value?.isRunning == true) return
    _hlsTestState.value = null
}
```

- [ ] **Step 6: Cancel any running HLS preparation in `onCleared()`**

Update the existing `onCleared()` method to also cancel HLS:

```kotlin
override fun onCleared() {
    super.onCleared()
    if (_uiState.value.isCompressing) {
        VideoCompressor.cancel()
    }
    if (_hlsTestState.value?.isRunning == true) {
        HlsPreparer.cancel()
    }
    gifJobs.forEach { it.cancel() }
    gifJobs.clear()
}
```

- [ ] **Step 7: Build to verify compilation**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :app:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL. The `when (action)` is exhaustive again, and the new HLS plumbing compiles.

- [ ] **Step 8: Run unit tests to ensure nothing regressed**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :app:testDebugUnitTest 2>&1 | tail -10`
Expected: All tests PASS (including the `HlsTestSessionTest` from Task 4).

- [ ] **Step 9: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt
git commit -m "feat(app): wire MainViewModel to drive HlsPreparer with HlsTestSession"
```

---

## Task 6: Add the HLS UI to `MainScreen`

**Files:**
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainScreen.kt`

Add three things, in order:

1. A single-select photo picker launcher for HLS, triggered by the new `MainEvent.LaunchHlsPicker` event
2. The status card (rendered between the video list and the bottom buttons when `uiState.hlsTestState != null`)
3. A new row in the bottom button area containing the **HLS codec** dropdown and the **Prepare HLS** button

The existing button bar must remain visually unchanged.

- [ ] **Step 1: Add new imports at the top of `MainScreen.kt`**

Add these alongside the existing imports (skip any that are already present — `mutableStateOf`, `remember`, `getValue`, `setValue` may already be imported):

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

- [ ] **Step 2: Add the HLS picker launcher and event handler**

Inside `MainScreen` composable, add a new launcher near the existing `videoPickerLauncher` (around `MainScreen.kt:91`):

```kotlin
// HLS single-select picker launcher
val hlsVideoPickerLauncher =
    rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            viewModel.onAction(MainAction.StartHlsPreparation(uri))
        }
    }
```

Then update the existing event-handler `LaunchedEffect(Unit)` block (around `MainScreen.kt:155`) so it also handles `LaunchHlsPicker`:

```kotlin
LaunchedEffect(Unit) {
    viewModel.events.collectLatest { event ->
        when (event) {
            is MainEvent.NavigateToPlayer -> onNavigateToPlayer(event.videoPath)
            MainEvent.LaunchHlsPicker ->
                hlsVideoPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                )
        }
    }
}
```

- [ ] **Step 3: Render the status card in the scrollable column**

Inside the inner scrollable `Column` (around `MainScreen.kt:227-269`), add the status card after the video list block (after the `if (uiState.videos.isNotEmpty()) { ... }` block) and before the closing brace of the inner `Column`:

```kotlin
uiState.hlsTestState?.let { hlsState ->
    Spacer(modifier = Modifier.height(16.dp))
    HlsTestStatusCard(
        state = hlsState,
        onCancel = { viewModel.onAction(MainAction.CancelHlsPreparation) },
        onClose = { viewModel.onAction(MainAction.CloseHlsTestState) },
        onPlay = { path -> viewModel.onAction(MainAction.PlayVideo(path)) },
    )
}
```

- [ ] **Step 4: Add the HLS controls row to the bottom buttons section**

Inside the bottom-buttons `Column` (around `MainScreen.kt:271-344`), add a NEW row at the very top of the column (above the `if (uiState.videos.isNotEmpty() && !uiState.isCompressing)` block) so the existing rows stay visually unchanged:

```kotlin
HlsControlsRow(
    selectedCodec = uiState.hlsCodec,
    isRunning = uiState.hlsTestState?.isRunning == true,
    onSelectCodec = { codec -> viewModel.onAction(MainAction.SetHlsCodec(codec)) },
    onPrepareHls = { viewModel.onAction(MainAction.PickHlsVideo) },
)
```

- [ ] **Step 5: Add the helper composables at the bottom of `MainScreen.kt`**

Append these new top-level composables at the end of the file (after `launchVideoCapture`). The `HLS_PERCENT_DIVISOR` constant satisfies detekt's `MagicNumber` rule:

```kotlin
private const val HLS_PERCENT_DIVISOR = 100f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HlsControlsRow(
    selectedCodec: Codec,
    isRunning: Boolean,
    onSelectCodec: (Codec) -> Unit,
    onPrepareHls: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { if (!isRunning) expanded = !expanded },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selectedCodec.displayName,
                onValueChange = {},
                readOnly = true,
                enabled = !isRunning,
                label = { Text("HLS codec") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier.menuAnchor().fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                Codec.entries.forEach { codec ->
                    DropdownMenuItem(
                        text = { Text(codec.displayName) },
                        onClick = {
                            onSelectCodec(codec)
                            expanded = false
                        },
                    )
                }
            }
        }

        Button(
            onClick = onPrepareHls,
            enabled = !isRunning,
            modifier = Modifier.weight(1f),
        ) {
            Text("Prepare HLS")
        }
    }
}

@Composable
private fun HlsTestStatusCard(
    state: HlsTestState,
    onCancel: () -> Unit,
    onClose: () -> Unit,
    onPlay: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header row: title + close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "HLS preparation",
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    onClick = onClose,
                    enabled = !state.isRunning,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Per-rendition rows
            state.renditions.forEach { row ->
                HlsRenditionRow(row)
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Footer: cancel / play / status caption
            when {
                state.isRunning ->
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Cancel")
                    }
                state.terminal is HlsTerminal.Succeeded -> {
                    Button(
                        onClick = { onPlay(state.terminal.masterPlaylistPath) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Play HLS")
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = state.terminal.masterPlaylistPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.terminal is HlsTerminal.Failed ->
                    Text(
                        text = state.terminal.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                state.terminal is HlsTerminal.Cancelled ->
                    Text(
                        text = "Cancelled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                else -> Unit
            }
        }
    }
}

@Composable
private fun HlsRenditionRow(row: HlsRenditionState) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = row.label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(56.dp),
        )

        Box(
            modifier = Modifier.width(20.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (row.status) {
                HlsRenditionStatus.Pending ->
                    Text(text = "—", style = MaterialTheme.typography.bodyMedium)
                HlsRenditionStatus.Active ->
                    CircularProgressIndicator(
                        modifier = Modifier.width(16.dp).height(16.dp),
                        strokeWidth = 2.dp,
                    )
                HlsRenditionStatus.Complete ->
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Complete",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                HlsRenditionStatus.Failed ->
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Failed",
                        tint = MaterialTheme.colorScheme.error,
                    )
            }
        }

        if (row.status == HlsRenditionStatus.Active || row.status == HlsRenditionStatus.Complete) {
            LinearProgressIndicator(
                progress = { row.progressPercent / HLS_PERCENT_DIVISOR },
                modifier = Modifier.weight(1f),
            )
        } else {
            Spacer(modifier = Modifier.weight(1f))
        }

        Text(
            text = "${row.segmentCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

- [ ] **Step 6: Build to verify compilation**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :app:assembleDebug 2>&1 | tail -20`
Expected: BUILD SUCCESSFUL. If you see Compose lint warnings about `menuAnchor()` deprecation or `progress = { ... }` lambda overload, those are non-fatal — leave them as-is to match the target Material3 API surface.

- [ ] **Step 7: Run lint and unit tests**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew ktlintCheck detekt :app:testDebugUnitTest 2>&1 | tail -15`
Expected: BUILD SUCCESSFUL. If ktlint complains about formatting, run `./gradlew ktlintFormat` and re-stage. If detekt complains about long methods or magic numbers in `MainViewModel.kt`, add a focused `@Suppress` or extract a private helper.

- [ ] **Step 8: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add app/src/main/java/com/davotoula/lce/ui/main/MainScreen.kt
git commit -m "feat(app): add HLS controls row, status card, and picker wiring to MainScreen"
```

---

## Task 7: Full build verification

**Files:** All new and modified files.

- [ ] **Step 1: Run the full build**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL (both modules).

- [ ] **Step 2: Run all unit tests**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew testDebugUnitTest 2>&1 | tail -10`
Expected: All tests PASS, including the new `HlsTestSessionTest`.

- [ ] **Step 3: Run ktlint**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew ktlintCheck 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL. If violations exist, run `./gradlew ktlintFormat`, re-stage, and amend with a fixup commit.

- [ ] **Step 4: Run detekt**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew detekt 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL. If violations exist, prefer extracting helpers or constants over `@Suppress`. Common offenders for this change set: `LongMethod`, `LongParameterList`, `MagicNumber`.

- [ ] **Step 5: Commit any lint/format fixes**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add -A
git commit -m "style: ktlint and detekt fixes for HLS test affordance"
```

(Skip this commit if there were no fixes.)

---

## Task 8: Manual verification on a device

**Files:** None (manual checklist).

These steps run against the installed debug APK from Task 7. They are not automated; an implementer should walk through them after the previous tasks are merged or before opening a PR.

- [ ] **Step 1: H.264 happy path**

Install the debug APK (`./gradlew :app:installDebug`), open the app, choose **H.264** in the new "HLS codec" dropdown, tap **Prepare HLS**, pick a short (2–10 second) video. Expect:
- The status card appears with up to five Pending rows
- `360p` becomes Active, then ✓; rows climb the ladder until the source short side is exceeded
- The `Cancel` button is replaced with `Play HLS` and a caption showing the file path

- [ ] **Step 2: Playback**

Tap **Play HLS**. `PlayerScreen` opens and plays the master playlist with adaptive switching across the encoded renditions. There is no audible glitch at segment boundaries.

- [ ] **Step 3: Cancel mid-run**

Pick a longer video (~30 seconds) and tap **Cancel** while encoding is in progress. Expect: encoding stops within ~1 second, the card shows "Cancelled", and the ✕ close button becomes enabled.

- [ ] **Step 4: H.265 + persistence**

Switch the dropdown to **H.265 (HEVC)**. Run **Prepare HLS** again — verify the H.265 path works. Force-stop the app, relaunch it, and confirm the dropdown still shows **H.265 (HEVC)** (DataStore persistence).

- [ ] **Step 5: Inspect on-disk layout**

```bash
adb pull /data/data/com.davotoula.lce.dev/files/hls/current /tmp/hls-out
ls -R /tmp/hls-out
```

(Use the `.dev` applicationId for debug builds — see `app/build.gradle`. Drop `.dev` for the release build.)

Expect a tree like:

```
/tmp/hls-out/
  master.m3u8
  360p/init.mp4 segment_000.m4s segment_001.m4s media.m3u8
  540p/...
  720p/...
  1080p/... (if source ≥ 1080p)
  4K/...    (if source ≥ 2160p)
```

- [ ] **Step 6: Failure path smoke test**

Pick a source the device's encoder can't handle (an HDR clip is a good candidate). Verify the run completes with some renditions Failed and the rest Succeeded — or, if all fail, the card shows a clear failure message instead of crashing.

---

## Task 9: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add a test affordance note under the App section**

Append the following to the App section in `CLAUDE.md`, just after the existing app-section paragraph:

```markdown
**HLS test affordance:** A "Prepare HLS" button on `MainScreen` (with an HLS codec dropdown) exercises `HlsPreparer` end-to-end against a single picked video. Output is written to `filesDir/hls/current/` (wiped before every run) by `HlsTestSession`, an `HlsListener` impl in `app/src/main/java/com/davotoula/lce/hls/`. The resulting `master.m3u8` plays back via the existing `PlayerScreen`, which uses Media3's HLS source (`media3-exoplayer-hls`).
```

- [ ] **Step 2: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add CLAUDE.md
git commit -m "docs: document HLS test affordance in CLAUDE.md"
```

---

## Task Summary

| Task | Component | Type | Dependencies |
|------|-----------|------|--------------|
| 1 | `media3-exoplayer-hls` dependency | Build | None |
| 2 | `VideoSettingsPreferences.hlsCodec` | Persistence | None |
| 3 | `MainUiState` HLS types & actions | State | None |
| 4 | `HlsTestSession` (TDD) | Listener impl | Task 3 |
| 5 | `MainViewModel` HLS handlers | Wiring | Tasks 2, 3, 4 |
| 6 | `MainScreen` HLS UI | UI | Tasks 3, 5 |
| 7 | Build / lint / test verification | Verification | Tasks 1–6 |
| 8 | Manual device checklist | Verification | Task 7 |
| 9 | Update CLAUDE.md | Docs | Task 7 |
