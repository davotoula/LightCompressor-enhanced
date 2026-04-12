# Defensive Error Handling for Transcoding Pipeline

**Date:** 2026-04-12
**Branch:** claude/fix-video-codec-error-Df0Ig

## Goal

Harden the transcoding pipeline so that failure paths produce descriptive error messages via `onFailure` rather than bubbling up as generic `RuntimeException`s. All 7 items below are already caught by the top-level try-catch in `Transcoder.transcode()` — the app doesn't crash — but the error messages are cryptic and some failures can be prevented or retried.

## Existing Work

The branch already adds a decoder availability check in `Transcoder.transcode()` that returns a descriptive failure for unsupported source codecs (e.g., `video/divx`).

## Changes

### 1. Surface frame timeout (OutputSurface)

**File:** `OutputSurface.kt:65-84`
**Problem:** 100ms timeout is aggressive. On slower devices or complex codecs, the frame may not arrive in time. Throws `RuntimeException("Surface frame wait timed out")`.
**Fix:** Increase timeout to 2500ms and add a retry loop (up to 3 waits) before giving up. When it does fail, include the timeout duration in the message for diagnostics.

### 2. H.265 encoder creation failure (Transcoder)

**File:** `Transcoder.kt:543-548`
**Problem:** `createEncoderByType(H265.mimeType)` can throw `IOException` if no encoder is available. The H.264 path has QTI fallback logic; the H.265 path has none.
**Fix:** Wrap the H.265 encoder creation + configure in try-catch. On failure, return `failure("Failed to create H.265 encoder: ${e.message}. Try using H.264 instead.")`.

### 3. Decoder creation failure (Transcoder)

**File:** `Transcoder.kt:103`
**Problem:** `createDecoderByType` can throw even after `isDecoderAvailable` returns true (e.g., all codec instances are in use on the device).
**Fix:** Wrap in try-catch, return descriptive failure: `"Failed to create decoder for $sourceMime: ${e.message}"`.

### 4. Decoder configure failure (Transcoder)

**File:** `Transcoder.kt:104`
**Problem:** `configure()` can throw `MediaCodec.CodecException` for format incompatibilities not caught by the decoder availability check.
**Fix:** Wrap in try-catch, return descriptive failure: `"Failed to configure decoder for $sourceMime: ${e.message}"`.

### 5. EGL setup failures (InputSurface)

**File:** `InputSurface.kt:23-76`
**Problem:** Multiple `RuntimeException` throws with messages like "unable to get EGL14 display" that bubble up as generic transcoding failures.
**Fix:** Wrap EGL errors with a prefix so they're identifiable: catch `RuntimeException` in Transcoder around `InputSurface` construction and return `failure("EGL/OpenGL setup failed: ${e.message}")`.

### 6. Null encoder output buffer (Transcoder drivePipeline)

**File:** `Transcoder.kt:310-312`
**Problem:** Throws `RuntimeException("Encoder output buffer $encoderStatus was null")` — technically informative but could be more descriptive.
**Fix:** Change to a more descriptive message: `"Encoder produced null output buffer (index=$encoderStatus). The device encoder may be in a bad state."`.

### 7. Unexpected codec status codes (Transcoder drivePipeline)

**File:** `Transcoder.kt:332-334, 350-352`
**Problem:** Throws `RuntimeException("Unexpected encoder/decoder status: $status")`.
**Fix:** Improve messages to include codec context: `"Unexpected encoder status $encoderStatus during ${codec.name} transcoding"` and `"Unexpected decoder status $decoderStatus while decoding $sourceMime"`. Pass `sourceMime` into `drivePipeline` for this.

## Testing

- Extend `TranscoderTest` with unit tests for each new guard path using subclass overrides (same pattern as the existing `isDecoderAvailable` test).
- Build verification: `./gradlew assembleDebug testDebugUnitTest`.

## Scope

Library changes only (`lightcompressor/`). No app-level changes needed — the app already handles `onFailure` messages correctly.
