# HLS Screen Separation — Design

**Date:** 2026-04-13
**Status:** Draft
**Related:** `docs/specs/2026-04-12-hls-test-affordance-design.md`

## Problem

The HLS test affordance was added directly inside `MainScreen` and `MainViewModel`. This tangles two unrelated responsibilities — video compression (primary product) and HLS preparation (library test tool) — in a single screen and a single ViewModel. It also consumes vertical space on the main screen that the video list needs.

## Goal

- Restore `MainScreen` and `MainViewModel` to their pre-HLS shape (compression-only).
- Move HLS into a dedicated screen reached from a TopAppBar icon on `MainScreen`.
- Keep HLS preparation running while the user navigates back to the compression screen.

## Non-Goals

- No changes to `lightcompressor/` library code (`HlsPreparer`, `HlsConfig`, `HlsLadder`, `PlaylistGenerator`, etc.).
- No changes to the player route or Media3 playback.
- No new analytics events. HLS preparation is not currently tracked and this refactor does not add tracking.
- No new Compose UI tests. The project does not have a UI test suite and this refactor does not change runtime behavior.

## Navigation Approach

Add a dedicated route `LceRoute.Hls` reached from a new icon button in `MainScreen`'s `TopAppBar`.

### Why an icon (not tabs or bottom nav)

HLS in this app is a **test affordance for the library**, not a peer feature to compression. Putting it behind an icon:

- Preserves vertical space on the main flow.
- Frames HLS as a secondary tool, which matches its product role.
- Leaves room to promote it to a tab later if it becomes user-facing.

### Icon and indicator

- Material icon: `Icons.Default.Stream` (or similar, finalised during implementation).
- Placed in `TopAppBar` actions, left of the existing theme toggle.
- When `HlsTestState.isRunning == true`, render a small badge dot on the icon so users can tell something is in progress after navigating away.
- Back press on `HlsScreen` always navigates; the HLS run keeps going because the `HlsViewModel` is activity-scoped.

## State and ViewModel Split

New `HlsViewModel: AndroidViewModel`, activity-scoped alongside `MainViewModel`.

### `HlsViewModel` owns

- `uiState: StateFlow<HlsUiState>` where `HlsUiState(hlsCodec: Codec, testState: HlsTestState?)`.
- `events: SharedFlow<HlsEvent>` with a single `HlsEvent.LaunchPicker`.
- Actions via `sealed interface HlsAction`:
  - `SetCodec(Codec)`
  - `PickVideo`
  - `StartPreparation(Uri)`
  - `CancelPreparation`
  - `CloseTestState`
- Direct ownership of `HlsPreparer` lifecycle (start/cancel) and the `HlsTestSession` listener.
- `onCleared()` cancels an in-flight `HlsPreparer` run.

### `MainViewModel` loses

- `_hlsTestState` field and the `combine` operator on `uiState`.
- All HLS-related fields from `MainUiState` (`hlsCodec`, `hlsTestState`).
- All HLS-related `MainAction`s (`SetHlsCodec`, `PickHlsVideo`, `StartHlsPreparation`, `CancelHlsPreparation`, `CloseHlsTestState`).
- `MainEvent.LaunchHlsPicker`.
- Handler methods: `handleSetHlsCodec`, `handlePickHlsVideo`, `handleStartHlsPreparation`, `handleCancelHlsPreparation`, `handleCloseHlsTestState`.
- HLS-related cleanup in `onCleared()`.

### Shared

- The `Codec` enum remains in `com.davotoula.lce.ui.main` (both ViewModels reference it). If the file is only the enum, it can stay; if it's tightly coupled to main state, it can be moved to a neutral package in a follow-up. **Decision:** keep `Codec` where it is for this refactor.
- `VideoSettingsPreferences.saveHlsCodec()` stays in place. `HlsViewModel` reads and writes HLS codec; `MainViewModel` stops touching it.

### Scoping subtlety

Using `viewModel()` inside each `composable { }` block gives each destination its own VM, which would cancel HLS on back-navigate. To keep HLS running across navigation, both `MainScreen` and `HlsScreen` must share an **activity-scoped** `HlsViewModel`.

Cleanest path: obtain `HlsViewModel` once in `LceNavHost` (scoped to the host activity) and pass it down to both composables. `MainScreen` uses it only to read `testState.isRunning` for the badge; `HlsScreen` owns the full UI.

## UI Composition

### `MainScreen` (restored to pre-HLS shape, plus one addition)

- **Removed:**
  - `HlsControlsRow` from the bottom buttons column.
  - `HlsTestStatusCard` from the scrollable content.
  - `hlsVideoPickerLauncher` and the `LaunchHlsPicker` event collection branch.
- **Added:**
  - `IconButton` in `TopAppBar` actions, shown with a badge dot when HLS is running, that calls `onNavigateToHls`.
- **Otherwise unchanged:** settings card, video list, start/pick/record/pick-gif buttons, version info.

### New `HlsScreen`

- `TopAppBar` with title "HLS Preparation" and a back arrow that calls `onBack` (→ `navController.popBackStack()`).
- Scrollable content column containing:
  - Codec dropdown (the existing `ExposedDropdownMenuBox`, re-housed).
  - "Prepare HLS" primary button (full-width).
  - `HlsTestStatusCard` showing rendition rows, progress, and contextual cancel/play/close buttons.
- Owns its own `hlsVideoPickerLauncher` and observes `HlsEvent.LaunchPicker`.
- Uses the shared activity-scoped `HlsViewModel` passed from `LceNavHost`.
- Play action: when the user taps "Play" on a succeeded run, `HlsScreen` calls the same `onNavigateToPlayer` callback currently used by `MainScreen`.

### Composables that move

These move from `MainScreen.kt` into `HlsScreen.kt` as private composables:

- `HlsControlsRow`
- `HlsTestStatusCard`
- `HlsRenditionRow`
- `HLS_PERCENT_DIVISOR` constant

### File moves

- `app/src/main/java/com/davotoula/lce/hls/HlsTestSession.kt` → `app/src/main/java/com/davotoula/lce/ui/hls/HlsTestSession.kt`
- `app/src/test/java/com/davotoula/lce/hls/HlsTestSessionTest.kt` → `app/src/test/java/com/davotoula/lce/ui/hls/HlsTestSessionTest.kt`
- Package declaration updated from `com.davotoula.lce.hls` to `com.davotoula.lce.ui.hls`.

### New files

- `app/src/main/java/com/davotoula/lce/ui/hls/HlsScreen.kt`
- `app/src/main/java/com/davotoula/lce/ui/hls/HlsUiState.kt` (holds `HlsUiState`, `HlsAction`, `HlsEvent`, and moves `HlsRenditionState`, `HlsRenditionStatus`, `HlsTerminal`, `HlsTestState` from `MainUiState.kt`)
- `app/src/main/java/com/davotoula/lce/ui/hls/HlsViewModel.kt`

## Navigation Graph Changes

### `LceRoute`

Add:

```kotlin
data object Hls : LceRoute("hls")
```

### `LceNavHost`

- Obtain a shared `HlsViewModel` scoped to the host activity.
- Pass it (plus an `onNavigateToHls` callback) to `MainScreen`.
- Add a new `composable(LceRoute.Hls.route)` that hosts `HlsScreen`, passing the shared `HlsViewModel`, an `onBack` callback (`popBackStack`), and the existing `onNavigateToPlayer` callback.

## Testing

- **`HlsViewModelTest`** (new) — mirrors `HlsTestSessionTest` conventions. Covers:
  - Codec selection writes through to `VideoSettingsPreferences`.
  - `StartPreparation` seeds rendition rows and sets `isRunning = true`.
  - `CancelPreparation` calls `HlsPreparer.cancel()`.
  - `CloseTestState` is a no-op while `isRunning`.
  - `onCleared()` cancels an active run.
  - Mock `HlsPreparer` (static) via MockK as per project conventions.
- **`HlsTestSessionTest`** — package rename only, tests unchanged.
- **`MainViewModelTest`** — if it exists, strip any HLS assertions; otherwise no change.
- **Library tests** — no changes.

## Migration Checklist

1. Create `com.davotoula.lce.ui.hls` package.
2. Move `HlsTestSession.kt` + test, update packages.
3. Move HLS state types (`HlsRenditionState`, `HlsRenditionStatus`, `HlsTerminal`, `HlsTestState`) from `MainUiState.kt` into new `HlsUiState.kt`.
4. Add `HlsUiState`, `HlsAction`, `HlsEvent` in `HlsUiState.kt`.
5. Create `HlsViewModel.kt` with all HLS handlers ported from `MainViewModel`.
6. Create `HlsScreen.kt` with `HlsControlsRow`, `HlsTestStatusCard`, `HlsRenditionRow`, picker launcher, and `HlsEvent` collection ported from `MainScreen`.
7. Strip HLS fields from `MainUiState`, HLS actions from `MainAction`, `LaunchHlsPicker` from `MainEvent`.
8. Strip HLS handlers, `_hlsTestState`, and `combine` operator from `MainViewModel`.
9. Remove HLS composables and launcher from `MainScreen`.
10. Add HLS icon + optional badge to `MainScreen` `TopAppBar`.
11. Add `LceRoute.Hls`, `composable(LceRoute.Hls.route)`, and shared-VM wiring in `LceNavHost`.
12. Add `HlsViewModelTest`.
13. Run `./gradlew testDebugUnitTest ktlintFormat detekt assembleDebug`.

## Risks and Mitigations

- **Activity-scoped ViewModel sharing.** If the shared-VM wiring is wrong, HLS will cancel on back-navigate. Mitigation: verify with a manual smoke test (start HLS, press back, re-enter, confirm progress continued).
- **Strings.** HLS string resources are already in `strings.xml` and stay shared. No rename needed.
- **Icon drift.** The chosen Material icon may not be immediately obvious as "HLS". Mitigation: add `contentDescription = "HLS preparation"` and rely on the badge for running feedback. Icon can be revisited in a follow-up if users find it unclear.
- **Play-from-HLS-screen back stack.** Tapping Play from `HlsScreen` navigates to the player; back should pop to `HlsScreen`, not `MainScreen`. Default Navigation Compose behaviour already handles this.
