# HLS Screen Separation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the HLS test affordance out of `MainScreen`/`MainViewModel` and into a dedicated `HlsScreen` reached from a TopAppBar icon, restoring the main compression screen to its pre-HLS shape.

**Architecture:** New `com.davotoula.lce.ui.hls` package with `HlsUiState`, `HlsViewModel`, `HlsScreen`, and the relocated `HlsTestSession`. `LceNavHost` gains a new route and hoists a single activity-scoped `HlsViewModel` so HLS runs survive back-navigation. `MainScreen`/`MainViewModel` lose all HLS fields, actions, events, and handlers.

**Tech Stack:** Kotlin 2.2.21, Jetpack Compose BOM 2025.12.01, Navigation Compose, androidx.activity-compose 1.13.0 (provides `LocalActivity`), JUnit 4 + MockK, ktlint + detekt.

**Related spec:** `docs/specs/2026-04-13-hls-screen-separation-design.md`

---

## Conventions

- **Per-task commit.** Each task ends with a single commit. The pre-commit hook runs `ktlintFormat`; accept its auto-edits.
- **No HlsViewModel unit tests.** The existing project pattern skips `MainViewModelTest` because `AndroidViewModel` + DataStore is costly to mock. Behavioural coverage of HLS already lives in `HlsTestSessionTest` (the listener side does all the state mutation). We verify `HlsViewModel` via the existing `HlsTestSessionTest` plus a manual smoke test in Task 8. If tests are wanted later, they'll require restructuring to inject `VideoSettingsPreferences` via the constructor.
- **Leave the build green after every task.** Between tasks the code must compile and `testDebugUnitTest` must pass. Steps spell out exactly what to keep temporarily in place.
- **No git push.** Feedback rule: commit locally only.

---

## File Map

**New files:**
- `app/src/main/java/com/davotoula/lce/ui/hls/HlsUiState.kt` — `HlsUiState`, `HlsAction`, `HlsEvent`, and the HLS state types currently in `MainUiState.kt` (`HlsRenditionState`, `HlsRenditionStatus`, `HlsTerminal`, `HlsTestState`).
- `app/src/main/java/com/davotoula/lce/ui/hls/HlsViewModel.kt` — activity-scoped ViewModel owning HLS codec selection, preparation lifecycle, and `HlsTestSession` wiring.
- `app/src/main/java/com/davotoula/lce/ui/hls/HlsScreen.kt` — the dedicated screen, plus private `HlsControlsRow`/`HlsTestStatusCard`/`HlsRenditionRow` composables moved from `MainScreen.kt`.

**Moved files (package rename only):**
- `app/src/main/java/com/davotoula/lce/hls/HlsTestSession.kt` → `app/src/main/java/com/davotoula/lce/ui/hls/HlsTestSession.kt`
- `app/src/test/java/com/davotoula/lce/hls/HlsTestSessionTest.kt` → `app/src/test/java/com/davotoula/lce/ui/hls/HlsTestSessionTest.kt`

**Modified files:**
- `app/src/main/java/com/davotoula/lce/ui/main/MainScreen.kt` — strip HLS UI, add HLS icon button in TopAppBar.
- `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt` — strip HLS state, actions, handlers, cleanup.
- `app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt` — strip HLS fields from `MainUiState`, HLS actions from `MainAction`, `LaunchHlsPicker` from `MainEvent`. Keep `Codec` (shared).
- `app/src/main/java/com/davotoula/lce/navigation/LceNavigation.kt` — add `LceRoute.Hls`, hoist shared `HlsViewModel`, add `HlsScreen` destination, pass `onNavigateToHls` callback to `MainScreen`.
- `app/src/main/res/values/strings.xml` — add one string (`hls_icon_description` for the TopAppBar icon's `contentDescription`).

---

## Task 1: Move `HlsTestSession` to `ui/hls` package

**Files:**
- Create: `app/src/main/java/com/davotoula/lce/ui/hls/HlsTestSession.kt`
- Delete: `app/src/main/java/com/davotoula/lce/hls/HlsTestSession.kt`
- Create: `app/src/test/java/com/davotoula/lce/ui/hls/HlsTestSessionTest.kt`
- Delete: `app/src/test/java/com/davotoula/lce/hls/HlsTestSessionTest.kt`
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt` (one import line)

Package rename only. No logic changes.

- [ ] **Step 1: Create the new `HlsTestSession.kt` file**

Copy the full contents of the existing file but change the package declaration.

New file: `app/src/main/java/com/davotoula/lce/ui/hls/HlsTestSession.kt` — identical to the existing file except line 1 changes from:

```kotlin
package com.davotoula.lce.hls
```

to:

```kotlin
package com.davotoula.lce.ui.hls
```

Keep all other imports and body bytes-identical. This file still references `com.davotoula.lce.ui.main.HlsRenditionState`, `HlsRenditionStatus`, `HlsTerminal`, `HlsTestState` — do NOT change those imports yet. (Task 2 moves those types to `ui.hls`.)

- [ ] **Step 2: Delete the old file**

```bash
rm app/src/main/java/com/davotoula/lce/hls/HlsTestSession.kt
```

- [ ] **Step 3: Create the new test file**

Copy the existing test contents but change line 1 from:

```kotlin
package com.davotoula.lce.hls
```

to:

```kotlin
package com.davotoula.lce.ui.hls
```

Keep all other imports and body identical.

- [ ] **Step 4: Delete the old test file**

```bash
rm app/src/test/java/com/davotoula/lce/hls/HlsTestSessionTest.kt
```

- [ ] **Step 5: Update the import in `MainViewModel.kt`**

Find the import near the top of `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt`:

```kotlin
import com.davotoula.lce.hls.HlsTestSession
```

Replace with:

```kotlin
import com.davotoula.lce.ui.hls.HlsTestSession
```

- [ ] **Step 6: Run tests to verify the rename is clean**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: all tests pass, including `HlsTestSessionTest` from its new package location.

- [ ] **Step 7: Remove the now-empty old package directories**

```bash
rmdir app/src/main/java/com/davotoula/lce/hls 2>/dev/null
rmdir app/src/test/java/com/davotoula/lce/hls 2>/dev/null
```

(Silent-fail if a directory isn't actually empty; nothing to do then.)

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(hls): move HlsTestSession to ui.hls package

Package rename only, no logic changes. Preparatory step for the
dedicated HLS screen.
EOF
)"
```

---

## Task 2: Extract HLS state types into `ui/hls/HlsUiState.kt`

**Files:**
- Create: `app/src/main/java/com/davotoula/lce/ui/hls/HlsUiState.kt`
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt`
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt`
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainScreen.kt`
- Modify: `app/src/main/java/com/davotoula/lce/ui/hls/HlsTestSession.kt`
- Modify: `app/src/test/java/com/davotoula/lce/ui/hls/HlsTestSessionTest.kt`

Moves `HlsRenditionState`, `HlsRenditionStatus`, `HlsTerminal`, `HlsTestState` out of `MainUiState.kt` and into a new `HlsUiState.kt` in `ui/hls/`. Also defines the new `HlsUiState`, `HlsAction`, `HlsEvent` types that Task 3 will consume. `Codec` stays in `ui/main/MainUiState.kt` (shared).

`MainUiState` still retains `hlsCodec` and `hlsTestState` fields temporarily — they're stripped in Task 7. `MainAction` still retains HLS actions. Everything compiles between tasks.

- [ ] **Step 1: Create `HlsUiState.kt` with all HLS types**

Create `app/src/main/java/com/davotoula/lce/ui/hls/HlsUiState.kt`:

```kotlin
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
```

- [ ] **Step 2: Remove the HLS types from `MainUiState.kt`**

In `app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt`, delete these blocks:

```kotlin
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
```

Leave everything else in `MainUiState.kt` untouched — including `MainUiState.hlsCodec`, `MainUiState.hlsTestState`, and the HLS `MainAction` entries.

- [ ] **Step 3: Add imports to `MainUiState.kt`**

Near the top of `MainUiState.kt`, add:

```kotlin
import com.davotoula.lce.ui.hls.HlsTestState
```

(The other HLS types — `HlsRenditionState`, `HlsRenditionStatus`, `HlsTerminal` — are only referenced from other files, not from `MainUiState.kt` itself, so only `HlsTestState` needs importing here for the `MainUiState.hlsTestState` field type.)

- [ ] **Step 4: Add imports to `MainViewModel.kt`**

Near the top of `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt`, add:

```kotlin
import com.davotoula.lce.ui.hls.HlsRenditionState
import com.davotoula.lce.ui.hls.HlsRenditionStatus
import com.davotoula.lce.ui.hls.HlsTestState
```

- [ ] **Step 5: Add imports to `MainScreen.kt`**

Near the top of `app/src/main/java/com/davotoula/lce/ui/main/MainScreen.kt`, add:

```kotlin
import com.davotoula.lce.ui.hls.HlsRenditionState
import com.davotoula.lce.ui.hls.HlsRenditionStatus
import com.davotoula.lce.ui.hls.HlsTerminal
import com.davotoula.lce.ui.hls.HlsTestState
```

- [ ] **Step 6: Update imports in `HlsTestSession.kt`**

In `app/src/main/java/com/davotoula/lce/ui/hls/HlsTestSession.kt`, find:

```kotlin
import com.davotoula.lce.ui.main.HlsRenditionState
import com.davotoula.lce.ui.main.HlsRenditionStatus
import com.davotoula.lce.ui.main.HlsTerminal
import com.davotoula.lce.ui.main.HlsTestState
```

Replace with:

```kotlin
import com.davotoula.lce.ui.hls.HlsRenditionState
import com.davotoula.lce.ui.hls.HlsRenditionStatus
import com.davotoula.lce.ui.hls.HlsTerminal
import com.davotoula.lce.ui.hls.HlsTestState
```

Wait — these references are same-package after the move, so you can simply delete the four `import` lines entirely. Delete them.

- [ ] **Step 7: Update imports in `HlsTestSessionTest.kt`**

In `app/src/test/java/com/davotoula/lce/ui/hls/HlsTestSessionTest.kt`, find:

```kotlin
import com.davotoula.lce.ui.main.HlsRenditionState
import com.davotoula.lce.ui.main.HlsRenditionStatus
import com.davotoula.lce.ui.main.HlsTerminal
import com.davotoula.lce.ui.main.HlsTestState
```

Delete those four lines (same-package now that the test lives in `com.davotoula.lce.ui.hls`).

- [ ] **Step 8: Run tests**

```bash
./gradlew :app:testDebugUnitTest
```

Expected: all tests pass. `HlsTestSessionTest` should still cover the same behavior.

- [ ] **Step 9: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(hls): move HLS state types into ui.hls package

Extracts HlsRenditionState, HlsRenditionStatus, HlsTerminal, HlsTestState
from MainUiState.kt into a new HlsUiState.kt file, alongside new
HlsUiState/HlsAction/HlsEvent types that the upcoming HlsViewModel will
consume. MainUiState still carries hlsCodec/hlsTestState temporarily —
those are stripped once HlsViewModel owns them.
EOF
)"
```

---

## Task 3: Create `HlsViewModel`

**Files:**
- Create: `app/src/main/java/com/davotoula/lce/ui/hls/HlsViewModel.kt`

Creates a new `AndroidViewModel` that owns HLS codec selection, preparation lifecycle, and `HlsTestSession` wiring. Logic is ported 1:1 from the HLS-related methods in `MainViewModel`. Not yet wired into navigation — Task 5 does that.

`MainViewModel` still retains its HLS handlers. Both exist in parallel until Task 7.

- [ ] **Step 1: Create `HlsViewModel.kt`**

Create `app/src/main/java/com/davotoula/lce/ui/hls/HlsViewModel.kt`:

```kotlin
package com.davotoula.lce.ui.hls

import android.app.Application
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.davotoula.lce.R
import com.davotoula.lce.data.VideoSettingsPreferences
import com.davotoula.lce.ui.main.Codec
import com.davotoula.lightcompressor.HlsPreparer
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.hls.HlsConfig
import com.davotoula.lightcompressor.hls.HlsLadder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

class HlsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(HlsUiState())
    val uiState: StateFlow<HlsUiState> = _uiState.asStateFlow()

    private val _events = MutableSharedFlow<HlsEvent>()
    val events: SharedFlow<HlsEvent> = _events.asSharedFlow()

    private val _toastMessages = MutableSharedFlow<String>()
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    private val context get() = getApplication<Application>()
    private val videoSettingsPreferences = VideoSettingsPreferences(application)

    init {
        viewModelScope.launch {
            val settings = videoSettingsPreferences.settings.first()
            _uiState.update { it.copy(hlsCodec = settings.hlsCodec) }
        }
    }

    fun onAction(action: HlsAction) {
        when (action) {
            is HlsAction.SetCodec -> handleSetCodec(action.codec)
            HlsAction.PickVideo -> handlePickVideo()
            is HlsAction.StartPreparation -> handleStartPreparation(action.uri)
            HlsAction.CancelPreparation -> handleCancelPreparation()
            HlsAction.CloseTestState -> handleCloseTestState()
        }
    }

    private fun handleSetCodec(codec: Codec) {
        val effectiveCodec =
            if (codec == Codec.H265 && !isH265Supported()) {
                viewModelScope.launch {
                    _toastMessages.emit(context.getString(R.string.h265_not_supported_fallback))
                }
                Codec.H264
            } else {
                codec
            }
        _uiState.update { it.copy(hlsCodec = effectiveCodec) }
        viewModelScope.launch {
            videoSettingsPreferences.saveHlsCodec(effectiveCodec)
        }
    }

    private fun handlePickVideo() {
        if (_uiState.value.testState?.isRunning == true) return
        viewModelScope.launch {
            _events.emit(HlsEvent.LaunchPicker)
        }
    }

    private fun handleStartPreparation(uri: Uri) {
        if (_uiState.value.testState?.isRunning == true) return

        val rootDir = File(context.filesDir, "hls/current")
        rootDir.deleteRecursively()
        if (!rootDir.mkdirs()) {
            viewModelScope.launch {
                _toastMessages.emit(context.getString(R.string.hls_output_dir_failed))
            }
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
        val testStateFlow = MutableStateFlow<HlsTestState?>(
            HlsTestState(
                isRunning = true,
                renditions = seededRows,
                terminal = null,
            ),
        )
        _uiState.update { it.copy(testState = testStateFlow.value) }

        // Mirror every update from testStateFlow into _uiState so HlsTestSession's
        // thread-safe .update() calls stay the source of truth while the VM's
        // aggregated UI state reflects them for the screen.
        viewModelScope.launch {
            testStateFlow.collect { snapshot ->
                _uiState.update { it.copy(testState = snapshot) }
            }
        }

        val videoCodec =
            when (_uiState.value.hlsCodec) {
                Codec.H264 -> VideoCodec.H264
                Codec.H265 -> if (isH265Supported()) VideoCodec.H265 else VideoCodec.H264
            }
        val config = HlsConfig(ladder = ladder, codec = videoCodec)

        val session =
            HlsTestSession(
                rootDir = rootDir,
                state = testStateFlow,
                onIoFailure = { HlsPreparer.cancel() },
            )

        HlsPreparer.start(
            context = context,
            uri = uri,
            config = config,
            listener = session,
        )
    }

    private fun handleCancelPreparation() {
        HlsPreparer.cancel()
    }

    private fun handleCloseTestState() {
        if (_uiState.value.testState?.isRunning == true) return
        _uiState.update { it.copy(testState = null) }
    }

    private fun isH265Supported(): Boolean =
        try {
            val codecList = MediaCodecList(MediaCodecList.ALL_CODECS)
            codecList.codecInfos.any { codecInfo ->
                codecInfo.isEncoder &&
                    codecInfo.supportedTypes.any { type ->
                        type.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true)
                    }
            }
        } catch (e: Exception) {
            Log.d("HlsViewModel", "Error checking H.265 support: ${e.message}")
            false
        }

    override fun onCleared() {
        super.onCleared()
        if (_uiState.value.testState?.isRunning == true) {
            HlsPreparer.cancel()
        }
    }
}
```

Note the subtle change from `MainViewModel`: because `HlsTestSession` expects a `MutableStateFlow<HlsTestState?>` it can mutate directly, we keep a per-run `testStateFlow` and collect it into `_uiState`. This preserves the existing `HlsTestSession` contract without modifying the listener side.

- [ ] **Step 2: Build to verify it compiles**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. No tests run yet — this file is orphan until Task 5 wires it in.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(hls): add HlsViewModel for dedicated HLS screen

Activity-scoped ViewModel that owns HLS codec selection, preparation
lifecycle, and HlsTestSession wiring. Logic ported 1:1 from the HLS
handlers in MainViewModel. Not yet wired into navigation.
EOF
)"
```

---

## Task 4: Create `HlsScreen`

**Files:**
- Create: `app/src/main/java/com/davotoula/lce/ui/hls/HlsScreen.kt`

Moves `HlsControlsRow`, `HlsTestStatusCard`, `HlsRenditionRow`, and the `HLS_PERCENT_DIVISOR` constant from `MainScreen.kt` into a new `HlsScreen.kt` as private composables. Adds the top-level `HlsScreen` composable with its own `TopAppBar`, scrollable column, video picker launcher, and event collection. Not yet wired into navigation.

`MainScreen.kt` is NOT modified in this task — the old private composables still exist there in parallel. Task 6 removes them.

- [ ] **Step 1: Create `HlsScreen.kt`**

Create `app/src/main/java/com/davotoula/lce/ui/hls/HlsScreen.kt`:

```kotlin
package com.davotoula.lce.ui.hls

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import com.davotoula.lce.R
import com.davotoula.lce.ui.main.Codec
import kotlinx.coroutines.flow.collectLatest

private const val HLS_PERCENT_DIVISOR = 100f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HlsScreen(
    viewModel: HlsViewModel,
    onBack: () -> Unit,
    onNavigateToPlayer: (String) -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val hlsVideoPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) {
                viewModel.onAction(HlsAction.StartPreparation(uri))
            }
        }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                HlsEvent.LaunchPicker ->
                    hlsVideoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly),
                    )
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.toastMessages.collectLatest { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hls_preparation_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.hls_back_description),
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HlsControlsRow(
                selectedCodec = uiState.hlsCodec,
                isRunning = uiState.testState?.isRunning == true,
                onSelectCodec = { codec -> viewModel.onAction(HlsAction.SetCodec(codec)) },
                onPrepareHls = { viewModel.onAction(HlsAction.PickVideo) },
            )

            uiState.testState?.let { testState ->
                HlsTestStatusCard(
                    state = testState,
                    onCancel = { viewModel.onAction(HlsAction.CancelPreparation) },
                    onClose = { viewModel.onAction(HlsAction.CloseTestState) },
                    onPlay = { path -> onNavigateToPlayer(path) },
                )
            }
        }
    }
}

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
                label = { Text(stringResource(R.string.hls_codec_label)) },
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
            Text(stringResource(R.string.hls_prepare_button))
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.hls_preparation_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(
                    onClick = onClose,
                    enabled = !state.isRunning,
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.hls_close_description),
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            state.renditions.forEach { row ->
                HlsRenditionRow(row)
                Spacer(modifier = Modifier.height(4.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))

            when {
                state.isRunning ->
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hls_cancel_button))
                    }
                state.terminal is HlsTerminal.Succeeded -> {
                    Button(
                        onClick = { onPlay(state.terminal.masterPlaylistPath) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.hls_play_button))
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
                        text = stringResource(R.string.hls_cancelled_status),
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
                        contentDescription = stringResource(R.string.hls_complete_description),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                HlsRenditionStatus.Failed ->
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.hls_failed_description),
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

- [ ] **Step 2: Add the new string resource `hls_back_description`**

Edit `app/src/main/res/values/strings.xml`. After the existing `hls_failed_description` line, add:

```xml
    <string name="hls_back_description">Back to compression</string>
```

- [ ] **Step 3: Build to verify it compiles**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. The file is still orphan (not referenced by navigation yet), but it must compile independently.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(hls): add dedicated HlsScreen composable

Owns its own TopAppBar with a back arrow, codec dropdown, prepare button,
and status card. Private HlsControlsRow/HlsTestStatusCard/HlsRenditionRow
composables mirror the current MainScreen versions; once MainScreen is
cleaned up in a later task, those duplicates will be removed.
EOF
)"
```

---

## Task 5: Wire `HlsScreen` into `LceNavHost` with shared `HlsViewModel`

**Files:**
- Modify: `app/src/main/java/com/davotoula/lce/navigation/LceNavigation.kt`
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

Adds `LceRoute.Hls`, registers the new `composable(LceRoute.Hls.route)`, and hoists a shared activity-scoped `HlsViewModel` so both `MainScreen` (reads `isRunning` for the badge + receives `onNavigateToHls`) and `HlsScreen` (owns the full UI) see the same instance. `MainScreen` gains an HLS icon button in its `TopAppBar` actions that navigates to the new route. The old in-place HLS UI (dropdown, prepare button, status card) is left alone for now — Task 6 removes it.

- [ ] **Step 1: Add the `hls_icon_description` string**

Edit `app/src/main/res/values/strings.xml`. After `hls_back_description`, add:

```xml
    <string name="hls_icon_description">Open HLS preparation</string>
```

- [ ] **Step 2: Add `LceRoute.Hls` and wire the destination**

Replace the contents of `app/src/main/java/com/davotoula/lce/navigation/LceNavigation.kt` with:

```kotlin
@file:Suppress("MatchingDeclarationName")

package com.davotoula.lce.navigation

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.davotoula.lce.ui.hls.HlsScreen
import com.davotoula.lce.ui.hls.HlsViewModel
import com.davotoula.lce.ui.main.MainScreen
import com.davotoula.lce.ui.player.PlayerScreen

/**
 * Sealed class representing all navigation routes in the LCE app.
 */
sealed class LceRoute(
    val route: String,
) {
    data object Main : LceRoute("main")

    data object Hls : LceRoute("hls")

    data object Player : LceRoute("player/{videoPath}") {
        fun createRoute(videoPath: String): String = "player/${Uri.encode(videoPath)}"
    }
}

@Composable
fun LceNavHost(
    navController: NavHostController,
    initialVideoUris: List<Uri> = emptyList(),
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    // Scope the HLS ViewModel to the hosting Activity so a running HLS
    // preparation survives back-navigation between Main and the HLS screen.
    val activity = LocalActivity.current as ComponentActivity
    val hlsViewModel: HlsViewModel = viewModel(viewModelStoreOwner = activity)

    NavHost(
        navController = navController,
        startDestination = LceRoute.Main.route,
    ) {
        composable(LceRoute.Main.route) {
            MainScreen(
                initialVideoUris = initialVideoUris,
                isDarkTheme = isDarkTheme,
                onToggleTheme = onToggleTheme,
                hlsViewModel = hlsViewModel,
                onNavigateToHls = { navController.navigate(LceRoute.Hls.route) },
                onNavigateToPlayer = { videoPath ->
                    navController.navigate(LceRoute.Player.createRoute(videoPath))
                },
            )
        }

        composable(LceRoute.Hls.route) {
            HlsScreen(
                viewModel = hlsViewModel,
                onBack = { navController.popBackStack() },
                onNavigateToPlayer = { videoPath ->
                    navController.navigate(LceRoute.Player.createRoute(videoPath))
                },
            )
        }

        composable(
            route = LceRoute.Player.route,
            arguments =
                listOf(
                    navArgument("videoPath") { type = NavType.StringType },
                ),
        ) { backStackEntry ->
            val videoPath =
                backStackEntry.arguments?.getString("videoPath")?.let {
                    Uri.decode(it)
                } ?: return@composable

            PlayerScreen(
                videoPath = videoPath,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
```

- [ ] **Step 3: Extend `MainScreen` signature and add the HLS icon button**

Open `app/src/main/java/com/davotoula/lce/ui/main/MainScreen.kt`.

3a. Add imports near the top (alphabetical with the other imports):

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Stream
import androidx.compose.ui.draw.clip
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.davotoula.lce.ui.hls.HlsViewModel
```

(If `collectAsStateWithLifecycle` is already imported, skip that line.)

3b. Extend the `MainScreen` parameter list. Find:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun MainScreen(
    initialVideoUris: List<Uri> = emptyList(),
    viewModel: MainViewModel = viewModel(),
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onNavigateToPlayer: (String) -> Unit,
) {
```

Replace with:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Suppress("LongMethod")
@Composable
fun MainScreen(
    initialVideoUris: List<Uri> = emptyList(),
    viewModel: MainViewModel = viewModel(),
    hlsViewModel: HlsViewModel,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onNavigateToHls: () -> Unit,
    onNavigateToPlayer: (String) -> Unit,
) {
```

3c. Collect HLS state near the top of the body, right after the existing `val uiState by viewModel.uiState.collectAsStateWithLifecycle()` line:

```kotlin
    val hlsUiState by hlsViewModel.uiState.collectAsStateWithLifecycle()
    val isHlsRunning = hlsUiState.testState?.isRunning == true
```

3d. In the `TopAppBar` `actions = { ... }` block, add the HLS icon button **before** the existing cancel-compression icon. The new icon goes at the start of the actions block:

```kotlin
                actions = {
                    IconButton(onClick = onNavigateToHls) {
                        Box {
                            Icon(
                                imageVector = Icons.Default.Stream,
                                contentDescription = stringResource(R.string.hls_icon_description),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            if (isHlsRunning) {
                                Box(
                                    modifier =
                                        Modifier
                                            .size(8.dp)
                                            .offset(x = 6.dp, y = (-2).dp)
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.error),
                                )
                            }
                        }
                    }
                    if (uiState.isCompressing) {
                        IconButton(onClick = { viewModel.onAction(MainAction.CancelCompression) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = stringResource(R.string.cancel_compression),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                },
```

Do NOT remove any other HLS code in this task. `HlsControlsRow`, `HlsTestStatusCard`, `hlsVideoPickerLauncher`, and the HLS actions/events stay for now — Task 6 removes them.

- [ ] **Step 4: Build to verify it compiles**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. The app now has both the old HLS UI and a new HLS icon button that navigates to `HlsScreen`. Both paths drive HLS preparation; the new one uses `HlsViewModel`, the old one still uses `MainViewModel`. This is intentional and temporary — Task 6 removes the old path.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
feat(hls): register HlsScreen route and share HlsViewModel from NavHost

Hoists an activity-scoped HlsViewModel in LceNavHost and passes it to
both MainScreen (for the running-indicator badge) and the new HlsScreen
destination. MainScreen gains an HLS icon in its TopAppBar that navigates
to the new route. The old in-place HLS UI is left in place temporarily
and will be removed in a follow-up commit.
EOF
)"
```

---

## Task 6: Remove old HLS UI from `MainScreen` and strip HLS plumbing from `MainViewModel`

**Files:**
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainScreen.kt`
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt`
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt`

This is the cleanup pass. The HLS path now runs entirely through `HlsViewModel`/`HlsScreen`, so everything HLS-related in `MainScreen`/`MainViewModel`/`MainUiState` is dead code and gets removed.

- [ ] **Step 1: Strip HLS composables and launcher from `MainScreen.kt`**

In `app/src/main/java/com/davotoula/lce/ui/main/MainScreen.kt`:

1a. **Remove the HLS picker launcher.** Delete:

```kotlin
    val hlsVideoPickerLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            if (uri != null) {
                viewModel.onAction(MainAction.StartHlsPreparation(uri))
            }
        }
```

1b. **Remove the `LaunchHlsPicker` branch from the event collector.** Find:

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

Replace with:

```kotlin
    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is MainEvent.NavigateToPlayer -> onNavigateToPlayer(event.videoPath)
            }
        }
    }
```

1c. **Remove the HLS status card from the scrollable content.** Delete:

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

1d. **Remove `HlsControlsRow` from the bottom buttons column.** Delete:

```kotlin
                HlsControlsRow(
                    selectedCodec = uiState.hlsCodec,
                    isRunning = uiState.hlsTestState?.isRunning == true,
                    onSelectCodec = { codec -> viewModel.onAction(MainAction.SetHlsCodec(codec)) },
                    onPrepareHls = { viewModel.onAction(MainAction.PickHlsVideo) },
                )
```

1e. **Delete the three private composables and the constant** at the bottom of the file:

```kotlin
private const val HLS_PERCENT_DIVISOR = 100f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HlsControlsRow(
    // ... entire function body
)

@Composable
private fun HlsTestStatusCard(
    // ... entire function body
)

@Composable
private fun HlsRenditionRow(row: HlsRenditionState) {
    // ... entire function body
}
```

1f. **Remove now-unused imports** from `MainScreen.kt`. Delete any of these that no longer have a reference in the file:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import com.davotoula.lce.ui.hls.HlsRenditionState
import com.davotoula.lce.ui.hls.HlsRenditionStatus
import com.davotoula.lce.ui.hls.HlsTerminal
import com.davotoula.lce.ui.hls.HlsTestState
```

Use the IDE's "Optimize Imports" action if available, or delete manually. Ktlint's `no-unused-imports` rule will also flag survivors.

- [ ] **Step 2: Strip HLS from `MainUiState.kt`**

Open `app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt`.

2a. Remove the `hlsCodec` and `hlsTestState` fields from `MainUiState`. The final shape:

```kotlin
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
)
```

2b. Remove the HLS `MainAction` entries:

```kotlin
    data class SetHlsCodec(
        val codec: Codec,
    ) : MainAction()

    data object PickHlsVideo : MainAction()

    data class StartHlsPreparation(
        val uri: Uri,
    ) : MainAction()

    data object CancelHlsPreparation : MainAction()

    data object CloseHlsTestState : MainAction()
```

2c. Remove the HLS `MainEvent`:

```kotlin
    data object LaunchHlsPicker : MainEvent()
```

2d. Remove the now-unused import:

```kotlin
import com.davotoula.lce.ui.hls.HlsTestState
```

- [ ] **Step 3: Strip HLS from `MainViewModel.kt`**

Open `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt`.

3a. Remove the `_hlsTestState` field:

```kotlin
    @Suppress("ktlint:standard:backing-property-naming")
    private val _hlsTestState = MutableStateFlow<HlsTestState?>(null)
```

3b. Simplify `uiState` — remove the `combine` and revert to a direct `asStateFlow()`. Find:

```kotlin
    val uiState: StateFlow<MainUiState> =
        combine(_uiState, _hlsTestState) { base, hlsState ->
            base.copy(hlsTestState = hlsState)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = MainUiState(),
        )
```

Replace with:

```kotlin
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
```

3c. Remove the `hlsCodec` loading in `loadSavedSettings()`. Find:

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

Replace with (remove the `hlsCodec = settings.hlsCodec,` line only):

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
                )
            }
            if (settings.bitrateKbps == null) {
                calculateAutoBitrate()
            }
        }
    }
```

3d. Remove HLS branches from the `onAction` `when` block. Find:

```kotlin
            is MainAction.SetHlsCodec -> handleSetHlsCodec(action.codec)
            MainAction.PickHlsVideo -> handlePickHlsVideo()
            is MainAction.StartHlsPreparation -> handleStartHlsPreparation(action.uri)
            MainAction.CancelHlsPreparation -> handleCancelHlsPreparation()
            MainAction.CloseHlsTestState -> handleCloseHlsTestState()
```

Delete those five lines.

3e. Delete the five HLS handler functions:

```kotlin
    private fun handleSetHlsCodec(codec: Codec) { /* ... */ }
    private fun handlePickHlsVideo() { /* ... */ }
    private fun handleStartHlsPreparation(uri: Uri) { /* ... */ }
    private fun handleCancelHlsPreparation() { /* ... */ }
    private fun handleCloseHlsTestState() { /* ... */ }
```

3f. Simplify `onCleared()`. Find:

```kotlin
    override fun onCleared() {
        super.onCleared()
        // Cancel any ongoing compression when ViewModel is cleared
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

Replace with:

```kotlin
    override fun onCleared() {
        super.onCleared()
        // Cancel any ongoing compression when ViewModel is cleared
        if (_uiState.value.isCompressing) {
            VideoCompressor.cancel()
        }
        gifJobs.forEach { it.cancel() }
        gifJobs.clear()
    }
```

3g. Remove now-unused imports. Delete any of these that are no longer referenced:

```kotlin
import com.davotoula.lce.ui.hls.HlsRenditionState
import com.davotoula.lce.ui.hls.HlsRenditionStatus
import com.davotoula.lce.ui.hls.HlsTestState
import com.davotoula.lce.ui.hls.HlsTestSession
import com.davotoula.lightcompressor.HlsPreparer
import com.davotoula.lightcompressor.hls.HlsConfig
import com.davotoula.lightcompressor.hls.HlsLadder
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.io.File
```

Add if not already present (from the `asStateFlow()` change):

```kotlin
import kotlinx.coroutines.flow.asStateFlow
```

Use IDE optimize-imports or delete manually; ktlint will flag unused survivors.

- [ ] **Step 4: Build and run tests**

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass. `HlsTestSessionTest` should still cover the listener behavior.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(hls): remove old HLS UI and plumbing from main compression screen

Strips HlsControlsRow, HlsTestStatusCard, HlsRenditionRow, and the HLS
picker launcher from MainScreen. Removes hlsCodec/hlsTestState from
MainUiState, five HLS actions from MainAction, LaunchHlsPicker from
MainEvent, and the corresponding handlers from MainViewModel. HLS is now
only reachable via the TopAppBar icon → HlsScreen route.
EOF
)"
```

---

## Task 7: Final verification

**Files:** none modified.

Runs the full static-analysis + test + build pipeline, then executes a manual smoke test of the new navigation.

- [ ] **Step 1: Run the full verification pipeline**

```bash
./gradlew ktlintCheck detekt testDebugUnitTest assembleDebug
```

Expected: all tasks pass. If ktlint or detekt reports issues, fix inline (most likely: unused imports, formatting) and re-run before proceeding.

- [ ] **Step 2: Install the debug APK on a connected device or emulator**

```bash
./gradlew :app:installDebug
```

- [ ] **Step 3: Manual smoke test — main compression path**

Launch the app and verify the compression path is unchanged:

1. Open the app. MainScreen shows settings card, empty video list, pick/record/GIF buttons. TopAppBar shows HLS icon (stream glyph) and theme toggle.
2. Tap "Pick Video" → pick any video. It appears in the list as "Pending...".
3. Tap "Start Compression". Progress updates, completes successfully.
4. Tap the completed row to play — PlayerScreen opens, plays back, back returns to MainScreen.

- [ ] **Step 4: Manual smoke test — HLS path**

With the app still open:

1. Tap the HLS icon in the TopAppBar. `HlsScreen` opens with a back arrow, codec dropdown, and "Prepare HLS" button.
2. Tap "Prepare HLS" → pick a short video. Rendition rows appear and progress.
3. Tap the system back button. `MainScreen` appears, and the HLS icon now shows a small red badge dot (because HLS is still running).
4. Tap the HLS icon again. `HlsScreen` re-opens with the same progress — the run survived navigation. Wait for it to complete.
5. Tap "Play HLS". `PlayerScreen` opens and plays the HLS output. Back → returns to `HlsScreen`. Back again → returns to `MainScreen`. Badge dot is gone.
6. Tap the HLS icon → `HlsScreen` still shows the previous succeeded status. Tap the Close (X) icon on the status card → card clears.
7. Tap the codec dropdown → select H.265 (or H.264 if 265 unsupported). Tap "Prepare HLS" → cancel mid-run via the in-card Cancel button. Status card shows "Cancelled".

If any step fails, file the issue as a follow-up. This plan is complete once the smoke test passes end-to-end.

- [ ] **Step 5: No commit**

This task makes no file changes. Nothing to commit.

---

## Self-Review Checklist

**Spec coverage:**

- [x] Navigation approach (icon → dedicated route) — Task 5
- [x] Running indicator (badge on icon) — Task 5 (step 3d)
- [x] `HlsViewModel` owns `HlsUiState`/actions/events — Tasks 2, 3
- [x] `HlsTestSession` moved to `ui/hls` — Task 1
- [x] `HlsScreen` composable with back arrow, dropdown, prepare, status card — Task 4
- [x] Shared activity-scoped VM via `LocalActivity` — Task 5 (step 2)
- [x] `MainScreen` restored (minus HLS, plus icon) — Tasks 5, 6
- [x] `MainViewModel`/`MainUiState`/`MainAction`/`MainEvent` stripped — Task 6
- [x] File moves match spec — Task 1 (HlsTestSession)
- [x] New files match spec — Tasks 2, 3, 4
- [x] Library code untouched — no task touches `lightcompressor/`
- [x] String resources — Task 4 adds `hls_back_description`, Task 5 adds `hls_icon_description`
- [x] Migration checklist — covered by Tasks 1–6
- [x] Risk: shared VM wiring — Task 7 smoke test step 3 catches it
- [x] Risk: back stack from HLS → Player — Task 7 smoke test step 5 catches it
- [ ] HlsViewModelTest — **deferred**, documented in Conventions section above

**Placeholder scan:** No TBDs, TODOs, or vague "handle errors" references. Every code block is complete.

**Type consistency:** `HlsUiState(hlsCodec, testState)` used consistently in Tasks 2, 3, 4, 5. `HlsAction` variants (`SetCodec`, `PickVideo`, `StartPreparation`, `CancelPreparation`, `CloseTestState`) match between Task 2 definition and Task 3/4 usage. `HlsEvent.LaunchPicker` matches between Tasks 2 and 4. `onNavigateToHls`, `onBack`, `onNavigateToPlayer` callback names match between Tasks 4 and 5.
