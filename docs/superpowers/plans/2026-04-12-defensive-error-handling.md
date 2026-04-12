# Defensive Error Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the transcoding pipeline with descriptive early failures instead of generic RuntimeExceptions.

**Architecture:** Add defensive guards around MediaCodec, EGL, and surface operations in `Transcoder.kt` and `OutputSurface.kt`. Each guard catches a specific failure and returns a descriptive `failure()` message. Tests use subclass overrides to simulate failure scenarios.

**Tech Stack:** Kotlin, Android MediaCodec, OpenGL ES, JUnit 4, MockK

---

### Task 1: Surface frame timeout — increase timeout and add retries

**Files:**
- Modify: `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/video/OutputSurface.kt:65-84`

- [ ] **Step 1: Update awaitNewImage with retry loop and longer timeout**

Replace the `awaitNewImage()` method:

```kotlin
fun awaitNewImage() {
    val timeOutMs = 2500L
    val maxAttempts = 3
    synchronized(mFrameSyncObject) {
        var attempts = 0
        while (!mFrameAvailable) {
            try {
                mFrameSyncObject.wait(timeOutMs)
                if (!mFrameAvailable) {
                    attempts++
                    if (attempts >= maxAttempts) {
                        throw RuntimeException(
                            "Surface frame wait timed out after ${maxAttempts * timeOutMs}ms"
                        )
                    }
                }
            } catch (ie: InterruptedException) {
                throw RuntimeException(ie)
            }
        }
        mFrameAvailable = false
    }
    mTextureRender?.checkGlError("before updateTexImage")
    mSurfaceTexture?.updateTexImage()
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :lightcompressor:assemble`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/video/OutputSurface.kt
git commit -m "fix: increase surface frame timeout to 2500ms with 3 retries"
```

---

### Task 2: Guard H.265 encoder creation

**Files:**
- Modify: `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt:515-549`

- [ ] **Step 1: Write failing test for H.265 encoder creation failure**

Add to `TranscoderTest.kt`:

```kotlin
@Test
fun createEncoderGuarded_h265_returnsFailureOnException() {
    val transcoder = object : Transcoder(VideoCodec.H265, context, uri, baseRequest()) {
        override fun isDecoderAvailable(mime: String) = true
        override fun createEncoderGuarded(format: MediaFormat): MediaCodec? = null
    }
    val result = transcoder.transcode()
    assertFalse(result.success)
    assertTrue(result.failureMessage!!.contains("H.265"))
    assertTrue(result.failureMessage!!.contains("H.264"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.compressor.TranscoderTest.createEncoderGuarded_h265_returnsFailureOnException"`
Expected: FAIL — `createEncoderGuarded` method does not exist

- [ ] **Step 3: Extract encoder creation into overridable method and add guard**

In `Transcoder.kt`, replace the private `createEncoder` method with an `internal open` method `createEncoderGuarded` that returns `MediaCodec?` (null on failure), and update `transcode()` to check the result:

In `transcode()`, replace lines 97-100:
```kotlin
            val encoderOrNull = createEncoderGuarded(encoderFormat)
                ?: return failure(
                    "Failed to create ${codec.name} encoder. " +
                    if (codec == VideoCodec.H265) "Try using H.264 instead." else "The device may not support this encoding format."
                )
            encoder = encoderOrNull
            inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()
```

Replace the `createEncoder` method:
```kotlin
internal open fun createEncoderGuarded(outputFormat: MediaFormat): MediaCodec? {
    return try {
        createEncoder(outputFormat)
    } catch (e: Exception) {
        Log.w(TAG, "Encoder creation failed: ${e.message}", e)
        null
    }
}

private fun createEncoder(outputFormat: MediaFormat): MediaCodec {
    return when (codec) {
        VideoCodec.H264 -> {
            val hasQTI = hasQTI()
            var encoder = if (hasQTI) {
                try {
                    MediaCodec.createByCodecName("c2.android.avc.encoder")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create c2.android.avc.encoder, falling back to generic", e)
                    MediaCodec.createEncoderByType(VideoCodec.H264.mimeType)
                }
            } else {
                MediaCodec.createEncoderByType(VideoCodec.H264.mimeType)
            }
            try {
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to configure encoder, falling back to generic encoder", e)
                encoder = MediaCodec.createEncoderByType(VideoCodec.H264.mimeType)
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            encoder
        }
        VideoCodec.H265 -> {
            val encoder = MediaCodec.createEncoderByType(VideoCodec.H265.mimeType)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.compressor.TranscoderTest.createEncoderGuarded_h265_returnsFailureOnException"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt
git add lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/compressor/TranscoderTest.kt
git commit -m "fix: guard encoder creation with descriptive failure message"
```

---

### Task 3: Guard decoder creation

**Files:**
- Modify: `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt:102-105`
- Modify: `lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/compressor/TranscoderTest.kt`

- [ ] **Step 1: Write failing test for decoder creation failure**

Add to `TranscoderTest.kt`:

```kotlin
@Test
fun transcode_returnsFailure_whenDecoderCreationFails() {
    val transcoder = object : Transcoder(VideoCodec.H264, context, uri, baseRequest()) {
        override fun isDecoderAvailable(mime: String) = true
        override fun createEncoderGuarded(format: MediaFormat): MediaCodec? = mockk(relaxed = true)
        override fun createDecoderGuarded(mime: String, format: MediaFormat, surface: android.view.Surface?): MediaCodec? = null
    }
    val result = transcoder.transcode()
    assertFalse(result.success)
    assertTrue(result.failureMessage!!.contains("decoder"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.compressor.TranscoderTest.transcode_returnsFailure_whenDecoderCreationFails"`
Expected: FAIL — `createDecoderGuarded` method does not exist

- [ ] **Step 3: Extract decoder creation into overridable method with guard**

In `Transcoder.kt`, add a new method and update `transcode()`.

Replace lines 102-105 in `transcode()`:
```kotlin
            outputSurface = OutputSurface()
            val decoderOrNull = createDecoderGuarded(sourceMime, inputFormat, outputSurface.getSurface())
                ?: return failure("Failed to create decoder for $sourceMime. The device may not have a free decoder instance.")
            decoder = decoderOrNull
            decoder.start()
```

Add the new method:
```kotlin
internal open fun createDecoderGuarded(mime: String, format: MediaFormat, surface: android.view.Surface?): MediaCodec? {
    return try {
        val dec = MediaCodec.createDecoderByType(mime)
        dec.configure(format, surface, null, 0)
        dec
    } catch (e: Exception) {
        Log.w(TAG, "Decoder creation/configuration failed for $mime: ${e.message}", e)
        null
    }
}
```

This also handles **Task 4 (decoder configure failure)** since both `createDecoderByType` and `configure` are inside the same try-catch.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.compressor.TranscoderTest.transcode_returnsFailure_whenDecoderCreationFails"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt
git add lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/compressor/TranscoderTest.kt
git commit -m "fix: guard decoder creation and configuration with descriptive failure"
```

---

### Task 4: Guard EGL/InputSurface setup

**Files:**
- Modify: `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt` (transcode method)
- Modify: `lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/compressor/TranscoderTest.kt`

- [ ] **Step 1: Write failing test for InputSurface failure**

Add to `TranscoderTest.kt`:

```kotlin
@Test
fun transcode_returnsFailure_whenInputSurfaceSetupFails() {
    val mockEncoder = mockk<MediaCodec>(relaxed = true)
    every { mockEncoder.createInputSurface() } throws RuntimeException("unable to get EGL14 display")
    val transcoder = object : Transcoder(VideoCodec.H264, context, uri, baseRequest()) {
        override fun isDecoderAvailable(mime: String) = true
        override fun createEncoderGuarded(format: MediaFormat): MediaCodec? = mockEncoder
        override fun createDecoderGuarded(mime: String, format: MediaFormat, surface: android.view.Surface?): MediaCodec? = mockk(relaxed = true)
    }
    val result = transcoder.transcode()
    assertFalse(result.success)
    assertTrue(result.failureMessage!!.contains("EGL") || result.failureMessage!!.contains("surface") || result.failureMessage!!.contains("display"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.compressor.TranscoderTest.transcode_returnsFailure_whenInputSurfaceSetupFails"`
Expected: FAIL — the RuntimeException is caught by the top-level catch but with generic message

- [ ] **Step 3: Wrap InputSurface construction with descriptive catch**

In `transcode()`, wrap the InputSurface + encoder start block:

```kotlin
            encoder = encoderOrNull
            try {
                inputSurface = InputSurface(encoder.createInputSurface())
                inputSurface.makeCurrent()
            } catch (e: Exception) {
                return failure("EGL/OpenGL setup failed: ${e.message}")
            }
            encoder.start()
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.compressor.TranscoderTest.transcode_returnsFailure_whenInputSurfaceSetupFails"`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt
git add lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/compressor/TranscoderTest.kt
git commit -m "fix: wrap EGL/InputSurface setup with descriptive failure message"
```

---

### Task 5: Improve encoder output buffer null message

**Files:**
- Modify: `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt:310-312`

- [ ] **Step 1: Update the error message**

In `drivePipeline`, replace line 311:
```kotlin
                            throw RuntimeException("Encoder output buffer $encoderStatus was null")
```
with:
```kotlin
                            throw RuntimeException(
                                "Encoder produced null output buffer (index=$encoderStatus). The device encoder may be in a bad state."
                            )
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :lightcompressor:assemble`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt
git commit -m "fix: improve encoder null output buffer error message"
```

---

### Task 6: Improve unexpected codec status messages

**Files:**
- Modify: `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt:225-378`

- [ ] **Step 1: Pass sourceMime into drivePipeline**

Update `drivePipeline` signature to accept `sourceMime: String`:

```kotlin
private fun drivePipeline(
    videoExtractor: MediaExtractor,
    decoder: MediaCodec,
    outputSurface: OutputSurface,
    encoder: MediaCodec,
    inputSurface: InputSurface,
    muxer: MediaMuxer,
    audioTrack: AudioTrackInfo?,
    sourceMime: String
)
```

Update the call site in `transcode()` to pass `sourceMime`:
```kotlin
            drivePipeline(
                videoExtractor = videoExtractor,
                decoder = decoder,
                outputSurface = outputSurface,
                encoder = encoder,
                inputSurface = inputSurface,
                muxer = muxer,
                audioTrack = audioTrackInfo,
                sourceMime = sourceMime
            )
```

- [ ] **Step 2: Update the encoder status error message**

Replace line 333-334:
```kotlin
                        else -> {
                            throw RuntimeException("Unexpected encoder status: $encoderStatus")
                        }
```
with:
```kotlin
                        else -> {
                            throw RuntimeException(
                                "Unexpected encoder status $encoderStatus during ${codec.name} transcoding"
                            )
                        }
```

- [ ] **Step 3: Update the decoder status error message**

Replace line 350-352:
```kotlin
                        decoderStatus < 0 -> {
                            throw RuntimeException("Unexpected decoder status: $decoderStatus")
                        }
```
with:
```kotlin
                        decoderStatus < 0 -> {
                            throw RuntimeException(
                                "Unexpected decoder status $decoderStatus while decoding $sourceMime"
                            )
                        }
```

- [ ] **Step 4: Build to verify compilation**

Run: `./gradlew :lightcompressor:assemble`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt
git commit -m "fix: add codec context to unexpected status error messages"
```

---

### Task 7: Run full test suite and verify build

**Files:** None (verification only)

- [ ] **Step 1: Run all tests**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 2: Verify no regressions in existing tests**

Check that all existing `TranscoderTest` tests still pass:

Run: `./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.compressor.TranscoderTest" -i`
Expected: All tests PASS
