# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Fork of LightCompressor — an Android video compression library. Two modules:
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
./gradlew :lightcompressor:testDebugUnitTest --tests "com.abedelazizshe.lightcompressorlibrary.config.VideoResizerTest"
```

CI runs: `./gradlew assembleDebug testDebugUnitTest` (JDK 17).

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

Package: `com.abedelazizshe.lightcompressorlibrary`

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

**Key design decisions:**
- BPS bitrate takes precedence over Mbps when both specified in `Configuration`
- H.265 support is runtime-detected via `CompressorUtils.isHevcEncodingSupported()` (cached)
- Uses native Android `MediaMuxer` instead of third-party MP4 muxer
- `StreamableVideo` moves moov atom for progressive download support
- `limitShortSide` is preferred over `limitSize` for resolution targeting — `limitSize` creates a landscape-oriented bounding box that crushes portrait/non-standard aspect ratio videos (e.g., 1080x2400 → 480x1088). `limitShortSide` constrains `min(width, height)` which is orientation-agnostic.

### App (`app/`)

Package: `com.davotoula.lce`

Jetpack Compose UI with `MainViewModel` managing compression state. Uses Media3 for playback, Coil for thumbnails, DataStore for preferences, Firebase for analytics/crashlytics. Resolution control uses `VideoResizer.limitShortSide()` for orientation-aware resizing.

## Testing

Library tests use JUnit 4 + MockK. Test files mirror source structure under `lightcompressor/src/test/`. Key test classes:
- `VideoResizerTest` — resolution calculation logic
- `ResolutionPipelineTest` — end-to-end resolution pipeline
- `TranscoderTest` — transcoder unit tests (H.264 and H.265)
- `NumbersUtilsTest`, `StreamableVideoTest` — utility tests

## Dependencies

Managed via version catalog (`gradle/libs.versions.toml`). Key versions: minSdk=21, compileSdk=36, Kotlin 2.2.21, Media3 1.9.0, Compose BOM 2025.12.01.