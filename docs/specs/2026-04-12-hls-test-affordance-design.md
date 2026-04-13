# HLS Test Affordance — Design

**Date:** 2026-04-12
**Status:** Approved (brainstorming phase)
**Related:** `docs/specs/2026-04-12-hls-preparation-design.md`,
`docs/specs/2026-04-12-hls-preparation-followups.md`

## Goal

Add a manual test affordance to the sample app that exercises the new
`HlsPreparer` API end-to-end against a user-picked local video, then
plays the resulting HLS bundle back via the existing Media3-based
`PlayerScreen`. The intent is exploratory testing of the new API on a
real device, not a production HLS feature.

## Architecture & scope

A new isolated path in the sample app, separate from the existing
multi-video compression queue.

**In scope:**

- One new bottom-bar button on `MainScreen`: **"Prepare HLS"**
- One codec dropdown above the button (H.264 / H.265), persisted in
  `VideoSettingsPreferences` like the existing settings
- Single-select photo picker invocation, separate from the existing
  multi-video pick flow
- Output to `context.filesDir/hls/current/`, wiped before every run
  (latest-only, no run history)
- New listener implementation `HlsTestSession` that persists segments
  and playlists to disk
- New per-rendition status card on `MainScreen` with rows like
  `360p ✓`, `720p ⏳ 47% (3 segments)`
- New "Play HLS" button on the card after completion that navigates
  to the existing `PlayerScreen` with a `file://` URI to
  `master.m3u8`
- New `media3-exoplayer-hls` dependency so ExoPlayer can play the
  result

**Out of scope:**

- The existing multi-video compression queue is untouched
- No HLS settings panel beyond the codec dropdown (the default ladder,
  6-second segments, and audio enabled are baked in)
- No history of past runs
- No instrumented test
- No parallel encoding implementation — but the per-rendition row
  layout is forward-compatible if/when it is added (deferred
  follow-up #8)

## Components & data flow

### State

A new field `hlsTestState: HlsTestState? = null` is added to
`MainUiState`. `null` means the card is hidden.

```kotlin
data class HlsTestState(
    val isRunning: Boolean = false,
    val renditions: List<HlsRenditionState> = emptyList(),
    val terminal: HlsTerminal? = null,
)

data class HlsRenditionState(
    val label: String,                   // "360p", "720p", ...
    val status: Status,                  // Pending | Active | Complete | Failed
    val progressPercent: Int,            // 0-100, only meaningful when Active/Complete
    val segmentCount: Int,
)

sealed interface HlsTerminal {
    data class Succeeded(val masterPlaylistPath: String) : HlsTerminal
    data class Failed(val message: String) : HlsTerminal
    data object Cancelled : HlsTerminal
}
```

The codec choice lives in `VideoSettingsPreferences` as a new
`hlsCodec: VideoCodec` field, persisted via DataStore the same way the
existing settings are.

### MainViewModel additions

- `onPickHlsVideoTapped()` — emits an event the screen consumes to
  launch a single-select photo picker
- `startHlsPreparation(uri: Uri)` — wipes `filesDir/hls/current/`,
  builds an `HlsConfig` with the persisted codec choice, creates an
  `HlsTestSession` listener, calls `HlsPreparer.start()`, stores the
  returned `Job`, and seeds `hlsTestState` with the rendition rows
- `cancelHlsPreparation()` — calls `HlsPreparer.cancel()`
- `onHlsTestStateClosed()` — clears `hlsTestState` (the ✕ button on
  the card; only enabled when not running)

### HlsTestSession (new class in app module)

A `HlsListener` implementation that persists segments and playlists
to disk so the in-memory result is playable. Output layout:

```
filesDir/hls/current/
├── master.m3u8                  # written in onComplete
├── 360p/
│   ├── init.mp4                 # copied in onSegmentReady when isInitSegment=true
│   ├── media.m3u8               # written in onRenditionComplete
│   ├── segment_000.m4s          # copied in onSegmentReady
│   ├── segment_001.m4s
│   └── ...
├── 540p/...
└── ...
```

This layout matches the relative paths the playlists already contain
(`#EXT-X-MAP:URI="init.mp4"`, `segment_000.m4s`,
`360p/media.m3u8`), so no path rewriting is needed.

**Critical:** `HlsTranscoder.emitSegment` deletes the temp segment
file immediately after `onSegmentReady` returns. The listener's
`onSegmentReady` must therefore copy the file synchronously (a simple
`inputStream.copyTo(outputStream)` to the persistent target path)
before returning.

### Callback to state mapping

| Callback                                | State change                                                                                              |
| --------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| `onStart(renditionCount)`               | Seed `renditions` with `Pending` rows (one per ladder entry); `isRunning = true`                          |
| `onRenditionStart(rendition)`           | That rendition's row → `Active`, `progressPercent = 0`                                                    |
| `onProgress(rendition, percent)`        | That rendition's `progressPercent = percent`                                                              |
| `onSegmentReady(rendition, segment)`    | Copy file to persistent location; that rendition's `segmentCount += 1`                                    |
| `onRenditionComplete(rendition, m3u8)`  | Write `media.m3u8`; that rendition's row → `Complete`                                                     |
| `onComplete(masterPlaylist)`            | Write `master.m3u8`; `isRunning = false`; `terminal = Succeeded(masterPlaylistPath)`                      |
| `onFailure(error)`                      | Mark any rendition still `Active` as `Failed`; `isRunning = false`; `terminal = Failed(error.message)`   |
| `onCancelled()`                         | `isRunning = false`; `terminal = Cancelled`                                                               |

### Threading

`HlsListener` callbacks run on two different dispatchers (per
`HlsListener` KDoc):

- `onSegmentReady` and `onProgress` → `Dispatchers.Default` (worker)
- `onStart`, `onRenditionStart`, `onRenditionComplete`, `onComplete`,
  `onCancelled`, `onFailure` → `Dispatchers.Main`

Disk I/O inside `onSegmentReady` therefore runs on a worker thread
already — no jank risk from copying segment files synchronously.

State updates from any callback go through `MutableStateFlow.update`,
which is thread-safe (atomic CAS), so the listener never needs an
explicit `withContext(Dispatchers.Main)` swap for the state mutation
itself. Compose collectors on Main will observe the new value on the
next frame.

## UI placement & playback wiring

### Bottom button bar (MainScreen)

Currently has Pick / Record buttons. Add a new row above the existing
row containing:

- An `ExposedDropdownMenuBox` labelled "HLS codec" (H.264 / H.265),
  bound to the persisted `hlsCodec` setting
- A primary button **"Prepare HLS"** that emits the photo-picker event

The HLS controls live in their own row to keep the existing button bar
visually unchanged.

### Status card

When `hlsTestState != null`, render a `Card` between the video list
and the bottom buttons:

- Header: `"HLS preparation"` + a small ✕ icon
  (calls `onHlsTestStateClosed`, only enabled when not running)
- One row per rendition: `Text(label)` + status glyph +
  `LinearProgressIndicator` (only shown when `Active` or `Complete`)
  + segment count
- Status glyphs: `—` (Pending), small `CircularProgressIndicator`
  (Active), ✓ (Complete), ✕ (Failed)
- Bottom of the card:
    - While running: `OutlinedButton("Cancel")` →
      `cancelHlsPreparation()`
    - On `Succeeded`: `Button("Play HLS")` → navigates to
      `PlayerScreen` with the master playlist URI; plus a small
      caption showing the output directory path
    - On `Failed`: red caption with the error message
    - On `Cancelled`: gray caption `"Cancelled"`

### Playback wiring

`PlayerScreen` already takes a video URI and constructs a Media3
`MediaItem`. Two changes:

1. Add `media3-exoplayer-hls` to the version catalog and the existing
   `media3` bundle in `app/build.gradle`, so ExoPlayer auto-detects
   HLS from the `.m3u8` extension and uses its HLS source.
2. Pass `Uri.fromFile(File(filesDir, "hls/current/master.m3u8")).toString()`
   through the existing nav route. No changes to `PlayerScreen` itself
   — ExoPlayer handles the rest.

## Error handling

- `onFailure` from `HlsListener` → `terminal = Failed(error.message)`.
  Surfaced in the card. No toast (the card is already visible).
- I/O errors during segment copy in `HlsTestSession.onSegmentReady` →
  catch, log, set `terminal = Failed(...)`, and call
  `HlsPreparer.cancel()`. A missing segment makes the playlist
  unplayable anyway, so failing the run is the right behaviour.
- App backgrounded mid-encode: `HlsPreparer` runs on its own coroutine
  scope so it survives recomposition. The viewmodel's job reference is
  enough to keep the run alive.
- Tapping "Prepare HLS" while a run is in progress: button is disabled
  when `hlsTestState?.isRunning == true`.
- Tapping "Prepare HLS" while a normal compression is in progress: not
  blocked — they are independent flows.

### Out-of-scope edge cases

- Process death mid-encode (state lost — user re-runs)
- Disk full (the OS error surfaces via `onFailure` naturally)
- Source video uses an HDR or unsupported codec (already handled by
  `HlsTranscoder` returning null per rendition; surfaces as Failed
  rendition rows in the card)

## Verification

Manual checklist for the implementer to confirm the test affordance
works once built:

1. Build the app on a device, pick a short video (a few seconds is
   enough), tap **Prepare HLS** with H.264 selected. Expect: status
   card shows all 5 default ladder rows, `360p` becomes Active,
   progress increments, segment count climbs, then ✓; repeat through
   to `4K` (or whichever rendition the source resolution supports).
2. On completion, tap **Play HLS**. `PlayerScreen` opens and plays the
   master playlist with adaptive switching across renditions.
3. Run a longer source to verify `Cancel` mid-run actually stops
   encoding within ~1 second and the card shows `Cancelled`.
4. Switch the codec dropdown to H.265, run again. Verify the H.265
   path works and that the choice persists across an app kill.
5. `adb pull /data/data/com.davotoula.lce/files/hls/current /tmp/hls-out`
   and inspect the layout matches the spec (master.m3u8 at root,
   per-rendition subdirs with init.mp4 + segment_*.m4s + media.m3u8).
6. Try a source video the device's encoder can't handle (e.g. weird
   codec). Verify the run completes with some renditions Failed and
   the rest Succeeded — or, if all fail, that the card shows a clear
   failure message instead of crashing.

## Files changed / created

- **New** `app/src/main/java/com/davotoula/lce/hls/HlsTestSession.kt`
  — `HlsListener` impl that persists segments and playlists to
  `filesDir/hls/current/`
- **Modified** `app/src/main/java/com/davotoula/lce/ui/main/MainViewModel.kt`
  — adds `hlsTestState` field handling, `startHlsPreparation` /
  `cancelHlsPreparation` / `onHlsTestStateClosed` methods, photo-picker
  event for HLS
- **Modified** `app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt`
  — adds `HlsTestState`, `HlsRenditionState`, `HlsTerminal` types and
  the new `hlsTestState` field
- **Modified** `app/src/main/java/com/davotoula/lce/ui/main/MainScreen.kt`
  — adds the codec dropdown, "Prepare HLS" button, status card, and
  HLS photo-picker launcher
- **Modified** `app/src/main/java/com/davotoula/lce/data/VideoSettingsPreferences.kt`
  — adds the `hlsCodec` persisted setting
- **Modified** `gradle/libs.versions.toml` — adds
  `media3-exoplayer-hls` to the catalog and the `media3` bundle
- **Modified** `app/build.gradle` — picks up the new dependency via
  the existing `libs.bundles.media3` import
