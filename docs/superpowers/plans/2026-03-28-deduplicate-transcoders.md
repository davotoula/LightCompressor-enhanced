# Deduplicate Transcoders Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merge `AVCTranscoder` and `HevcTranscoder` into a single `Transcoder` class parameterized by `VideoCodec`, eliminating ~500 lines of duplicated code.

**Architecture:** A single `Transcoder` class takes `VideoCodec` as a constructor parameter. The two codec-specific concerns (encoder creation and format tuning) are handled via `when(codec)` branches in private methods. Everything else (pipeline driving, audio copying, output finalization, progress reporting) is shared unchanged.

**Tech Stack:** Kotlin, Android MediaCodec/MediaMuxer, JUnit 4 + MockK

---

## File Structure

| Action | Path | Responsibility |
|--------|------|---------------|
| Create | `lightcompressor/src/main/java/.../compressor/Transcoder.kt` | Unified transcoder — all shared pipeline logic + codec-specific branches |
| Delete | `lightcompressor/src/main/java/.../compressor/AVCTranscoder.kt` | Replaced by Transcoder |
| Delete | `lightcompressor/src/main/java/.../compressor/HevcTranscoder.kt` | Replaced by Transcoder |
| Modify | `lightcompressor/src/main/java/.../compressor/Compressor.kt:202-252` | Update `transcode()` to use `Transcoder` instead of two separate classes |
| Create | `lightcompressor/src/test/java/.../compressor/TranscoderTest.kt` | Merged tests for both codecs |
| Delete | `lightcompressor/src/test/java/.../compressor/AVCTranscoderTest.kt` | Replaced by TranscoderTest |
| Delete | `lightcompressor/src/test/java/.../compressor/HevcTranscoderTest.kt` | Replaced by TranscoderTest |

All paths under package `com.davotoula.lightcompressor`.

---

### Task 1: Create unified Transcoder class

**Files:**
- Create: `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt`

- [ ] **Step 1: Create Transcoder.kt with all shared code**

The class is a direct merge of AVCTranscoder with these changes:
- Constructor takes `VideoCodec` parameter alongside `context`, `srcUri`, `request`
- Single shared `Request` data class (identical in both originals)
- `transcode()`: uses `codec.mimeType` for `MediaFormat.createVideoFormat()`, calls `tuneEncoderFormat()` and `createEncoder()`
- `createEncoder(format)`: `when(codec)` — H264 uses QTI fallback logic from `prepareAvcEncoder()`; H265 uses simple `createEncoderByType`
- `tuneEncoderFormat(format)`: `when(codec)` — H264 sets AVC High Profile; H265 sets HEVC Main Profile + vendor B-frame hints
- `TAG` derived from codec: `"Transcoder-H264"` / `"Transcoder-H265"`
- Temp file prefix: `"transcode_"` (no need for codec-specific prefix)
- All other methods (`drivePipeline`, `copyAudioTrack`, `prepareAudioTrack`, `finalizeOutput`, `reportProgress`, `convertExtractorFlagsToCodecFlags`, `failure`) copied as-is from AVCTranscoder (they're identical)
- `codecSupportsProfile` and `trySetVendorKey` kept as `internal open` for testability

```kotlin
package com.davotoula.lightcompressor.compressor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import com.davotoula.lightcompressor.CompressionProgressListener
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.utils.CompressorUtils.findTrack
import com.davotoula.lightcompressor.utils.CompressorUtils.hasQTI
import com.davotoula.lightcompressor.utils.CompressorUtils.printException
import com.davotoula.lightcompressor.utils.CompressorUtils.setOutputFileParameters
import com.davotoula.lightcompressor.utils.StreamableVideo
import com.davotoula.lightcompressor.video.InputSurface
import com.davotoula.lightcompressor.video.OutputSurface
import com.davotoula.lightcompressor.video.Result
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

internal open class Transcoder(
    private val context: Context,
    private val srcUri: Uri,
    private val codec: VideoCodec,
    private val request: Request
) {

    data class Request(
        val index: Int,
        val width: Int,
        val height: Int,
        val bitrate: Long,
        val destination: File,
        val streamablePath: String?,
        val disableAudio: Boolean,
        val rotation: Int,
        val durationUs: Long,
        val listener: CompressionProgressListener
    )

    private val tag = "Transcoder-${codec.name}"

    fun transcode(): Result {
        val streamableRequested = request.streamablePath != null
        val parentDir = request.destination.parentFile ?: context.cacheDir
        val muxerOutputFile = if (streamableRequested) {
            File.createTempFile("transcode_", ".mp4", parentDir)
        } else {
            request.destination
        }

        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null
        var videoExtractor: MediaExtractor? = null
        var audioTrackInfo: AudioTrackInfo? = null

        return try {
            videoExtractor = MediaExtractor().apply {
                setDataSource(context, srcUri, null)
            }
            val videoTrackIndex = findTrack(videoExtractor, true)
            if (videoTrackIndex < 0) {
                return failure("No video track found in source")
            }
            videoExtractor.selectTrack(videoTrackIndex)
            videoExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inputFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val sourceMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return failure("Source video mime type missing")

            val encoderFormat = MediaFormat.createVideoFormat(
                codec.mimeType,
                request.width,
                request.height
            )
            setOutputFileParameters(inputFormat, encoderFormat, request.bitrate)
            tuneEncoderFormat(encoderFormat)

            encoder = createEncoder(encoderFormat)
            inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()

            outputSurface = OutputSurface()
            decoder = MediaCodec.createDecoderByType(sourceMime)
            decoder.configure(inputFormat, outputSurface.getSurface(), null, 0)
            decoder.start()

            muxer = MediaMuxer(muxerOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(request.rotation)

            audioTrackInfo = if (request.disableAudio) null else prepareAudioTrack(muxer)

            drivePipeline(
                videoExtractor = videoExtractor,
                decoder = decoder,
                outputSurface = outputSurface,
                encoder = encoder,
                inputSurface = inputSurface,
                muxer = muxer,
                audioTrack = audioTrackInfo
            )

            try {
                muxer.stop()
            } catch (stopError: Exception) {
                Log.w(tag, "Failed to stop muxer cleanly: ${stopError.message}")
            }
            try {
                muxer.release()
            } catch (releaseError: Exception) {
                Log.w(tag, "Failed to release muxer cleanly: ${releaseError.message}")
            }
            muxer = null

            finalizeOutput(muxerOutputFile, streamableRequested)
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                request.listener.onProgressCancelled(request.index)
                return failure("Compression cancelled")
            }
            printException(Exception(throwable))
            failure(throwable.message ?: "${codec.name} transcoding failed")
        } finally {
            try { videoExtractor?.release() } catch (_: Exception) {}
            audioTrackInfo?.let {
                try { it.extractor.release() } catch (_: Exception) {}
            }
            try { decoder?.stop() } catch (_: Exception) {}
            try { decoder?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { inputSurface?.release() } catch (_: Exception) {}
            try { outputSurface?.release() } catch (_: Exception) {}
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            if (streamableRequested && muxerOutputFile != request.destination && muxerOutputFile.exists()) {
                if (!muxerOutputFile.delete()) {
                    Log.w(tag, "Failed to delete temporary muxer file: ${muxerOutputFile.absolutePath}")
                }
            }
        }
    }

    // -- Codec-specific methods --

    private fun createEncoder(format: MediaFormat): MediaCodec {
        return when (codec) {
            VideoCodec.H264 -> createAvcEncoder(format)
            VideoCodec.H265 -> {
                val encoder = MediaCodec.createEncoderByType(VideoCodec.H265.mimeType)
                encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder
            }
        }
    }

    private fun createAvcEncoder(outputFormat: MediaFormat): MediaCodec {
        val hasQTI = hasQTI()
        var encoder = if (hasQTI) {
            try {
                MediaCodec.createByCodecName("c2.android.avc.encoder")
            } catch (e: Exception) {
                Log.w(tag, "Failed to create c2.android.avc.encoder, falling back to generic", e)
                MediaCodec.createEncoderByType(VideoCodec.H264.mimeType)
            }
        } else {
            MediaCodec.createEncoderByType(VideoCodec.H264.mimeType)
        }

        try {
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(tag, "Failed to configure encoder, falling back to generic encoder", e)
            encoder = MediaCodec.createEncoderByType(VideoCodec.H264.mimeType)
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }

        return encoder
    }

    internal open fun tuneEncoderFormat(encoderFormat: MediaFormat) {
        when (codec) {
            VideoCodec.H264 -> {
                if (codecSupportsProfile(codec.mimeType, AVC_PROFILE_HIGH, AVC_LEVEL_4)) {
                    encoderFormat.setInteger(MediaFormat.KEY_PROFILE, AVC_PROFILE_HIGH)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        encoderFormat.setInteger(MediaFormat.KEY_LEVEL, AVC_LEVEL_4)
                    }
                }
            }
            VideoCodec.H265 -> {
                if (codecSupportsProfile(codec.mimeType, HEVC_PROFILE_MAIN, HEVC_LEVEL_4)) {
                    encoderFormat.setInteger(MediaFormat.KEY_PROFILE, HEVC_PROFILE_MAIN)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        encoderFormat.setInteger(MediaFormat.KEY_LEVEL, HEVC_LEVEL_4)
                    }
                }
                trySetVendorKey(encoderFormat, "vendor.qti-ext-enc-bframes.num-bframes", 2)
                trySetVendorKey(encoderFormat, "video-encoder.max-bframes", 2)
            }
        }
    }

    // -- Shared methods (unchanged from originals) --

    // ... drivePipeline, copyAudioTrack, prepareAudioTrack, finalizeOutput,
    // reportProgress, convertExtractorFlagsToCodecFlags, failure,
    // AudioTrackInfo, CancellationException — all identical to AVCTranscoder

    internal open fun codecSupportsProfile(mime: String, profile: Int, level: Int): Boolean {
        // ... identical to both originals
    }

    internal open fun trySetVendorKey(format: MediaFormat, key: String, value: Int) {
        // ... identical to HevcTranscoder
    }

    companion object {
        private const val TIMEOUT_US = 100L
        private const val AVC_PROFILE_HIGH = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        private const val AVC_LEVEL_4 = MediaCodecInfo.CodecProfileLevel.AVCLevel4
        private const val HEVC_PROFILE_MAIN = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        private const val HEVC_LEVEL_4 = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `./gradlew :lightcompressor:compileDebugKotlin`
Expected: BUILD SUCCESSFUL (new file compiles, old files still exist and are unused)

- [ ] **Step 3: Commit**

```bash
git add lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Transcoder.kt
git commit -m "add unified Transcoder class merging AVC and HEVC transcoders"
```

---

### Task 2: Update Compressor to use Transcoder

**Files:**
- Modify: `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Compressor.kt:202-252`

- [ ] **Step 1: Replace `transcode()` method body**

Replace the `when(videoCodec)` block that creates separate `AVCTranscoder`/`HevcTranscoder` instances with a single `Transcoder` instantiation:

```kotlin
private fun transcode(
    id: Int,
    width: Int,
    height: Int,
    destination: String,
    bitrate: Long,
    streamableFile: String?,
    disableAudio: Boolean,
    context: Context,
    srcUri: Uri,
    listener: CompressionProgressListener,
    duration: Long,
    rotation: Int,
    videoCodec: VideoCodec
): Result {
    return Transcoder(
        context = context,
        srcUri = srcUri,
        codec = videoCodec,
        request = Transcoder.Request(
            index = id,
            width = width,
            height = height,
            bitrate = bitrate,
            destination = File(destination),
            streamablePath = streamableFile,
            disableAudio = disableAudio,
            rotation = rotation,
            durationUs = duration,
            listener = listener
        )
    ).transcode()
}
```

- [ ] **Step 2: Build to verify**

Run: `./gradlew :lightcompressor:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/Compressor.kt
git commit -m "update Compressor to use unified Transcoder"
```

---

### Task 3: Delete old transcoder files

**Files:**
- Delete: `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/AVCTranscoder.kt`
- Delete: `lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/HevcTranscoder.kt`

- [ ] **Step 1: Delete old files**

```bash
git rm lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/AVCTranscoder.kt
git rm lightcompressor/src/main/java/com/abedelazizshe/lightcompressorlibrary/compressor/HevcTranscoder.kt
```

- [ ] **Step 2: Build to verify no remaining references**

Run: `./gradlew :lightcompressor:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git commit -m "remove AVCTranscoder and HevcTranscoder, replaced by Transcoder"
```

---

### Task 4: Create unified TranscoderTest

**Files:**
- Create: `lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/compressor/TranscoderTest.kt`
- Delete: `lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/compressor/AVCTranscoderTest.kt`
- Delete: `lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/compressor/HevcTranscoderTest.kt`

- [ ] **Step 1: Create TranscoderTest.kt**

Merge both test classes. Key changes:
- All tests use `Transcoder` + `Transcoder.Request` instead of codec-specific types
- `tuneAvcEncoderFormat_setsProfileAndLevelWhenSupported` becomes a test that creates `Transcoder(... codec = VideoCodec.H264 ...)` and calls `tuneEncoderFormat()`
- `tuneHevcEncoderFormat_appliesProfileAndVendorHints` becomes a test with `VideoCodec.H265`
- `finalizeOutput` tests are codec-agnostic — keep one set (they were identical), use `VideoCodec.H264` for the codec parameter
- Override `codecSupportsProfile` and `trySetVendorKey` via anonymous subclass (same pattern as originals)

```kotlin
package com.davotoula.lightcompressor.compressor

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import com.davotoula.lightcompressor.CompressionProgressListener
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.utils.StreamableVideo
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class TranscoderTest {

    private val context: Context = mockk(relaxed = true)
    private val uri: Uri = mockk()
    private val listener: CompressionProgressListener = mockk(relaxed = true)

    @Before
    fun setUp() {
        mockkObject(StreamableVideo)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun tuneEncoderFormat_h264_setsAvcProfileAndLevel() {
        val transcoder = object : Transcoder(context, uri, VideoCodec.H264, baseRequest()) {
            override fun codecSupportsProfile(mime: String, profile: Int, level: Int) = true
        }
        val format = mockk<MediaFormat>(relaxed = true)
        transcoder.tuneEncoderFormat(format)
        verify {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh)
        }
    }

    @Test
    fun tuneEncoderFormat_h265_appliesProfileAndVendorHints() {
        val appliedKeys = mutableListOf<Pair<String, Int>>()
        val transcoder = object : Transcoder(context, uri, VideoCodec.H265, baseRequest()) {
            override fun codecSupportsProfile(mime: String, profile: Int, level: Int) = true
            override fun trySetVendorKey(format: MediaFormat, key: String, value: Int) {
                appliedKeys += key to value
                format.setInteger(key, value)
            }
        }
        val format = mockk<MediaFormat>(relaxed = true)
        transcoder.tuneEncoderFormat(format)
        verify {
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            verify {
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4)
            }
        }
        assertTrue("Expected vendor hints", appliedKeys.isNotEmpty())
    }

    @Test
    fun finalizeOutput_withoutStreamable_renamesMuxerToDestination() {
        val muxerOutput = File.createTempFile("transcoder_mux", ".mp4").apply {
            deleteOnExit(); writeText("mux")
        }
        val destination = File.createTempFile("transcoder_dest", ".mp4").apply {
            deleteOnExit(); writeText("dest-initial")
        }
        val transcoder = Transcoder(context, uri, VideoCodec.H264, baseRequest(destination = destination))
        val result = transcoder.finalizeOutput(muxerOutput, streamableRequested = false)
        assertTrue(destination.exists())
        assertEquals("mux", destination.readText())
        assertEquals(destination.path, result.path)
        verify(exactly = 0) { StreamableVideo.start(any(), any()) }
    }

    @Test
    fun finalizeOutput_withoutStreamable_sameFileSkipsRename() {
        val destination = File.createTempFile("transcoder_dest", ".mp4").apply {
            deleteOnExit(); writeText("muxer-output")
        }
        val transcoder = Transcoder(context, uri, VideoCodec.H264, baseRequest(destination = destination))
        val result = transcoder.finalizeOutput(destination, streamableRequested = false)
        assertTrue(destination.exists())
        assertEquals("muxer-output", destination.readText())
        assertEquals(destination.path, result.path)
        verify(exactly = 0) { StreamableVideo.start(any(), any()) }
    }

    @Test
    fun finalizeOutput_withStreamable_prefersSecondaryOutput() {
        val muxerOutput = File.createTempFile("transcoder_mux", ".mp4").apply {
            deleteOnExit(); writeText("mux")
        }
        val destination = File.createTempFile("transcoder_dest", ".mp4").apply {
            deleteOnExit(); delete()
        }
        val streamable = File.createTempFile("transcoder_stream", ".mp4").apply {
            deleteOnExit(); delete()
        }
        every { StreamableVideo.start(muxerOutput, destination) } answers {
            destination.writeText("fast-start"); true
        }
        every { StreamableVideo.start(destination, streamable) } answers {
            streamable.writeText("streamable"); true
        }
        val transcoder = Transcoder(
            context, uri, VideoCodec.H264,
            baseRequest(destination = destination, streamablePath = streamable.path)
        )
        val result = transcoder.finalizeOutput(muxerOutput, streamableRequested = true)
        assertTrue(streamable.exists())
        assertEquals("streamable", streamable.readText())
        assertFalse("Destination should be removed", destination.exists())
        assertEquals(streamable.path, result.path)
        verifySequence {
            StreamableVideo.start(muxerOutput, destination)
            StreamableVideo.start(destination, streamable)
        }
    }

    private fun baseRequest(
        destination: File = File.createTempFile("transcoder_dest", ".mp4").apply {
            deleteOnExit(); delete()
        },
        streamablePath: String? = null
    ) = Transcoder.Request(
        index = 0, width = 1280, height = 720, bitrate = 2_000_000L,
        destination = destination, streamablePath = streamablePath,
        disableAudio = true, rotation = 0, durationUs = 1_000_000L,
        listener = listener
    )
}
```

- [ ] **Step 2: Delete old test files**

```bash
git rm lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/compressor/AVCTranscoderTest.kt
git rm lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/compressor/HevcTranscoderTest.kt
```

- [ ] **Step 3: Run tests**

Run: `./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.compressor.TranscoderTest"`
Expected: All 5 tests PASS

- [ ] **Step 4: Commit**

```bash
git add lightcompressor/src/test/java/com/abedelazizshe/lightcompressorlibrary/compressor/TranscoderTest.kt
git commit -m "replace AVCTranscoderTest and HevcTranscoderTest with unified TranscoderTest"
```

---

### Task 5: Update CLAUDE.md references

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update Architecture section**

Replace references to `AVCTranscoder (H.264) / HevcTranscoder (H.265)` with `Transcoder` (unified, parameterized by `VideoCodec`). Update test class references from `AVCTranscoderTest` / `HevcTranscoderTest` to `TranscoderTest`.

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "update CLAUDE.md for unified Transcoder"
```

---

### Task 6: Full build and test verification

- [ ] **Step 1: Run full build and all tests**

Run: `./gradlew assembleDebug testDebugUnitTest`
Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 2: Verify no stale references**

Run: `grep -r "AVCTranscoder\|HevcTranscoder" lightcompressor/src/`
Expected: No matches
