# HLS Preparation — Code Review Follow-ups

Items from the final code review of the `feature/hls-preparation` branch
that were intentionally **not** addressed in the initial implementation.
Each entry has the original severity, the reviewer's finding, and the
reason for deferring.

Reviewer: superpowers:code-reviewer
Date: 2026-04-12
Branch: feature/hls-preparation

---

## IMPORTANT

### #4 — `onCancelled` callback thread mismatch with spec

**Finding:** The design spec at line 333 of
`docs/specs/2026-04-12-hls-preparation-design.md` says `onCancelled`
should be on "Default (worker)". The implementation at lines 121 and 176
of `HlsPreparer.kt` dispatches it on `Dispatchers.Main`. The
`HlsListener` doc comment at line 10 says "All other callbacks: called
on Main thread", which is itself inconsistent with the spec.

**Reviewer recommendation:** Update the **spec** to match the
implementation, since Main is the more intuitive choice for
`onCancelled` (clients typically update UI on cancellation).

**Why deferred:** This is a documentation-only correction. The
implementation is correct as-is. Track separately so the spec can be
amended in the same change as any other doc updates.

**Action:** Update `docs/specs/2026-04-12-hls-preparation-design.md`
line 333 to say `onCancelled` is dispatched on Main.

---

## MINOR

### #7 — `BoxWriter.totalSize` Int overflow for boxes > 2 GB

**Finding:** In
`lightcompressor/src/main/java/com/davotoula/lightcompressor/muxer/BoxWriter.kt`
line 121:

```kotlin
val totalSize = HEADER_SIZE + bodyBytes.size
```

Both operands are `Int`, so boxes larger than ~2 GB would overflow. The
ISO BMFF spec supports 64-bit extended sizes (largesize) for this case.

**Why deferred:** HLS segments at 6-second cadence and bitrates up to
15 Mbps are at most ~11 MB. The 2 GB ceiling is unreachable in
practice. Adding largesize support would add complexity for no
realistic benefit.

**Action:** None unless a use case for very long single-segment writes
emerges.

---

### #8 — `HlsPreparer` singleton limits concurrent usage

**Finding:** `HlsPreparer` is an `object` (Kotlin singleton) with a
single `currentJob` and `transcoder`. Calling `start()` cancels any
in-progress preparation. A client cannot prepare two videos
concurrently.

**Why deferred:** This matches the existing `VideoCompressor` pattern
in the same library and is documented behavior (the `cancel()` call at
the top of `start()`). Changing it would diverge from the established
API surface and require breaking changes. No client has asked for
concurrent preparation.

**Action:** None unless a client requests concurrent preparation. If
needed, introduce a separate non-singleton `HlsPreparerInstance` class
and keep the singleton as a thin wrapper.

---

### #9 — `PlaylistGenerator` bandwidth integer multiplication

**Finding:** In
`lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/PlaylistGenerator.kt`
line 60:

```kotlin
val bandwidthBps = r.rendition.bitrateKbps * 1000
```

`Int * Int` overflows at ~2.1 M kbps (~2.1 Gbps).

**Why deferred:** The default ladder tops out at 15 Mbps for 4K. The
~2 Gbps ceiling is ~140× higher than the highest configured rendition.
Realistic HLS bitrates are well under this threshold.

**Action:** None. If a future ladder pushes past hundreds of Mbps,
change to `r.rendition.bitrateKbps.toLong() * 1000L`.

---

### #10 — No unit tests for `HlsTranscoder` and `HlsPreparer`

**Finding:** Both classes depend on Android framework types
(`MediaCodec`, `MediaExtractor`, `MediaMetadataRetriever`,
`MediaFormat`) that are not available in JVM unit tests. Pure-logic
components (BoxWriter, Mp4SegmentWriter, SegmentAccumulator,
PlaylistGenerator, PlaylistRewriter, HlsConfig) all have unit tests.

**Why deferred:** Properly testing these classes requires either
Robolectric (heavy, slow) or instrumented tests on a device/emulator
(infrastructure not yet set up for the library). The plan deferred
this to a follow-up phase.

**Action:** Add instrumented tests in
`lightcompressor/src/androidTest/` covering at least:

- `HlsPreparer.start()` end-to-end with a small fixture video
- Cancellation mid-encode
- Single rendition failure with skip-and-continue
- Multi-rendition output produces valid m3u8 + playable segments

---

### #11 — Package structure deviation from spec

**Finding:** The original spec placed `HlsConfig.kt`, `HlsListener.kt`,
and `PlaylistRewriter.kt` at the top-level package, but the
implementation puts them in the `hls/` subpackage.

**Why deferred:** This was a deliberate refinement during planning.
Keeping all HLS types in one subpackage is cleaner. The reviewer
agreed it was a justified improvement.

**Action:** None. Update the spec to reflect the realized package
structure if it gets revised for any other reason.

---

## Items Already Addressed

For reference, the items from the same review that **were** fixed in
commit `3ddcd03 fix(hls): address code review findings`:

- **CRITICAL #1** — Audio sample `durationUs = 0` → compute AAC frame
  duration from sample rate
- **CRITICAL #2** — `MediaMetadataRetriever` resource leak on early
  return → wrap in try/finally with `runCatching`
- **IMPORTANT #3** — `onRenditionComplete` called on `Dispatchers.Default`
  → moved into `HlsPreparer` and dispatched on `Dispatchers.Main`
- **IMPORTANT #5** — Audio samples read greedily → added PTS limit to
  `copyAudioSamples` time-windowed to current video encoding position
- **IMPORTANT #6** — Audio passthrough non-functional → resolved by
  fixing #1 and #5 together
- **MINOR #12** — `HlsConfig.maxParallelEncoders` unused → removed
