# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

LightCompressor Enhanced — an Android video compression library. Two modules:
- **lightcompressor** — the library (published via JitPack)
- **app** — sample Jetpack Compose app demonstrating the library

## Build Commands

```bash
./gradlew assembleDebug                    # Build everything (debug)
./gradlew :lightcompressor:assemble        # Build library only
./gradlew :app:assembleDebug               # Build app only

./gradlew testDebugUnitTest                # Run all unit tests
./gradlew :lightcompressor:testDebugUnitTest  # Run library tests only

# Run a single test class
./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.config.VideoResizerTest"
```

CI runs: `./gradlew assembleDebug testDebugUnitTest ktlintCheck detekt` (JDK 17).

## Static Analysis

```bash
./gradlew ktlintCheck                     # Check Kotlin style (both modules)
./gradlew ktlintFormat                    # Auto-fix style violations
./gradlew detekt                          # Run static analysis (both modules)
./gradlew installGitHooks                 # Manually install git hooks
```

Git hooks auto-install on build. Pre-commit runs ktlint, pre-push runs detekt.

## Architecture

### Library (`lightcompressor/`)

Package: `com.davotoula.lightcompressor`

**Public API:**
- `VideoCompressor` — singleton entry point. `start()` launches compression via coroutines, `cancel()` aborts.
- `CompressionListener` — callback interface: `onStart`, `onSuccess`, `onFailure`, `onProgress`, `onCancelled`.
- `Configuration` — compression settings (quality, bitrate, codec, resizer, disableAudio).
- `VideoResizer` — functional interface for resolution control (`auto`, `scale`, `limitSize`, `matchSize`, `limitShortSide`).
- `StorageConfiguration` — where to save output (`SharedStorageConfiguration`, `AppSpecificStorageConfiguration`, `CacheStorageConfiguration`).

**Compression pipeline:**
1. `VideoCompressor.start()` → validates input, extracts metadata via `MediaMetadataRetriever`
2. `Compressor` — orchestrates: checks bitrate (2 Mbps minimum), applies `VideoResizer`, routes to transcoder
3. `Transcoder` — unified MediaCodec-based encoding (H.264/H.265) with native `MediaMuxer`, parameterized by `VideoCodec`
4. `InputSurface` / `OutputSurface` / `TextureRenderer` — OpenGL pipeline for frame processing

**HLS preparation pipeline:**
1. `HlsPreparer.start()` → extracts source metadata, filters encoding ladder
2. `HlsTranscoder` — encodes each rendition sequentially (lowest-first) using Surface/GL pipeline
3. `SegmentAccumulator` — detects keyframe-aligned segment boundaries in encoder output
4. `Mp4SegmentWriter` / `BoxWriter` — writes fMP4 init and media segments (ISO 14496-12)
5. `SegmentSink` — `MultiFileSegmentSink` (default, one file per init/segment) or `SingleFileSegmentSink` (one combined `<label>.mp4` per rendition with `#EXT-X-BYTERANGE` playlist), selected by `HlsConfig.singleFilePerRendition`
6. `PlaylistGenerator` — builds VOD m3u8 playlists as strings (`buildMediaPlaylist` for multi-file, `buildByteRangeMediaPlaylist` for single-file)
7. Segments emitted via `HlsListener.onSegmentReady()` callback; temp files deleted after callback returns. In single-file mode the listener gets exactly one callback per rendition with `HlsSegment.isCombinedRendition = true`.

**Key design decisions:**
- BPS bitrate takes precedence over Mbps when both specified in `Configuration`
- H.265 support is runtime-detected via `CompressorUtils.isHevcEncodingSupported()` (cached)
- Uses native Android `MediaMuxer` instead of third-party MP4 muxer
- `StreamableVideo` moves moov atom for progressive download support
- `limitShortSide` is preferred over `limitSize` for resolution targeting — `limitSize` creates a landscape-oriented bounding box that crushes portrait/non-standard aspect ratio videos (e.g., 1080x2400 → 480x1088). `limitShortSide` constrains `min(width, height)` which is orientation-agnostic.

**HDR handling** (HLG/PQ input, `hdr/` package, controlled by `Configuration.hdrMode: HdrMode`):
- `HdrPolicy.decide()` picks a `ColorPlan` once per job from `ColorInfo`, codec, `HdrMode`, an HLS flag and `HdrCapabilities`: `Sdr`, `KeepHdr` (H.265 only, non-HLS, BT.2020 input, an encoder with HDR editing support) or `ToneMapToSdr`. `HdrPolicy.fallbackFor()` builds the tone-map plan.
- `ColorPlan` is the single place encoder colour tags (and for `KeepHdr` the profile and static info) are set, and carries the `RenderSpec` (GLES version, bit depth, shader). Output is never tagged BT.709 unless tone mapped, and never HLG/PQ unless 10-bit.
- `GlSurfaceFactory` builds the GL pipeline for a plan. `KeepHdr` uses ES3 / 10-bit and any failure becomes `KeepHdrRejected`; `ToneMapToSdr` prefers ES3 with `GL_EXT_YUV_target` and falls back to ES2.
- Keep-HDR retry window: `Transcoder.transcode()` retries a rejected `KeepHdr` attempt exactly once as `ToneMapToSdr`, but only before `muxer.start()`. After the muxer starts a failure is a normal failure, and progress is held back until then.
- No EGL colour-space attribute is ever set on the encoder input surface: as Media3's `GlUtil` documents, BT2020 PQ/HLG window attributes make the encoder switch to full range even when configured for limited.
- `HlsTranscoder` always tone maps HDR sources (`isHls = true`).
- Dolby Vision: Android lists a DV 8.x file (iPhone HDR) as a `video/dolby-vision` track plus its HEVC base layer. `CompressorUtils.findVideoTrack` prefers the first non-DV video track (no DV decoder needed, and DV can't be encoded); profile 5 has no base layer and still fails the decoder check. `ColorInfo.forVideoTrack` fills colour keys the chosen track lacks from the DV track, so an untagged base layer never plans as SDR.
- JVM unit tests run against the stub android.jar where `Build.VERSION.SDK_INT` is 0, so tests of SDK-gated code must pass `sdkInt` explicitly (`ColorPlan.applyTo`/`matchesEncoderOutput` take it as a parameter).

### App (`app/`)

Package: `com.davotoula.lce`

Jetpack Compose UI with `MainViewModel` managing compression state. Uses Media3 for playback, Coil for thumbnails, DataStore for preferences, Firebase for analytics/crashlytics. Resolution control uses `VideoResizer.limitShortSide()` for orientation-aware resizing.

**HLS test affordance:** A "Prepare HLS" button on `MainScreen` (with an HLS codec dropdown) exercises `HlsPreparer` end-to-end against a single picked video. Output is written to `filesDir/hls/current/` (wiped before every run) by `HlsTestSession`, an `HlsListener` impl in `app/src/main/java/com/davotoula/lce/hls/`. The resulting `master.m3u8` plays back via the existing `PlayerScreen`, which uses Media3's HLS source (`media3-exoplayer-hls`).

## Testing

Library tests use JUnit 4 + MockK. Test files mirror source structure under `lightcompressor/src/test/`. Key test classes:
- `VideoResizerTest` — resolution calculation logic
- `ResolutionPipelineTest` — end-to-end resolution pipeline
- `TranscoderTest` — transcoder unit tests (H.264 and H.265)
- `NumbersUtilsTest`, `StreamableVideoTest` — utility tests
- `BoxWriterTest` — ISO BMFF box encoding
- `Mp4SegmentWriterTest` — fMP4 init and media segment structure
- `HlsConfigTest` — encoding ladder configuration
- `PlaylistGeneratorTest` — m3u8 playlist generation (multi-file + byterange)
- `PlaylistRewriterTest` — URL remapping
- `SegmentAccumulatorTest` — keyframe boundary detection
- `SegmentSinkTest` — multi-file vs single-file rendition emission
- `ColorInfoTest` — colour metadata extraction from `MediaFormat`
- `ToneMapMathTest` — tone-map curve maths
- `ColorPlanTest` — encoder colour tags per plan (passes `sdkInt`)
- `HdrPolicyTest` — keep-HDR vs tone-map decision table
- `MediaCodecHdrCapabilitiesTest` — HDR encoder discovery
- `VideoTrackSelectionTest` — Dolby Vision base-layer track selection
- `KeepHdrRejectionTest` — keep-HDR rejection and retry window
- `ShaderSourcesTest` — generated GLSL sources
- `HlsTranscoderPlanTest` — HLS always tone maps HDR
- `CodecStatusTest` — transcoders ignore `MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED` from older OMX codecs

One instrumented test, `ToneMapFallbackTest` (ES2/ES3 tone-map fallback), needs `orig2.mp4` pushed to the test app's external files dir. Run it with `installDebugAndroidTest` plus `adb shell am instrument`, not `connectedDebugAndroidTest`, which uninstalls the app and deletes its outputs.

## Dependencies

Managed via version catalog (`gradle/libs.versions.toml`). Key versions: minSdk=21, targetSdk=36, compileSdk=37.0, AGP 9.1.0, Gradle 9.8.1, Kotlin 2.4.21, Media3 1.11.1, Compose BOM 2026.09.00.

The `kotlin` catalog version sets the library's consumer floor: the published AAR carries Kotlin metadata of that minor version, and a Kotlin compiler only reads metadata one minor ahead of itself. At Kotlin 2.4.x, consumers must compile with Kotlin 2.3 or newer.