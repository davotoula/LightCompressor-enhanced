# HLS Video Preparation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add HLS VOD preparation to LightCompressor — transcode a local video into multiple resolution renditions as fMP4 segments with HLS playlists, emitting segments incrementally via callbacks.

**Architecture:** Separate `HlsPreparer` API alongside existing `VideoCompressor`. Custom fMP4 muxer (`BoxWriter` + `Mp4SegmentWriter`) writes ISO BMFF segments. `HlsTranscoder` orchestrates multi-rendition encoding using the existing Surface/OpenGL pipeline. Segments emitted via `HlsListener` callbacks; library deletes temp files after callback returns.

**Tech Stack:** Kotlin, Android MediaCodec/MediaExtractor, OpenGL ES 2.0 (existing InputSurface/OutputSurface/TextureRenderer), JUnit 4 + MockK for tests.

**Spec:** `docs/specs/2026-04-12-hls-preparation-design.md`

---

## File Structure

### New files (library `lightcompressor/src/main/java/com/davotoula/lightcompressor/`)

| File | Responsibility |
|------|----------------|
| `Resolution.kt` | Resolution enum (moved from app, extended with 360p) |
| `HlsPreparer.kt` | Public entry point — coroutine launcher, cancellation |
| `hls/HlsConfig.kt` | HlsConfig, HlsLadder, Rendition data classes |
| `hls/HlsListener.kt` | HlsListener interface, HlsSegment, HlsError |
| `hls/HlsTranscoder.kt` | Internal orchestrator — multi-rendition encoding loop |
| `hls/SegmentAccumulator.kt` | Collects encoder output, detects keyframe segment boundaries |
| `hls/PlaylistGenerator.kt` | Builds m3u8 playlist strings |
| `hls/PlaylistRewriter.kt` | Public helper — remaps segment filenames to URLs |
| `muxer/BoxWriter.kt` | Low-level ISO BMFF box writing |
| `muxer/Mp4SegmentWriter.kt` | Writes fMP4 init segments and media segments |

### New test files (`lightcompressor/src/test/java/com/davotoula/lightcompressor/`)

| File | Tests |
|------|-------|
| `muxer/BoxWriterTest.kt` | Box encoding, nested boxes, full boxes, size patching |
| `muxer/Mp4SegmentWriterTest.kt` | Init segment structure, media segment structure, atom parsing |
| `hls/HlsConfigTest.kt` | Ladder defaults, drop/add, validation, auto-skip |
| `hls/PlaylistGeneratorTest.kt` | Media playlist format, master playlist, codec strings |
| `hls/PlaylistRewriterTest.kt` | URL remapping |
| `hls/SegmentAccumulatorTest.kt` | Keyframe boundary detection, duration tracking |

### Modified files (app module)

| File | Change |
|------|--------|
| `app/.../ui/main/MainUiState.kt:7-15` | Remove Resolution enum, import from library |
| `app/.../data/VideoSettingsPreferences.kt` | Update Resolution import |
| `app/.../ui/main/CollapsibleSettingsCard.kt` | Update Resolution import |
| `app/.../ui/components/SettingsSummary.kt` | Update Resolution import |

---

## Task 1: BoxWriter — Low-Level ISO BMFF Box Writing

**Files:**
- Create: `lightcompressor/src/main/java/com/davotoula/lightcompressor/muxer/BoxWriter.kt`
- Test: `lightcompressor/src/test/java/com/davotoula/lightcompressor/muxer/BoxWriterTest.kt`

This is the foundation — a utility that writes ISO 14496-12 boxes to a `ByteArrayOutputStream`. Every fMP4 structure is built from nested boxes.

- [ ] **Step 1: Write failing tests for basic box writing**

```kotlin
package com.davotoula.lightcompressor.muxer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class BoxWriterTest {

    @Test
    fun `writeBox - empty box has 8-byte header`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("free") {}
        val bytes = out.toByteArray()
        assertEquals(8, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(8, bb.getInt()) // size
        assertEquals("free", String(bytes, 4, 4, Charsets.US_ASCII)) // type
    }

    @Test
    fun `writeBox - box with payload includes payload in size`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("mdat") {
            writeBytes(byteArrayOf(0x01, 0x02, 0x03))
        }
        val bytes = out.toByteArray()
        assertEquals(11, bytes.size) // 8 header + 3 payload
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(11, bb.getInt())
    }

    @Test
    fun `fullBox - includes version and flags`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.fullBox("mvhd", version = 1, flags = 0) {
            writeUInt32(0L) // some data
        }
        val bytes = out.toByteArray()
        // 8 (header) + 4 (version+flags) + 4 (data) = 16
        assertEquals(16, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(16, bb.getInt()) // size
        assertEquals("mvhd", String(bytes, 4, 4, Charsets.US_ASCII))
        assertEquals(1, bb.get().toInt()) // version
        // flags = 3 bytes, all zero
        assertEquals(0, bb.get().toInt())
        assertEquals(0, bb.get().toInt())
        assertEquals(0, bb.get().toInt())
    }

    @Test
    fun `nested boxes - outer size includes inner box`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("moov") {
            box("mvhd") {
                writeUInt32(42L)
            }
        }
        val bytes = out.toByteArray()
        // outer: 8 header + inner(8 header + 4 data) = 20
        assertEquals(20, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        assertEquals(20, bb.getInt()) // outer size
        assertEquals("moov", String(bytes, 4, 4, Charsets.US_ASCII))
        assertEquals(12, bb.getInt()) // inner size
        assertEquals("mvhd", String(bytes, 12, 4, Charsets.US_ASCII))
    }

    @Test
    fun `writeUInt16 - writes big-endian 16-bit value`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("test") {
            writeUInt16(0x0102)
        }
        val bytes = out.toByteArray()
        assertEquals(0x01.toByte(), bytes[8])
        assertEquals(0x02.toByte(), bytes[9])
    }

    @Test
    fun `writeUInt32 - writes big-endian 32-bit value`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("test") {
            writeUInt32(0x01020304L)
        }
        val bytes = out.toByteArray()
        assertEquals(0x01.toByte(), bytes[8])
        assertEquals(0x02.toByte(), bytes[9])
        assertEquals(0x03.toByte(), bytes[10])
        assertEquals(0x04.toByte(), bytes[11])
    }

    @Test
    fun `writeUInt64 - writes big-endian 64-bit value`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("test") {
            writeUInt64(0x0102030405060708L)
        }
        val bytes = out.toByteArray()
        assertEquals(0x01.toByte(), bytes[8])
        assertEquals(0x08.toByte(), bytes[15])
    }

    @Test
    fun `writeFourCC - writes 4 ASCII bytes`() {
        val out = ByteArrayOutputStream()
        val writer = BoxWriter(out)
        writer.box("test") {
            writeFourCC("isom")
        }
        val bytes = out.toByteArray()
        assertEquals("isom", String(bytes, 8, 4, Charsets.US_ASCII))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.muxer.BoxWriterTest" 2>&1 | tail -5`
Expected: Compilation failure — `BoxWriter` class does not exist.

- [ ] **Step 3: Implement BoxWriter**

```kotlin
package com.davotoula.lightcompressor.muxer

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Low-level ISO 14496-12 (ISOBMFF) box writer.
 *
 * Writes nested boxes to an [OutputStream]. Box sizes are patched after
 * the content is written using an internal [ByteArrayOutputStream] buffer.
 */
internal class BoxWriter(private val output: OutputStream) {

    /**
     * Scope object providing write methods inside a box body.
     * Delegates to a [ByteArrayOutputStream] buffer; the enclosing
     * [box]/[fullBox] call patches the size header after the lambda returns.
     */
    inner class BoxScope(private val buffer: ByteArrayOutputStream) {

        fun writeUInt8(value: Int) {
            buffer.write(value and 0xFF)
        }

        fun writeUInt16(value: Int) {
            buffer.write((value shr 8) and 0xFF)
            buffer.write(value and 0xFF)
        }

        fun writeUInt32(value: Long) {
            buffer.write(((value shr 24) and 0xFF).toInt())
            buffer.write(((value shr 16) and 0xFF).toInt())
            buffer.write(((value shr 8) and 0xFF).toInt())
            buffer.write((value and 0xFF).toInt())
        }

        fun writeUInt64(value: Long) {
            buffer.write(((value shr 56) and 0xFF).toInt())
            buffer.write(((value shr 48) and 0xFF).toInt())
            buffer.write(((value shr 40) and 0xFF).toInt())
            buffer.write(((value shr 32) and 0xFF).toInt())
            buffer.write(((value shr 24) and 0xFF).toInt())
            buffer.write(((value shr 16) and 0xFF).toInt())
            buffer.write(((value shr 8) and 0xFF).toInt())
            buffer.write((value and 0xFF).toInt())
        }

        fun writeBytes(data: ByteArray) {
            buffer.write(data)
        }

        fun writeFourCC(code: String) {
            require(code.length == 4) { "FourCC must be exactly 4 characters: $code" }
            buffer.write(code.toByteArray(Charsets.US_ASCII))
        }

        fun writeZeros(count: Int) {
            repeat(count) { buffer.write(0) }
        }

        /** Write a nested box. */
        fun box(type: String, body: BoxScope.() -> Unit) {
            this@BoxWriter.writeBox(type, null, null, buffer, body)
        }

        /** Write a nested full box (with version and flags). */
        fun fullBox(type: String, version: Int, flags: Int, body: BoxScope.() -> Unit) {
            this@BoxWriter.writeBox(type, version, flags, buffer, body)
        }
    }

    /** Write a box to the top-level output stream. */
    fun box(type: String, body: BoxScope.() -> Unit) {
        writeBox(type, null, null, output, body)
    }

    /** Write a full box to the top-level output stream. */
    fun fullBox(type: String, version: Int, flags: Int, body: BoxScope.() -> Unit) {
        writeBox(type, version, flags, output, body)
    }

    private fun writeBox(
        type: String,
        version: Int?,
        flags: Int?,
        target: OutputStream,
        body: BoxScope.() -> Unit,
    ) {
        require(type.length == 4) { "Box type must be exactly 4 characters: $type" }
        val bodyBuffer = ByteArrayOutputStream()

        if (version != null && flags != null) {
            bodyBuffer.write(version and 0xFF)
            bodyBuffer.write((flags shr 16) and 0xFF)
            bodyBuffer.write((flags shr 8) and 0xFF)
            bodyBuffer.write(flags and 0xFF)
        }

        BoxScope(bodyBuffer).body()

        val bodyBytes = bodyBuffer.toByteArray()
        val totalSize = HEADER_SIZE + bodyBytes.size

        // Write 4-byte size (big-endian)
        target.write((totalSize shr 24) and 0xFF)
        target.write((totalSize shr 16) and 0xFF)
        target.write((totalSize shr 8) and 0xFF)
        target.write(totalSize and 0xFF)

        // Write 4-byte type
        target.write(type.toByteArray(Charsets.US_ASCII))

        // Write body
        target.write(bodyBytes)
    }

    companion object {
        private const val HEADER_SIZE = 8
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.muxer.BoxWriterTest" 2>&1 | tail -5`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add lightcompressor/src/main/java/com/davotoula/lightcompressor/muxer/BoxWriter.kt \
        lightcompressor/src/test/java/com/davotoula/lightcompressor/muxer/BoxWriterTest.kt
git commit -m "feat(hls): add BoxWriter for ISO BMFF box writing"
```

---

## Task 2: Mp4SegmentWriter — Initialization Segment

**Files:**
- Create: `lightcompressor/src/main/java/com/davotoula/lightcompressor/muxer/Mp4SegmentWriter.kt`
- Test: `lightcompressor/src/test/java/com/davotoula/lightcompressor/muxer/Mp4SegmentWriterTest.kt`

Writes the fMP4 initialization segment (ftyp + moov). This is emitted once per rendition before any media segments. Contains codec configuration (SPS/PPS for H.264, VPS/SPS/PPS for H.265) and track definitions.

- [ ] **Step 1: Write failing tests for init segment**

```kotlin
package com.davotoula.lightcompressor.muxer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Mp4SegmentWriterTest {

    private fun readBoxHeader(bb: ByteBuffer): Pair<Int, String> {
        val size = bb.getInt()
        val typeBytes = ByteArray(4)
        bb.get(typeBytes)
        return size to String(typeBytes, Charsets.US_ASCII)
    }

    @Test
    fun `init segment starts with ftyp box`() {
        val writer = Mp4SegmentWriter(
            videoCodecConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A),
            videoMimeType = "video/avc",
            videoWidth = 640,
            videoHeight = 360,
            videoTimescale = 90000,
            audioConfig = null,
        )
        val out = ByteArrayOutputStream()
        writer.writeInitSegment(out)
        val bytes = out.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val (ftypSize, ftypType) = readBoxHeader(bb)
        assertEquals("ftyp", ftypType)
        assertTrue("ftyp box size should be > 8", ftypSize > 8)
    }

    @Test
    fun `init segment has moov box after ftyp`() {
        val writer = Mp4SegmentWriter(
            videoCodecConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A),
            videoMimeType = "video/avc",
            videoWidth = 640,
            videoHeight = 360,
            videoTimescale = 90000,
            audioConfig = null,
        )
        val out = ByteArrayOutputStream()
        writer.writeInitSegment(out)
        val bytes = out.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val (ftypSize, _) = readBoxHeader(bb)
        bb.position(ftypSize)
        val (_, moovType) = readBoxHeader(bb)
        assertEquals("moov", moovType)
    }

    @Test
    fun `init segment moov contains mvhd, trak, mvex`() {
        val writer = Mp4SegmentWriter(
            videoCodecConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A),
            videoMimeType = "video/avc",
            videoWidth = 640,
            videoHeight = 360,
            videoTimescale = 90000,
            audioConfig = null,
        )
        val out = ByteArrayOutputStream()
        writer.writeInitSegment(out)
        val bytes = out.toByteArray()
        val childTypes = findChildBoxTypes(bytes, "moov")
        assertTrue("moov must contain mvhd", "mvhd" in childTypes)
        assertTrue("moov must contain trak", "trak" in childTypes)
        assertTrue("moov must contain mvex", "mvex" in childTypes)
    }

    @Test
    fun `init segment with audio has two trak boxes`() {
        val writer = Mp4SegmentWriter(
            videoCodecConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A),
            videoMimeType = "video/avc",
            videoWidth = 640,
            videoHeight = 360,
            videoTimescale = 90000,
            audioConfig = AudioConfig(
                codecConfig = byteArrayOf(0x12, 0x10),
                sampleRate = 44100,
                channelCount = 2,
                timescale = 44100,
            ),
        )
        val out = ByteArrayOutputStream()
        writer.writeInitSegment(out)
        val bytes = out.toByteArray()
        val childTypes = findChildBoxTypes(bytes, "moov")
        assertEquals("moov should have 2 trak boxes", 2, childTypes.count { it == "trak" })
    }

    /** Finds the top-level box with [parentType] and lists its direct children's types. */
    private fun findChildBoxTypes(data: ByteArray, parentType: String): List<String> {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        // Find the parent box
        while (bb.remaining() >= 8) {
            val startPos = bb.position()
            val (size, type) = readBoxHeader(bb)
            if (type == parentType) {
                val children = mutableListOf<String>()
                val endPos = startPos + size
                while (bb.position() + 8 <= endPos) {
                    val childStart = bb.position()
                    val (childSize, childType) = readBoxHeader(bb)
                    children.add(childType)
                    bb.position(childStart + childSize)
                }
                return children
            }
            bb.position(startPos + size)
        }
        return emptyList()
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.muxer.Mp4SegmentWriterTest" 2>&1 | tail -5`
Expected: Compilation failure — `Mp4SegmentWriter` and `AudioConfig` do not exist.

- [ ] **Step 3: Implement Mp4SegmentWriter init segment**

```kotlin
package com.davotoula.lightcompressor.muxer

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Audio track configuration for the initialization segment.
 */
internal data class AudioConfig(
    val codecConfig: ByteArray,
    val sampleRate: Int,
    val channelCount: Int,
    val timescale: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioConfig) return false
        return codecConfig.contentEquals(other.codecConfig) &&
            sampleRate == other.sampleRate &&
            channelCount == other.channelCount &&
            timescale == other.timescale
    }

    override fun hashCode(): Int {
        var result = codecConfig.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + timescale
        return result
    }
}

/**
 * Writes fragmented MP4 (fMP4) initialization and media segments
 * conforming to ISO 14496-12 for HLS delivery.
 */
internal class Mp4SegmentWriter(
    private val videoCodecConfig: ByteArray,
    private val videoMimeType: String,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val videoTimescale: Int = DEFAULT_VIDEO_TIMESCALE,
    private val audioConfig: AudioConfig? = null,
) {
    /**
     * Writes the fMP4 initialization segment (ftyp + moov) to [output].
     * This must be emitted once per rendition before any media segments.
     */
    fun writeInitSegment(output: OutputStream) {
        val writer = BoxWriter(output)
        writeFtyp(writer)
        writeMoov(writer)
    }

    private fun writeFtyp(writer: BoxWriter) {
        writer.box("ftyp") {
            writeFourCC("isom") // major brand
            writeUInt32(0x200L) // minor version
            writeFourCC("isom")
            writeFourCC("iso6")
            writeFourCC("msdh")
            writeFourCC("msix")
        }
    }

    private fun writeMoov(writer: BoxWriter) {
        writer.box("moov") {
            writeMvhd(this)
            writeVideoTrak(this, trackId = VIDEO_TRACK_ID)
            if (audioConfig != null) {
                writeAudioTrak(this, trackId = AUDIO_TRACK_ID)
            }
            writeMvex(this)
        }
    }

    private fun writeMvhd(scope: BoxWriter.BoxScope) {
        scope.fullBox("mvhd", version = 0, flags = 0) {
            writeUInt32(0L) // creation time
            writeUInt32(0L) // modification time
            writeUInt32(videoTimescale.toLong()) // timescale
            writeUInt32(0L) // duration (unknown for fragmented)
            writeUInt32(0x00010000L) // rate = 1.0 (fixed point 16.16)
            writeUInt16(0x0100) // volume = 1.0 (fixed point 8.8)
            writeZeros(10) // reserved
            // Unity matrix (9 x int32)
            writeUInt32(0x00010000L); writeUInt32(0L); writeUInt32(0L)
            writeUInt32(0L); writeUInt32(0x00010000L); writeUInt32(0L)
            writeUInt32(0L); writeUInt32(0L); writeUInt32(0x40000000L)
            writeZeros(24) // pre-defined
            val nextTrackId = if (audioConfig != null) 3L else 2L
            writeUInt32(nextTrackId) // next track ID
        }
    }

    private fun writeVideoTrak(scope: BoxWriter.BoxScope, trackId: Int) {
        scope.box("trak") {
            writeTkhd(this, trackId, videoWidth, videoHeight)
            box("mdia") {
                writeMdhd(this, videoTimescale)
                writeHdlr(this, "vide", "VideoHandler")
                box("minf") {
                    fullBox("vmhd", version = 0, flags = 1) {
                        writeUInt16(0) // graphicsmode
                        writeUInt16(0); writeUInt16(0); writeUInt16(0) // opcolor
                    }
                    writeDinf(this)
                    writeVideoStbl(this)
                }
            }
        }
    }

    private fun writeAudioTrak(scope: BoxWriter.BoxScope, trackId: Int) {
        val audio = audioConfig ?: return
        scope.box("trak") {
            writeTkhd(this, trackId, 0, 0)
            box("mdia") {
                writeMdhd(this, audio.timescale)
                writeHdlr(this, "soun", "SoundHandler")
                box("minf") {
                    fullBox("smhd", version = 0, flags = 0) {
                        writeUInt16(0) // balance
                        writeUInt16(0) // reserved
                    }
                    writeDinf(this)
                    writeAudioStbl(this)
                }
            }
        }
    }

    private fun writeTkhd(scope: BoxWriter.BoxScope, trackId: Int, width: Int, height: Int) {
        scope.fullBox("tkhd", version = 0, flags = 3) { // flags: enabled + in-movie
            writeUInt32(0L) // creation time
            writeUInt32(0L) // modification time
            writeUInt32(trackId.toLong()) // track ID
            writeUInt32(0L) // reserved
            writeUInt32(0L) // duration
            writeZeros(8) // reserved
            writeUInt16(0) // layer
            writeUInt16(0) // alternate group
            writeUInt16(if (width == 0) 0x0100 else 0) // volume (audio=1.0, video=0)
            writeUInt16(0) // reserved
            // Unity matrix
            writeUInt32(0x00010000L); writeUInt32(0L); writeUInt32(0L)
            writeUInt32(0L); writeUInt32(0x00010000L); writeUInt32(0L)
            writeUInt32(0L); writeUInt32(0L); writeUInt32(0x40000000L)
            writeUInt32((width.toLong() shl 16)) // width (16.16 fixed)
            writeUInt32((height.toLong() shl 16)) // height (16.16 fixed)
        }
    }

    private fun writeMdhd(scope: BoxWriter.BoxScope, timescale: Int) {
        scope.fullBox("mdhd", version = 0, flags = 0) {
            writeUInt32(0L) // creation time
            writeUInt32(0L) // modification time
            writeUInt32(timescale.toLong())
            writeUInt32(0L) // duration
            writeUInt16(0x55C4.toInt()) // language = "und"
            writeUInt16(0) // pre-defined
        }
    }

    private fun writeHdlr(scope: BoxWriter.BoxScope, handlerType: String, name: String) {
        scope.fullBox("hdlr", version = 0, flags = 0) {
            writeUInt32(0L) // pre-defined
            writeFourCC(handlerType)
            writeZeros(12) // reserved
            writeBytes(name.toByteArray(Charsets.US_ASCII))
            writeUInt8(0) // null terminator
        }
    }

    private fun writeDinf(scope: BoxWriter.BoxScope) {
        scope.box("dinf") {
            fullBox("dref", version = 0, flags = 0) {
                writeUInt32(1L) // entry count
                fullBox("url ", version = 0, flags = 1) {} // self-contained flag
            }
        }
    }

    private fun writeVideoStbl(scope: BoxWriter.BoxScope) {
        scope.box("stbl") {
            writeVideoStsd(this)
            writeEmptyTimeToSample(this)
            writeEmptySampleToChunk(this)
            writeEmptySampleSize(this)
            writeEmptyChunkOffset(this)
        }
    }

    private fun writeAudioStbl(scope: BoxWriter.BoxScope) {
        scope.box("stbl") {
            writeAudioStsd(this)
            writeEmptyTimeToSample(this)
            writeEmptySampleToChunk(this)
            writeEmptySampleSize(this)
            writeEmptyChunkOffset(this)
        }
    }

    private fun writeVideoStsd(scope: BoxWriter.BoxScope) {
        scope.fullBox("stsd", version = 0, flags = 0) {
            writeUInt32(1L) // entry count
            val codecBox = if (videoMimeType == "video/hevc") "hev1" else "avc1"
            box(codecBox) {
                writeZeros(6) // reserved
                writeUInt16(1) // data reference index
                writeZeros(16) // pre-defined + reserved
                writeUInt16(videoWidth)
                writeUInt16(videoHeight)
                writeUInt32(0x00480000L) // h-resolution 72dpi (16.16)
                writeUInt32(0x00480000L) // v-resolution 72dpi
                writeUInt32(0L) // reserved
                writeUInt16(1) // frame count
                writeZeros(32) // compressor name
                writeUInt16(0x0018) // depth = 24
                writeUInt16(0xFFFF.toInt()) // pre-defined = -1
                writeCodecConfigBox(this)
            }
        }
    }

    private fun writeCodecConfigBox(scope: BoxWriter.BoxScope) {
        if (videoMimeType == "video/hevc") {
            scope.box("hvcC") {
                writeBytes(videoCodecConfig)
            }
        } else {
            scope.box("avcC") {
                writeBytes(videoCodecConfig)
            }
        }
    }

    private fun writeAudioStsd(scope: BoxWriter.BoxScope) {
        val audio = audioConfig ?: return
        scope.fullBox("stsd", version = 0, flags = 0) {
            writeUInt32(1L) // entry count
            box("mp4a") {
                writeZeros(6) // reserved
                writeUInt16(1) // data reference index
                writeZeros(8) // reserved
                writeUInt16(audio.channelCount)
                writeUInt16(16) // sample size
                writeUInt16(0) // compression id
                writeUInt16(0) // reserved
                writeUInt32((audio.sampleRate.toLong() shl 16)) // sample rate (16.16)
                writeEsds(this)
            }
        }
    }

    private fun writeEsds(scope: BoxWriter.BoxScope) {
        val audio = audioConfig ?: return
        scope.fullBox("esds", version = 0, flags = 0) {
            val configLen = audio.codecConfig.size
            // ES_Descriptor
            writeUInt8(0x03) // tag
            writeUInt8(23 + configLen) // length
            writeUInt16(1) // ES_ID
            writeUInt8(0) // stream priority
            // DecoderConfigDescriptor
            writeUInt8(0x04) // tag
            writeUInt8(15 + configLen) // length
            writeUInt8(0x40) // objectTypeIndication = AAC
            writeUInt8(0x15) // streamType = audio (5 << 2 | 1)
            writeUInt8(0); writeUInt16(0) // bufferSizeDB (3 bytes)
            writeUInt32(0L) // maxBitrate
            writeUInt32(0L) // avgBitrate
            // DecoderSpecificInfo
            writeUInt8(0x05) // tag
            writeUInt8(configLen) // length
            writeBytes(audio.codecConfig)
            // SLConfigDescriptor
            writeUInt8(0x06) // tag
            writeUInt8(1) // length
            writeUInt8(0x02) // predefined = MP4
        }
    }

    private fun writeEmptyTimeToSample(scope: BoxWriter.BoxScope) {
        scope.fullBox("stts", version = 0, flags = 0) {
            writeUInt32(0L) // entry count = 0 (data in fragments)
        }
    }

    private fun writeEmptySampleToChunk(scope: BoxWriter.BoxScope) {
        scope.fullBox("stsc", version = 0, flags = 0) {
            writeUInt32(0L) // entry count = 0
        }
    }

    private fun writeEmptySampleSize(scope: BoxWriter.BoxScope) {
        scope.fullBox("stsz", version = 0, flags = 0) {
            writeUInt32(0L) // sample size = 0 (variable)
            writeUInt32(0L) // sample count = 0
        }
    }

    private fun writeEmptyChunkOffset(scope: BoxWriter.BoxScope) {
        scope.fullBox("stco", version = 0, flags = 0) {
            writeUInt32(0L) // entry count = 0
        }
    }

    private fun writeMvex(scope: BoxWriter.BoxScope) {
        scope.box("mvex") {
            writeTrex(this, VIDEO_TRACK_ID)
            if (audioConfig != null) {
                writeTrex(this, AUDIO_TRACK_ID)
            }
        }
    }

    private fun writeTrex(scope: BoxWriter.BoxScope, trackId: Int) {
        scope.fullBox("trex", version = 0, flags = 0) {
            writeUInt32(trackId.toLong())
            writeUInt32(1L) // default sample description index
            writeUInt32(0L) // default sample duration
            writeUInt32(0L) // default sample size
            writeUInt32(0L) // default sample flags
        }
    }

    companion object {
        const val VIDEO_TRACK_ID = 1
        const val AUDIO_TRACK_ID = 2
        const val DEFAULT_VIDEO_TIMESCALE = 90000
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.muxer.Mp4SegmentWriterTest" 2>&1 | tail -5`
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add lightcompressor/src/main/java/com/davotoula/lightcompressor/muxer/Mp4SegmentWriter.kt \
        lightcompressor/src/test/java/com/davotoula/lightcompressor/muxer/Mp4SegmentWriterTest.kt
git commit -m "feat(hls): add Mp4SegmentWriter init segment (ftyp + moov)"
```

---

## Task 3: Mp4SegmentWriter — Media Segments

**Files:**
- Modify: `lightcompressor/src/main/java/com/davotoula/lightcompressor/muxer/Mp4SegmentWriter.kt`
- Modify: `lightcompressor/src/test/java/com/davotoula/lightcompressor/muxer/Mp4SegmentWriterTest.kt`

Adds `writeMediaSegment()` — writes moof + mdat boxes containing encoded video (and optionally audio) samples for one segment.

- [ ] **Step 1: Write failing tests for media segment**

Add to `Mp4SegmentWriterTest.kt`:

```kotlin
@Test
fun `media segment starts with moof box`() {
    val writer = createVideoOnlyWriter()
    val samples = listOf(
        EncodedSample(
            data = ByteArray(100),
            presentationTimeUs = 0L,
            durationUs = 33333L,
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME,
        ),
    )
    val out = ByteArrayOutputStream()
    writer.writeMediaSegment(
        videoSamples = samples,
        audioSamples = emptyList(),
        sequenceNumber = 1,
        baseDecodeTimeUs = 0L,
        output = out,
    )
    val bytes = out.toByteArray()
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val (_, type) = readBoxHeader(bb)
    assertEquals("moof", type)
}

@Test
fun `media segment has mdat after moof`() {
    val writer = createVideoOnlyWriter()
    val samples = listOf(
        EncodedSample(
            data = ByteArray(100),
            presentationTimeUs = 0L,
            durationUs = 33333L,
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME,
        ),
    )
    val out = ByteArrayOutputStream()
    writer.writeMediaSegment(
        videoSamples = samples,
        audioSamples = emptyList(),
        sequenceNumber = 1,
        baseDecodeTimeUs = 0L,
        output = out,
    )
    val bytes = out.toByteArray()
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val (moofSize, _) = readBoxHeader(bb)
    bb.position(moofSize)
    val (_, mdatType) = readBoxHeader(bb)
    assertEquals("mdat", mdatType)
}

@Test
fun `media segment mdat size equals header plus sample data`() {
    val writer = createVideoOnlyWriter()
    val sampleData = ByteArray(256) { it.toByte() }
    val samples = listOf(
        EncodedSample(
            data = sampleData,
            presentationTimeUs = 0L,
            durationUs = 33333L,
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME,
        ),
    )
    val out = ByteArrayOutputStream()
    writer.writeMediaSegment(
        videoSamples = samples,
        audioSamples = emptyList(),
        sequenceNumber = 1,
        baseDecodeTimeUs = 0L,
        output = out,
    )
    val bytes = out.toByteArray()
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
    val (moofSize, _) = readBoxHeader(bb)
    bb.position(moofSize)
    val (mdatSize, _) = readBoxHeader(bb)
    assertEquals(8 + 256, mdatSize) // header + sample data
}

@Test
fun `media segment moof contains mfhd and traf`() {
    val writer = createVideoOnlyWriter()
    val samples = listOf(
        EncodedSample(
            data = ByteArray(50),
            presentationTimeUs = 0L,
            durationUs = 33333L,
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME,
        ),
    )
    val out = ByteArrayOutputStream()
    writer.writeMediaSegment(
        videoSamples = samples,
        audioSamples = emptyList(),
        sequenceNumber = 1,
        baseDecodeTimeUs = 0L,
        output = out,
    )
    val bytes = out.toByteArray()
    val childTypes = findChildBoxTypes(bytes, "moof")
    assertTrue("moof must contain mfhd", "mfhd" in childTypes)
    assertTrue("moof must contain traf", "traf" in childTypes)
}

private fun createVideoOnlyWriter() = Mp4SegmentWriter(
    videoCodecConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A),
    videoMimeType = "video/avc",
    videoWidth = 640,
    videoHeight = 360,
    videoTimescale = 90000,
    audioConfig = null,
)
```

Also add the `EncodedSample` import (class will be defined in the same package).

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.muxer.Mp4SegmentWriterTest" 2>&1 | tail -5`
Expected: Compilation failure — `writeMediaSegment` and `EncodedSample` do not exist.

- [ ] **Step 3: Add EncodedSample and writeMediaSegment**

Add `EncodedSample` to `Mp4SegmentWriter.kt`:

```kotlin
/**
 * A single encoded sample (video or audio frame) from MediaCodec.
 */
internal data class EncodedSample(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val durationUs: Long,
    val flags: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedSample) return false
        return data.contentEquals(other.data) &&
            presentationTimeUs == other.presentationTimeUs &&
            durationUs == other.durationUs &&
            flags == other.flags
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + durationUs.hashCode()
        result = 31 * result + flags
        return result
    }
}
```

Add `writeMediaSegment` method to `Mp4SegmentWriter`:

```kotlin
/**
 * Writes one fMP4 media segment (moof + mdat) to [output].
 *
 * @param videoSamples encoded video frames for this segment
 * @param audioSamples encoded audio frames for this segment (empty if audio disabled)
 * @param sequenceNumber fragment sequence number (1-based, incrementing)
 * @param baseDecodeTimeUs decode timestamp of the first sample in this segment (microseconds)
 * @param output target stream
 */
fun writeMediaSegment(
    videoSamples: List<EncodedSample>,
    audioSamples: List<EncodedSample>,
    sequenceNumber: Int,
    baseDecodeTimeUs: Long,
    output: OutputStream,
) {
    val writer = BoxWriter(output)
    val videoDataSize = videoSamples.sumOf { it.data.size }
    val audioDataSize = audioSamples.sumOf { it.data.size }

    writer.box("moof") {
        writeMfhd(this, sequenceNumber)
        writeVideoTraf(this, videoSamples, baseDecodeTimeUs, videoDataSize, audioDataSize)
        if (audioSamples.isNotEmpty() && audioConfig != null) {
            writeAudioTraf(this, audioSamples, baseDecodeTimeUs)
        }
    }
    writer.box("mdat") {
        for (sample in videoSamples) {
            writeBytes(sample.data)
        }
        for (sample in audioSamples) {
            writeBytes(sample.data)
        }
    }
}

private fun writeMfhd(scope: BoxWriter.BoxScope, sequenceNumber: Int) {
    scope.fullBox("mfhd", version = 0, flags = 0) {
        writeUInt32(sequenceNumber.toLong())
    }
}

private fun writeVideoTraf(
    scope: BoxWriter.BoxScope,
    samples: List<EncodedSample>,
    baseDecodeTimeUs: Long,
    videoDataSize: Int,
    audioDataSize: Int,
) {
    scope.box("traf") {
        writeTfhd(this, VIDEO_TRACK_ID)
        writeTfdt(this, baseDecodeTimeUs, videoTimescale)
        writeVideoTrun(this, samples)
    }
}

private fun writeAudioTraf(
    scope: BoxWriter.BoxScope,
    samples: List<EncodedSample>,
    baseDecodeTimeUs: Long,
) {
    val audio = audioConfig ?: return
    scope.box("traf") {
        writeTfhd(this, AUDIO_TRACK_ID)
        writeTfdt(this, baseDecodeTimeUs, audio.timescale)
        writeAudioTrun(this, samples)
    }
}

private fun writeTfhd(scope: BoxWriter.BoxScope, trackId: Int) {
    // flags: 0x020000 = default-base-is-moof
    scope.fullBox("tfhd", version = 0, flags = 0x020000) {
        writeUInt32(trackId.toLong())
    }
}

private fun writeTfdt(scope: BoxWriter.BoxScope, baseDecodeTimeUs: Long, timescale: Int) {
    val baseDecodeTime = baseDecodeTimeUs * timescale / 1_000_000L
    scope.fullBox("tfdt", version = 1, flags = 0) {
        writeUInt64(baseDecodeTime)
    }
}

private fun writeVideoTrun(scope: BoxWriter.BoxScope, samples: List<EncodedSample>) {
    // flags: 0x000001 = data-offset-present
    //        0x000100 = sample-duration-present
    //        0x000200 = sample-size-present
    //        0x000400 = sample-flags-present
    val trunFlags = 0x000001 or 0x000100 or 0x000200 or 0x000400
    scope.fullBox("trun", version = 0, flags = trunFlags) {
        writeUInt32(samples.size.toLong()) // sample count
        writeUInt32(0L) // data offset placeholder (patched by muxer consumer if needed)
        for (sample in samples) {
            val duration = (sample.durationUs * videoTimescale / 1_000_000L).toInt()
            writeUInt32(duration.toLong()) // sample duration
            writeUInt32(sample.data.size.toLong()) // sample size
            val isKeyFrame = sample.flags and KEY_FRAME_FLAG != 0
            val sampleFlags = if (isKeyFrame) 0x02000000 else 0x00010000
            writeUInt32(sampleFlags.toLong()) // sample flags
        }
    }
}

private fun writeAudioTrun(scope: BoxWriter.BoxScope, samples: List<EncodedSample>) {
    val audio = audioConfig ?: return
    val trunFlags = 0x000001 or 0x000100 or 0x000200
    scope.fullBox("trun", version = 0, flags = trunFlags) {
        writeUInt32(samples.size.toLong())
        writeUInt32(0L) // data offset placeholder
        for (sample in samples) {
            val duration = (sample.durationUs * audio.timescale / 1_000_000L).toInt()
            writeUInt32(duration.toLong())
            writeUInt32(sample.data.size.toLong())
        }
    }
}

companion object {
    const val VIDEO_TRACK_ID = 1
    const val AUDIO_TRACK_ID = 2
    const val DEFAULT_VIDEO_TIMESCALE = 90000
    private const val KEY_FRAME_FLAG = 1 // MediaCodec.BUFFER_FLAG_KEY_FRAME
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.muxer.Mp4SegmentWriterTest" 2>&1 | tail -5`
Expected: All 8 tests PASS (4 from Task 2 + 4 new).

- [ ] **Step 5: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add lightcompressor/src/main/java/com/davotoula/lightcompressor/muxer/Mp4SegmentWriter.kt \
        lightcompressor/src/test/java/com/davotoula/lightcompressor/muxer/Mp4SegmentWriterTest.kt
git commit -m "feat(hls): add Mp4SegmentWriter media segment (moof + mdat)"
```

---

## Task 4: Resolution Enum — Move to Library

**Files:**
- Create: `lightcompressor/src/main/java/com/davotoula/lightcompressor/Resolution.kt`
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt:7-15` (remove enum)
- Modify: `app/src/main/java/com/davotoula/lce/data/VideoSettingsPreferences.kt` (update import)
- Modify: `app/src/main/java/com/davotoula/lce/ui/main/CollapsibleSettingsCard.kt` (update import)
- Modify: `app/src/main/java/com/davotoula/lce/ui/components/SettingsSummary.kt` (update import)

- [ ] **Step 1: Create Resolution enum in library**

```kotlin
package com.davotoula.lightcompressor

/**
 * Standard video resolutions identified by short-side pixel count.
 * Used by both the compression and HLS preparation APIs.
 */
enum class Resolution(
    val shortSide: Int,
    val label: String,
) {
    UHD_4K(2160, "4K"),
    FHD_1080(1080, "1080p"),
    HD_720(720, "720p"),
    SD_540(540, "540p"),
    SD_360(360, "360p"),
}
```

- [ ] **Step 2: Remove Resolution enum from app's MainUiState.kt**

In `app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt`, remove lines 7-15 (the `enum class Resolution` block) and add an import:

```kotlin
import com.davotoula.lightcompressor.Resolution
```

- [ ] **Step 3: Update imports in other app files**

In each of these files, replace `import com.davotoula.lce.ui.main.Resolution` with `import com.davotoula.lightcompressor.Resolution`:

- `app/src/main/java/com/davotoula/lce/data/VideoSettingsPreferences.kt`
- `app/src/main/java/com/davotoula/lce/ui/main/CollapsibleSettingsCard.kt`
- `app/src/main/java/com/davotoula/lce/ui/components/SettingsSummary.kt`

- [ ] **Step 4: Build both modules to verify**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run all tests to verify no regressions**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew testDebugUnitTest 2>&1 | tail -5`
Expected: All existing tests PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add lightcompressor/src/main/java/com/davotoula/lightcompressor/Resolution.kt \
        app/src/main/java/com/davotoula/lce/ui/main/MainUiState.kt \
        app/src/main/java/com/davotoula/lce/data/VideoSettingsPreferences.kt \
        app/src/main/java/com/davotoula/lce/ui/main/CollapsibleSettingsCard.kt \
        app/src/main/java/com/davotoula/lce/ui/components/SettingsSummary.kt
git commit -m "refactor: move Resolution enum from app to library, add SD_360"
```

---

## Task 5: HLS Public API Types

**Files:**
- Create: `lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/HlsConfig.kt`
- Create: `lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/HlsListener.kt`
- Test: `lightcompressor/src/test/java/com/davotoula/lightcompressor/hls/HlsConfigTest.kt`

- [ ] **Step 1: Write failing tests for HlsConfig and HlsLadder**

```kotlin
package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsConfigTest {

    @Test
    fun `default ladder has 5 renditions`() {
        val ladder = HlsLadder.default()
        assertEquals(5, ladder.renditions.size)
    }

    @Test
    fun `default ladder is ordered lowest to highest resolution`() {
        val ladder = HlsLadder.default()
        val shortSides = ladder.renditions.map { it.resolution.shortSide }
        assertEquals(shortSides.sorted(), shortSides)
    }

    @Test
    fun `default ladder contains expected resolutions`() {
        val ladder = HlsLadder.default()
        val labels = ladder.renditions.map { it.resolution.label }
        assertEquals(listOf("360p", "540p", "720p", "1080p", "4K"), labels)
    }

    @Test
    fun `drop removes renditions by label`() {
        val ladder = HlsLadder.default().drop("4K", "360p")
        assertEquals(3, ladder.renditions.size)
        val labels = ladder.renditions.map { it.resolution.label }
        assertTrue("4K" !in labels)
        assertTrue("360p" !in labels)
    }

    @Test
    fun `add appends and re-sorts by resolution`() {
        val ladder = HlsLadder(
            listOf(Rendition(Resolution.HD_720, 2500)),
        ).add(Rendition(Resolution.SD_360, 500))
        assertEquals(2, ladder.renditions.size)
        assertEquals(Resolution.SD_360, ladder.renditions[0].resolution)
        assertEquals(Resolution.HD_720, ladder.renditions[1].resolution)
    }

    @Test
    fun `forSource filters renditions above source short side`() {
        val ladder = HlsLadder.default().forSource(sourceShortSide = 720)
        val labels = ladder.renditions.map { it.resolution.label }
        assertEquals(listOf("360p", "540p", "720p"), labels)
    }

    @Test
    fun `forSource keeps rendition matching source exactly`() {
        val ladder = HlsLadder.default().forSource(sourceShortSide = 1080)
        assertTrue(ladder.renditions.any { it.resolution == Resolution.FHD_1080 })
    }

    @Test
    fun `default config uses H264 and 6-second segments`() {
        val config = HlsConfig()
        assertEquals(com.davotoula.lightcompressor.VideoCodec.H264, config.codec)
        assertEquals(6, config.segmentDurationSeconds)
        assertEquals(1, config.maxParallelEncoders)
        assertEquals(false, config.disableAudio)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.hls.HlsConfigTest" 2>&1 | tail -5`
Expected: Compilation failure.

- [ ] **Step 3: Implement HlsConfig, HlsLadder, Rendition**

```kotlin
package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.Resolution
import com.davotoula.lightcompressor.VideoCodec

/**
 * A single rendition in the HLS encoding ladder.
 */
data class Rendition(
    val resolution: Resolution,
    val bitrateKbps: Int,
)

/**
 * An ordered list of renditions forming the HLS adaptive bitrate ladder.
 * Renditions are always sorted lowest-to-highest by [Resolution.shortSide].
 */
class HlsLadder(val renditions: List<Rendition>) {

    /** Remove renditions by [Resolution.label]. */
    fun drop(vararg labels: String): HlsLadder {
        val labelSet = labels.toSet()
        return HlsLadder(renditions.filter { it.resolution.label !in labelSet })
    }

    /** Add a rendition, maintaining sort order. */
    fun add(rendition: Rendition): HlsLadder {
        return HlsLadder((renditions + rendition).sortedBy { it.resolution.shortSide })
    }

    /** Filter to only renditions whose short side <= [sourceShortSide]. */
    fun forSource(sourceShortSide: Int): HlsLadder {
        return HlsLadder(renditions.filter { it.resolution.shortSide <= sourceShortSide })
    }

    companion object {
        fun default(): HlsLadder = HlsLadder(
            listOf(
                Rendition(Resolution.SD_360, 500),
                Rendition(Resolution.SD_540, 1200),
                Rendition(Resolution.HD_720, 2500),
                Rendition(Resolution.FHD_1080, 5000),
                Rendition(Resolution.UHD_4K, 15000),
            ),
        )
    }
}

/**
 * Configuration for HLS video preparation.
 */
data class HlsConfig(
    val ladder: HlsLadder = HlsLadder.default(),
    val codec: VideoCodec = VideoCodec.H264,
    val segmentDurationSeconds: Int = 6,
    val maxParallelEncoders: Int = 1,
    val disableAudio: Boolean = false,
)
```

- [ ] **Step 4: Implement HlsListener, HlsSegment, HlsError**

```kotlin
package com.davotoula.lightcompressor.hls

import java.io.File

/**
 * Callback interface for HLS preparation progress and results.
 *
 * Threading:
 * - [onSegmentReady] and [onProgress]: called on worker thread (Dispatchers.Default)
 * - All other callbacks: called on Main thread
 *
 * The [onSegmentReady] callback is synchronous: the segment file is valid
 * until the callback returns. The library deletes the temp file after return.
 */
interface HlsListener {
    /** Called when preparation starts. [renditionCount] = number of renditions to process. */
    fun onStart(renditionCount: Int)

    /** Called when a rendition begins encoding. */
    fun onRenditionStart(rendition: Rendition)

    /**
     * Called when a segment is ready. The [segment] file is valid until this method returns.
     * Upload or copy the file before returning — the library deletes it afterward.
     */
    fun onSegmentReady(rendition: Rendition, segment: HlsSegment)

    /** Called when a rendition finishes. [playlist] is the m3u8 media playlist content. */
    fun onRenditionComplete(rendition: Rendition, playlist: String)

    /** Called when all renditions complete. [masterPlaylist] is the master m3u8 content. */
    fun onComplete(masterPlaylist: String)

    /** Called if all renditions fail. Partial success still triggers [onComplete]. */
    fun onFailure(error: HlsError)

    /** Encoding progress for the current rendition. [percent] is 0.0 to 100.0. */
    fun onProgress(rendition: Rendition, percent: Float)

    /** Called if [HlsPreparer.cancel] is invoked. */
    fun onCancelled()
}

/**
 * Represents a single fMP4 segment ready for upload.
 */
data class HlsSegment(
    /** Temp file containing the segment data. Valid until [HlsListener.onSegmentReady] returns. */
    val file: File,
    /** Segment sequence number (0-based). */
    val index: Int,
    /** Actual segment duration in seconds. */
    val durationSeconds: Double,
    /** True for the initialization segment (init.mp4), false for media segments. */
    val isInitSegment: Boolean,
)

/**
 * Error details when HLS preparation fails.
 */
data class HlsError(
    val message: String,
    val failedRenditions: List<Rendition>,
    val completedRenditions: List<Rendition>,
)
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.hls.HlsConfigTest" 2>&1 | tail -5`
Expected: All 8 tests PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/HlsConfig.kt \
        lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/HlsListener.kt \
        lightcompressor/src/test/java/com/davotoula/lightcompressor/hls/HlsConfigTest.kt
git commit -m "feat(hls): add HLS public API types (HlsConfig, HlsListener, HlsSegment)"
```

---

## Task 6: PlaylistGenerator

**Files:**
- Create: `lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/PlaylistGenerator.kt`
- Test: `lightcompressor/src/test/java/com/davotoula/lightcompressor/hls/PlaylistGeneratorTest.kt`

Builds HLS m3u8 playlist strings. Pure string operations, no Android dependencies.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistGeneratorTest {

    private val generator = PlaylistGenerator()

    @Test
    fun `media playlist starts with EXTM3U and version 7`() {
        val playlist = generator.buildMediaPlaylist(
            segments = listOf(
                SegmentInfo("segment_000.m4s", 6.0),
            ),
            targetDurationSeconds = 6,
        )
        assertTrue(playlist.startsWith("#EXTM3U\n"))
        assertTrue("#EXT-X-VERSION:7" in playlist)
    }

    @Test
    fun `media playlist ends with EXT-X-ENDLIST`() {
        val playlist = generator.buildMediaPlaylist(
            segments = listOf(SegmentInfo("segment_000.m4s", 6.0)),
            targetDurationSeconds = 6,
        )
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"))
    }

    @Test
    fun `media playlist includes EXT-X-MAP for init segment`() {
        val playlist = generator.buildMediaPlaylist(
            segments = listOf(SegmentInfo("segment_000.m4s", 6.0)),
            targetDurationSeconds = 6,
        )
        assertTrue("#EXT-X-MAP:URI=\"init.mp4\"" in playlist)
    }

    @Test
    fun `media playlist includes EXTINF for each segment`() {
        val playlist = generator.buildMediaPlaylist(
            segments = listOf(
                SegmentInfo("segment_000.m4s", 6.0),
                SegmentInfo("segment_001.m4s", 6.0),
                SegmentInfo("segment_002.m4s", 4.320),
            ),
            targetDurationSeconds = 6,
        )
        assertEquals(3, Regex("#EXTINF:").findAll(playlist).count())
        assertTrue("#EXTINF:4.320," in playlist)
    }

    @Test
    fun `media playlist target duration is ceiling of max segment duration`() {
        val playlist = generator.buildMediaPlaylist(
            segments = listOf(
                SegmentInfo("segment_000.m4s", 6.0),
                SegmentInfo("segment_001.m4s", 6.5),
            ),
            targetDurationSeconds = 7,
        )
        assertTrue("#EXT-X-TARGETDURATION:7" in playlist)
    }

    @Test
    fun `master playlist includes STREAM-INF for each rendition`() {
        val master = generator.buildMasterPlaylist(
            renditions = listOf(
                RenditionResult(
                    rendition = Rendition(Resolution.SD_360, 500),
                    actualWidth = 640,
                    actualHeight = 360,
                    codecString = "avc1.64001E",
                    playlistFilename = "360p/media.m3u8",
                ),
                RenditionResult(
                    rendition = Rendition(Resolution.HD_720, 2500),
                    actualWidth = 1280,
                    actualHeight = 720,
                    codecString = "avc1.640020",
                    playlistFilename = "720p/media.m3u8",
                ),
            ),
        )
        assertTrue("#EXTM3U" in master)
        assertTrue("BANDWIDTH=500000" in master)
        assertTrue("BANDWIDTH=2500000" in master)
        assertTrue("RESOLUTION=640x360" in master)
        assertTrue("RESOLUTION=1280x720" in master)
        assertTrue("CODECS=\"avc1.64001E\"" in master)
        assertTrue("360p/media.m3u8" in master)
        assertTrue("720p/media.m3u8" in master)
    }

    @Test
    fun `master playlist renditions ordered by bandwidth ascending`() {
        val master = generator.buildMasterPlaylist(
            renditions = listOf(
                RenditionResult(
                    rendition = Rendition(Resolution.HD_720, 2500),
                    actualWidth = 1280, actualHeight = 720,
                    codecString = "avc1.640020",
                    playlistFilename = "720p/media.m3u8",
                ),
                RenditionResult(
                    rendition = Rendition(Resolution.SD_360, 500),
                    actualWidth = 640, actualHeight = 360,
                    codecString = "avc1.64001E",
                    playlistFilename = "360p/media.m3u8",
                ),
            ),
        )
        val idx360 = master.indexOf("360p/media.m3u8")
        val idx720 = master.indexOf("720p/media.m3u8")
        assertTrue("360p should appear before 720p", idx360 < idx720)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.hls.PlaylistGeneratorTest" 2>&1 | tail -5`
Expected: Compilation failure.

- [ ] **Step 3: Implement PlaylistGenerator**

```kotlin
package com.davotoula.lightcompressor.hls

/**
 * Metadata for one segment, used to build the media playlist.
 */
internal data class SegmentInfo(
    val filename: String,
    val durationSeconds: Double,
)

/**
 * Result for one completed rendition, used to build the master playlist.
 */
internal data class RenditionResult(
    val rendition: Rendition,
    val actualWidth: Int,
    val actualHeight: Int,
    val codecString: String,
    val playlistFilename: String,
)

/**
 * Generates HLS m3u8 playlist strings (VOD).
 */
internal class PlaylistGenerator {

    /**
     * Builds a media playlist for a single rendition.
     */
    fun buildMediaPlaylist(
        segments: List<SegmentInfo>,
        targetDurationSeconds: Int,
    ): String = buildString {
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:7")
        appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
        appendLine("#EXT-X-MEDIA-SEQUENCE:0")
        appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
        appendLine("#EXT-X-MAP:URI=\"init.mp4\"")
        appendLine()
        for (segment in segments) {
            appendLine("#EXTINF:${formatDuration(segment.durationSeconds)},")
            appendLine(segment.filename)
        }
        appendLine()
        appendLine("#EXT-X-ENDLIST")
    }

    /**
     * Builds the master playlist referencing all completed renditions.
     * Renditions are sorted by bandwidth ascending.
     */
    fun buildMasterPlaylist(renditions: List<RenditionResult>): String = buildString {
        val sorted = renditions.sortedBy { it.rendition.bitrateKbps }
        appendLine("#EXTM3U")
        appendLine("#EXT-X-VERSION:7")
        appendLine()
        for (r in sorted) {
            val bandwidthBps = r.rendition.bitrateKbps * 1000
            append("#EXT-X-STREAM-INF:")
            append("BANDWIDTH=$bandwidthBps,")
            append("RESOLUTION=${r.actualWidth}x${r.actualHeight},")
            appendLine("CODECS=\"${r.codecString}\"")
            appendLine(r.playlistFilename)
            appendLine()
        }
    }

    private fun formatDuration(seconds: Double): String {
        return "%.3f".format(seconds)
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.hls.PlaylistGeneratorTest" 2>&1 | tail -5`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/PlaylistGenerator.kt \
        lightcompressor/src/test/java/com/davotoula/lightcompressor/hls/PlaylistGeneratorTest.kt
git commit -m "feat(hls): add PlaylistGenerator for m3u8 VOD playlists"
```

---

## Task 7: PlaylistRewriter

**Files:**
- Create: `lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/PlaylistRewriter.kt`
- Test: `lightcompressor/src/test/java/com/davotoula/lightcompressor/hls/PlaylistRewriterTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
package com.davotoula.lightcompressor.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRewriterTest {

    @Test
    fun `rewrites segment filenames to URLs`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.000,
            segment_000.m4s
            #EXTINF:6.000,
            segment_001.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val urlMap = mapOf(
            "init.mp4" to "https://blossom.example/abc123",
            "segment_000.m4s" to "https://blossom.example/def456",
            "segment_001.m4s" to "https://blossom.example/ghi789",
        )
        val result = PlaylistRewriter.rewrite(playlist, urlMap)
        assertTrue("https://blossom.example/abc123" in result)
        assertTrue("https://blossom.example/def456" in result)
        assertTrue("https://blossom.example/ghi789" in result)
        assertTrue("init.mp4" !in result)
        assertTrue("segment_000.m4s" !in result)
    }

    @Test
    fun `preserves HLS tags unchanged`() {
        val playlist = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXTINF:6.000,
            segment_000.m4s
            #EXT-X-ENDLIST
        """.trimIndent()
        val urlMap = mapOf("segment_000.m4s" to "https://example.com/a")
        val result = PlaylistRewriter.rewrite(playlist, urlMap)
        assertTrue("#EXTM3U" in result)
        assertTrue("#EXT-X-VERSION:7" in result)
        assertTrue("#EXTINF:6.000," in result)
    }

    @Test
    fun `leaves unmapped filenames unchanged`() {
        val playlist = "#EXTINF:6.000,\nsegment_000.m4s\n"
        val result = PlaylistRewriter.rewrite(playlist, emptyMap())
        assertTrue("segment_000.m4s" in result)
    }

    @Test
    fun `rewrites EXT-X-MAP URI attribute`() {
        val playlist = "#EXT-X-MAP:URI=\"init.mp4\"\n"
        val urlMap = mapOf("init.mp4" to "https://blossom.example/init")
        val result = PlaylistRewriter.rewrite(playlist, urlMap)
        assertEquals("#EXT-X-MAP:URI=\"https://blossom.example/init\"\n", result)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.hls.PlaylistRewriterTest" 2>&1 | tail -5`
Expected: Compilation failure.

- [ ] **Step 3: Implement PlaylistRewriter**

```kotlin
package com.davotoula.lightcompressor.hls

/**
 * Rewrites HLS playlist segment filenames to uploaded URLs.
 *
 * After uploading segments to a content-addressed server (e.g. Blossom),
 * use this to replace predictable filenames with actual URLs before
 * uploading the playlist.
 */
object PlaylistRewriter {

    private val MAP_URI_PATTERN = Regex("""(#EXT-X-MAP:URI=")([^"]+)(")""")

    /**
     * Replaces segment filenames in [playlist] using [urlMap].
     *
     * @param playlist the m3u8 content (media or master)
     * @param urlMap maps original filenames (e.g. "segment_000.m4s") to URLs
     * @return the rewritten playlist string
     */
    fun rewrite(playlist: String, urlMap: Map<String, String>): String {
        return playlist.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    MAP_URI_PATTERN.replace(line) { match ->
                        val filename = match.groupValues[2]
                        val url = urlMap[filename] ?: filename
                        "${match.groupValues[1]}$url${match.groupValues[3]}"
                    }
                }
                line.startsWith("#") || line.isBlank() -> line
                else -> urlMap[line.trim()] ?: line
            }
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.hls.PlaylistRewriterTest" 2>&1 | tail -5`
Expected: All 4 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/PlaylistRewriter.kt \
        lightcompressor/src/test/java/com/davotoula/lightcompressor/hls/PlaylistRewriterTest.kt
git commit -m "feat(hls): add PlaylistRewriter for URL remapping"
```

---

## Task 8: SegmentAccumulator

**Files:**
- Create: `lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/SegmentAccumulator.kt`
- Test: `lightcompressor/src/test/java/com/davotoula/lightcompressor/hls/SegmentAccumulatorTest.kt`

Collects encoded samples from the MediaCodec encoder output loop and detects keyframe-aligned segment boundaries.

- [ ] **Step 1: Write failing tests**

```kotlin
package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.muxer.EncodedSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentAccumulatorTest {

    private val segmentDurationUs = 6_000_000L // 6 seconds

    @Test
    fun `no flush before segment duration reached`() {
        val acc = SegmentAccumulator(segmentDurationUs)
        acc.addVideoSample(keyframeSample(0L, 33_333L))
        acc.addVideoSample(frameSample(33_333L, 33_333L))
        assertNull(acc.flushIfReady())
    }

    @Test
    fun `flushes on keyframe after segment duration`() {
        val acc = SegmentAccumulator(segmentDurationUs)
        // Fill 6 seconds of frames at 30fps
        var pts = 0L
        val frameDuration = 33_333L
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        pts += frameDuration
        while (pts < segmentDurationUs) {
            acc.addVideoSample(frameSample(pts, frameDuration))
            pts += frameDuration
        }
        // Next keyframe triggers flush
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        val flushed = acc.flushIfReady()
        assertTrue("Should flush after duration + keyframe", flushed != null)
        assertTrue("Flushed samples should not be empty", flushed!!.videoSamples.isNotEmpty())
    }

    @Test
    fun `flushed segment does not include the triggering keyframe`() {
        val acc = SegmentAccumulator(segmentDurationUs)
        var pts = 0L
        val frameDuration = 33_333L
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        pts += frameDuration
        while (pts < segmentDurationUs) {
            acc.addVideoSample(frameSample(pts, frameDuration))
            pts += frameDuration
        }
        val triggerPts = pts
        acc.addVideoSample(keyframeSample(triggerPts, frameDuration))
        val flushed = acc.flushIfReady()!!
        // The triggering keyframe should NOT be in the flushed segment
        assertFalse(
            "Triggering keyframe should be in next segment",
            flushed.videoSamples.any { it.presentationTimeUs == triggerPts },
        )
    }

    @Test
    fun `segment duration is sum of sample durations`() {
        val acc = SegmentAccumulator(segmentDurationUs)
        val frameDuration = 33_333L
        var pts = 0L
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        pts += frameDuration
        // Add frames for ~6 seconds
        while (pts < segmentDurationUs) {
            acc.addVideoSample(frameSample(pts, frameDuration))
            pts += frameDuration
        }
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        val flushed = acc.flushIfReady()!!
        val expectedDuration = flushed.videoSamples.sumOf { it.durationUs }
        assertTrue("Duration should be approximately 6s", expectedDuration in 5_900_000L..6_100_000L)
    }

    @Test
    fun `flushRemaining returns final segment`() {
        val acc = SegmentAccumulator(segmentDurationUs)
        acc.addVideoSample(keyframeSample(0L, 33_333L))
        acc.addVideoSample(frameSample(33_333L, 33_333L))
        val remaining = acc.flushRemaining()
        assertTrue("Should have remaining samples", remaining != null)
        assertEquals(2, remaining!!.videoSamples.size)
    }

    @Test
    fun `tracks audio samples alongside video`() {
        val acc = SegmentAccumulator(segmentDurationUs)
        acc.addVideoSample(keyframeSample(0L, 33_333L))
        acc.addAudioSample(audioSample(0L, 23_219L))
        acc.addAudioSample(audioSample(23_219L, 23_219L))
        val remaining = acc.flushRemaining()!!
        assertEquals(1, remaining.videoSamples.size)
        assertEquals(2, remaining.audioSamples.size)
    }

    @Test
    fun `sequenceNumber increments on each flush`() {
        val acc = SegmentAccumulator(1_000_000L) // 1 second segments for test speed
        val frameDuration = 33_333L
        var pts = 0L
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        pts += frameDuration
        while (pts < 1_000_000L) {
            acc.addVideoSample(frameSample(pts, frameDuration))
            pts += frameDuration
        }
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        val first = acc.flushIfReady()!!
        assertEquals(1, first.sequenceNumber)

        pts += frameDuration
        while (pts < 2_000_000L) {
            acc.addVideoSample(frameSample(pts, frameDuration))
            pts += frameDuration
        }
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        val second = acc.flushIfReady()!!
        assertEquals(2, second.sequenceNumber)
    }

    private fun keyframeSample(pts: Long, duration: Long) = EncodedSample(
        data = ByteArray(1000),
        presentationTimeUs = pts,
        durationUs = duration,
        flags = 1, // KEY_FRAME
    )

    private fun frameSample(pts: Long, duration: Long) = EncodedSample(
        data = ByteArray(200),
        presentationTimeUs = pts,
        durationUs = duration,
        flags = 0,
    )

    private fun audioSample(pts: Long, duration: Long) = EncodedSample(
        data = ByteArray(50),
        presentationTimeUs = pts,
        durationUs = duration,
        flags = 0,
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.hls.SegmentAccumulatorTest" 2>&1 | tail -5`
Expected: Compilation failure.

- [ ] **Step 3: Implement SegmentAccumulator**

```kotlin
package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.muxer.EncodedSample

/**
 * Result of flushing a complete segment from the accumulator.
 */
internal data class FlushedSegment(
    val videoSamples: List<EncodedSample>,
    val audioSamples: List<EncodedSample>,
    val sequenceNumber: Int,
    val baseDecodeTimeUs: Long,
    val durationUs: Long,
)

/**
 * Collects encoded samples from the MediaCodec output loop and detects
 * keyframe-aligned segment boundaries.
 *
 * When a keyframe arrives after [targetSegmentDurationUs] has elapsed
 * since the current segment start, [flushIfReady] returns the accumulated
 * samples as a [FlushedSegment]. The triggering keyframe becomes the first
 * sample of the next segment.
 */
internal class SegmentAccumulator(
    private val targetSegmentDurationUs: Long,
) {
    private val videoSamples = mutableListOf<EncodedSample>()
    private val audioSamples = mutableListOf<EncodedSample>()
    private var segmentStartPtsUs = 0L
    private var accumulatedDurationUs = 0L
    private var sequenceCounter = 0
    private var pendingKeyframe: EncodedSample? = null

    fun addVideoSample(sample: EncodedSample) {
        val isKeyFrame = sample.flags and KEY_FRAME_FLAG != 0
        if (isKeyFrame && accumulatedDurationUs >= targetSegmentDurationUs) {
            pendingKeyframe = sample
        } else {
            videoSamples.add(sample)
            accumulatedDurationUs += sample.durationUs
        }
    }

    fun addAudioSample(sample: EncodedSample) {
        audioSamples.add(sample)
    }

    /**
     * Returns a [FlushedSegment] if a segment boundary was detected, null otherwise.
     * After flushing, the triggering keyframe becomes the start of the next segment.
     */
    fun flushIfReady(): FlushedSegment? {
        val keyframe = pendingKeyframe ?: return null
        pendingKeyframe = null

        val flushed = FlushedSegment(
            videoSamples = videoSamples.toList(),
            audioSamples = audioSamples.toList(),
            sequenceNumber = ++sequenceCounter,
            baseDecodeTimeUs = segmentStartPtsUs,
            durationUs = accumulatedDurationUs,
        )

        videoSamples.clear()
        audioSamples.clear()
        segmentStartPtsUs = keyframe.presentationTimeUs
        accumulatedDurationUs = keyframe.durationUs
        videoSamples.add(keyframe)

        return flushed
    }

    /**
     * Flushes all remaining samples as the final segment (end of stream).
     * Returns null if no samples are accumulated.
     */
    fun flushRemaining(): FlushedSegment? {
        if (videoSamples.isEmpty()) return null
        val flushed = FlushedSegment(
            videoSamples = videoSamples.toList(),
            audioSamples = audioSamples.toList(),
            sequenceNumber = ++sequenceCounter,
            baseDecodeTimeUs = segmentStartPtsUs,
            durationUs = accumulatedDurationUs,
        )
        videoSamples.clear()
        audioSamples.clear()
        accumulatedDurationUs = 0L
        return flushed
    }

    companion object {
        private const val KEY_FRAME_FLAG = 1 // MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:testDebugUnitTest --tests "com.davotoula.lightcompressor.hls.SegmentAccumulatorTest" 2>&1 | tail -5`
Expected: All 7 tests PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/SegmentAccumulator.kt \
        lightcompressor/src/test/java/com/davotoula/lightcompressor/hls/SegmentAccumulatorTest.kt
git commit -m "feat(hls): add SegmentAccumulator for keyframe boundary detection"
```

---

## Task 9: HlsTranscoder — Single Rendition Encoding

**Files:**
- Create: `lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/HlsTranscoder.kt`

This is the core orchestrator. It's heavily coupled to Android MediaCodec APIs so it's tested primarily through integration/instrumented testing. This task implements single-rendition encoding; Task 10 adds multi-rendition orchestration.

The structure mirrors the existing `Transcoder.kt` (lines 52-220) but replaces MediaMuxer with `Mp4SegmentWriter` and adds segment boundary detection via `SegmentAccumulator`.

- [ ] **Step 1: Implement HlsTranscoder**

```kotlin
package com.davotoula.lightcompressor.hls

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.muxer.AudioConfig
import com.davotoula.lightcompressor.muxer.EncodedSample
import com.davotoula.lightcompressor.muxer.Mp4SegmentWriter
import com.davotoula.lightcompressor.utils.CompressorUtils
import com.davotoula.lightcompressor.video.InputSurface
import com.davotoula.lightcompressor.video.OutputSurface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * Internal transcoder for HLS preparation.
 * Encodes a single rendition, segmenting output into fMP4 chunks
 * and emitting them via [HlsListener.onSegmentReady].
 */
internal class HlsTranscoder(
    private val context: Context,
    private val srcUri: Uri,
    private val config: HlsConfig,
) {
    @Volatile
    var isCancelled = false

    /**
     * Encodes one rendition of the source video.
     *
     * @param rendition target resolution and bitrate
     * @param actualWidth target width (calculated from source aspect ratio)
     * @param actualHeight target height
     * @param listener callbacks for segment delivery and progress
     * @param tempDir directory for temporary segment files
     * @return [RenditionResult] on success, null on failure
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod", "TooGenericExceptionCaught")
    fun encodeRendition(
        rendition: Rendition,
        actualWidth: Int,
        actualHeight: Int,
        listener: HlsListener,
        tempDir: File,
    ): RenditionResult? {
        val segmentDurationUs = config.segmentDurationSeconds * 1_000_000L
        val segments = mutableListOf<SegmentInfo>()
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null

        try {
            // Setup video extractor
            videoExtractor = MediaExtractor().apply { setDataSource(context, srcUri, null) }
            val videoTrackIndex = CompressorUtils.findTrack(videoExtractor!!, true)
            if (videoTrackIndex < 0) return null
            val inputFormat = videoExtractor!!.getTrackFormat(videoTrackIndex)
            videoExtractor!!.selectTrack(videoTrackIndex)
            val sourceMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION)

            // Setup audio extractor (if enabled)
            var audioConfig: AudioConfig? = null
            if (!config.disableAudio) {
                audioExtractor = MediaExtractor().apply { setDataSource(context, srcUri, null) }
                val audioTrackIndex = CompressorUtils.findTrack(audioExtractor!!, false)
                if (audioTrackIndex >= 0) {
                    audioExtractor!!.selectTrack(audioTrackIndex)
                    val audioFormat = audioExtractor!!.getTrackFormat(audioTrackIndex)
                    val csd0 = audioFormat.getByteBuffer("csd-0")
                    if (csd0 != null) {
                        val configBytes = ByteArray(csd0.remaining())
                        csd0.get(configBytes)
                        audioConfig = AudioConfig(
                            codecConfig = configBytes,
                            sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            timescale = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                        )
                    }
                } else {
                    audioExtractor?.release()
                    audioExtractor = null
                }
            }

            // Configure encoder
            val encoderFormat = MediaFormat.createVideoFormat(
                config.codec.mimeType,
                actualWidth,
                actualHeight,
            ).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, rendition.bitrateKbps * 1000)
                setInteger(
                    MediaFormat.KEY_FRAME_RATE,
                    getFrameRate(inputFormat),
                )
                setInteger(
                    MediaFormat.KEY_I_FRAME_INTERVAL,
                    config.segmentDurationSeconds,
                )
                setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                )
                setInteger(
                    MediaFormat.KEY_BITRATE_MODE,
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                )
            }

            encoder = MediaCodec.createEncoderByType(config.codec.mimeType)
            encoder!!.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            inputSurface = InputSurface(encoder!!.createInputSurface())
            inputSurface!!.makeCurrent()
            encoder!!.start()

            // Configure decoder
            outputSurface = OutputSurface()
            decoder = MediaCodec.createDecoderByType(sourceMime)
            decoder!!.configure(inputFormat, outputSurface!!.getSurface(), null, 0)
            decoder!!.start()

            // Get codec config from encoder output format change
            var codecConfigBytes: ByteArray? = null
            var encoderOutputFormat: MediaFormat? = null

            // Create segment writer (deferred until we have codec config)
            var segmentWriter: Mp4SegmentWriter? = null
            val accumulator = SegmentAccumulator(segmentDurationUs)

            // Drive the encoding pipeline
            val timeoutUs = TIMEOUT_US
            val bufferInfo = MediaCodec.BufferInfo()
            val encoderBufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false

            while (!encoderDone) {
                if (isCancelled) throw CancellationException()

                // Feed decoder input
                if (!inputDone) {
                    val inputBufferIndex = decoder!!.dequeueInputBuffer(timeoutUs)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = decoder!!.getInputBuffer(inputBufferIndex)
                        if (inputBuffer == null) {
                            decoder!!.queueInputBuffer(
                                inputBufferIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                        } else {
                            val sampleSize = videoExtractor!!.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder!!.queueInputBuffer(
                                    inputBufferIndex, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                inputDone = true
                            } else {
                                decoder!!.queueInputBuffer(
                                    inputBufferIndex, 0, sampleSize,
                                    videoExtractor!!.sampleTime,
                                    videoExtractor!!.sampleFlags,
                                )
                                videoExtractor!!.advance()
                            }
                        }
                    }
                }

                // Collect encoder output and decoder output
                var encoderOutputAvailable = true
                var decoderOutputAvailable = !decoderDone

                while (encoderOutputAvailable || decoderOutputAvailable) {
                    // Encoder output
                    if (encoderOutputAvailable) {
                        val encoderStatus = encoder!!.dequeueOutputBuffer(encoderBufferInfo, timeoutUs)
                        when {
                            encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                encoderOutputAvailable = false
                            }
                            encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                encoderOutputFormat = encoder!!.outputFormat
                                val csd0 = encoderOutputFormat!!.getByteBuffer("csd-0")
                                if (csd0 != null) {
                                    codecConfigBytes = ByteArray(csd0.remaining())
                                    csd0.get(codecConfigBytes!!)
                                }
                                segmentWriter = Mp4SegmentWriter(
                                    videoCodecConfig = codecConfigBytes ?: ByteArray(0),
                                    videoMimeType = config.codec.mimeType,
                                    videoWidth = actualWidth,
                                    videoHeight = actualHeight,
                                    audioConfig = audioConfig,
                                )
                                // Emit init segment
                                val initFile = File(tempDir, "init_${rendition.resolution.label}.mp4")
                                FileOutputStream(initFile).use { fos ->
                                    segmentWriter!!.writeInitSegment(fos)
                                }
                                listener.onSegmentReady(
                                    rendition,
                                    HlsSegment(initFile, 0, 0.0, isInitSegment = true),
                                )
                                initFile.delete()
                            }
                            encoderStatus >= 0 -> {
                                val encodedData = encoder!!.getOutputBuffer(encoderStatus)
                                    ?: throw RuntimeException("Null encoder output buffer")

                                if (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    encoder!!.releaseOutputBuffer(encoderStatus, false)
                                    continue
                                }

                                if (encoderBufferInfo.size > 0) {
                                    val data = ByteArray(encoderBufferInfo.size)
                                    encodedData.position(encoderBufferInfo.offset)
                                    encodedData.get(data)

                                    // Calculate sample duration from PTS difference
                                    // (approximation — refined by accumulator)
                                    val frameDurationUs = 1_000_000L / getFrameRate(inputFormat)

                                    accumulator.addVideoSample(
                                        EncodedSample(
                                            data = data,
                                            presentationTimeUs = encoderBufferInfo.presentationTimeUs,
                                            durationUs = frameDurationUs,
                                            flags = encoderBufferInfo.flags,
                                        ),
                                    )

                                    // Check for segment boundary
                                    val flushed = accumulator.flushIfReady()
                                    if (flushed != null) {
                                        emitSegment(
                                            segmentWriter!!, flushed, rendition,
                                            listener, segments, tempDir,
                                        )
                                    }

                                    // Report progress
                                    val progress = min(
                                        (encoderBufferInfo.presentationTimeUs.toFloat() / durationUs) * 100f,
                                        100f,
                                    )
                                    listener.onProgress(rendition, progress)
                                }

                                encoder!!.releaseOutputBuffer(encoderStatus, false)

                                if (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    // Flush remaining samples as final segment
                                    val remaining = accumulator.flushRemaining()
                                    if (remaining != null && segmentWriter != null) {
                                        emitSegment(
                                            segmentWriter!!, remaining, rendition,
                                            listener, segments, tempDir,
                                        )
                                    }
                                    encoderDone = true
                                    break
                                }
                            }
                        }
                    }

                    // Decoder output
                    if (decoderOutputAvailable && !encoderDone) {
                        val decoderStatus = decoder!!.dequeueOutputBuffer(bufferInfo, timeoutUs)
                        when {
                            decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                decoderOutputAvailable = false
                            }
                            decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* no-op */ }
                            decoderStatus >= 0 -> {
                                val doRender = bufferInfo.size > 0
                                decoder!!.releaseOutputBuffer(decoderStatus, doRender)
                                if (doRender) {
                                    if (isCancelled) throw CancellationException()
                                    outputSurface!!.awaitNewImage()
                                    outputSurface!!.drawImage()
                                    inputSurface!!.setPresentationTime(
                                        bufferInfo.presentationTimeUs * NS_PER_US,
                                    )
                                    inputSurface!!.swapBuffers()
                                }
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    decoderDone = true
                                    encoder!!.signalEndOfInputStream()
                                }
                            }
                        }
                    }
                }

                // Copy audio samples for current time window
                if (audioExtractor != null && audioConfig != null) {
                    copyAudioSamples(audioExtractor!!, accumulator)
                }
            }

            // Build codec string from encoder output format
            val codecString = buildCodecString(encoderOutputFormat, config.codec)
            val targetDuration = segments.maxOfOrNull { it.durationSeconds }?.toInt()?.plus(1) ?: config.segmentDurationSeconds

            val playlist = PlaylistGenerator().buildMediaPlaylist(
                segments = segments,
                targetDurationSeconds = targetDuration,
            )

            return RenditionResult(
                rendition = rendition,
                actualWidth = actualWidth,
                actualHeight = actualHeight,
                codecString = codecString,
                playlistFilename = "${rendition.resolution.label}/media.m3u8",
            )
                .also { listener.onRenditionComplete(rendition, playlist) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Rendition ${rendition.resolution.label} failed", e)
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            runCatching { outputSurface?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
        }
    }

    private fun emitSegment(
        writer: Mp4SegmentWriter,
        flushed: FlushedSegment,
        rendition: Rendition,
        listener: HlsListener,
        segments: MutableList<SegmentInfo>,
        tempDir: File,
    ) {
        val filename = "segment_%03d.m4s".format(flushed.sequenceNumber - 1)
        val segmentFile = File(tempDir, "${rendition.resolution.label}_$filename")
        FileOutputStream(segmentFile).use { fos ->
            writer.writeMediaSegment(
                videoSamples = flushed.videoSamples,
                audioSamples = flushed.audioSamples,
                sequenceNumber = flushed.sequenceNumber,
                baseDecodeTimeUs = flushed.baseDecodeTimeUs,
                output = fos,
            )
        }
        val durationSeconds = flushed.durationUs / 1_000_000.0
        segments.add(SegmentInfo(filename, durationSeconds))
        listener.onSegmentReady(
            rendition,
            HlsSegment(segmentFile, flushed.sequenceNumber - 1, durationSeconds, isInitSegment = false),
        )
        segmentFile.delete()
    }

    private fun copyAudioSamples(extractor: MediaExtractor, accumulator: SegmentAccumulator) {
        val bufferSize = 64 * 1024
        val buffer = java.nio.ByteBuffer.allocate(bufferSize)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            val data = ByteArray(sampleSize)
            buffer.position(0)
            buffer.get(data, 0, sampleSize)
            accumulator.addAudioSample(
                EncodedSample(
                    data = data,
                    presentationTimeUs = extractor.sampleTime,
                    durationUs = 0, // Duration calculated from sample rate in muxer
                    flags = 0,
                ),
            )
            extractor.advance()
            buffer.clear()
        }
    }

    private fun getFrameRate(format: MediaFormat): Int {
        return try {
            format.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (_: Exception) {
            DEFAULT_FRAME_RATE
        }
    }

    private fun buildCodecString(format: MediaFormat?, codec: VideoCodec): String {
        if (format == null) return if (codec == VideoCodec.H264) "avc1.640028" else "hev1.1.6.L93.B0"
        return try {
            when (codec) {
                VideoCodec.H264 -> {
                    val profile = format.getInteger(MediaFormat.KEY_PROFILE)
                    val level = format.getInteger(MediaFormat.KEY_LEVEL)
                    "avc1.%02X%02X%02X".format(
                        (profile and 0xFF),
                        0x00, // constraint flags
                        (level and 0xFF),
                    )
                }
                VideoCodec.H265 -> {
                    "hev1.1.6.L93.B0" // Default Main profile
                }
            }
        } catch (_: Exception) {
            if (codec == VideoCodec.H264) "avc1.640028" else "hev1.1.6.L93.B0"
        }
    }

    private class CancellationException : Exception("HLS preparation cancelled")

    companion object {
        private const val TAG = "HlsTranscoder"
        private const val TIMEOUT_US = 10_000L
        private const val NS_PER_US = 1000L
        private const val DEFAULT_FRAME_RATE = 30
    }
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add lightcompressor/src/main/java/com/davotoula/lightcompressor/hls/HlsTranscoder.kt
git commit -m "feat(hls): add HlsTranscoder for single-rendition fMP4 encoding"
```

---

## Task 10: HlsPreparer — Public Entry Point

**Files:**
- Create: `lightcompressor/src/main/java/com/davotoula/lightcompressor/HlsPreparer.kt`

Orchestrates multi-rendition encoding, manages coroutines, handles cancellation and the skip-and-continue error model.

- [ ] **Step 1: Implement HlsPreparer**

```kotlin
package com.davotoula.lightcompressor

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.davotoula.lightcompressor.hls.HlsConfig
import com.davotoula.lightcompressor.hls.HlsError
import com.davotoula.lightcompressor.hls.HlsListener
import com.davotoula.lightcompressor.hls.HlsTranscoder
import com.davotoula.lightcompressor.hls.PlaylistGenerator
import com.davotoula.lightcompressor.hls.Rendition
import com.davotoula.lightcompressor.hls.RenditionResult
import com.davotoula.lightcompressor.utils.CompressorUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Public entry point for HLS VOD preparation.
 *
 * Given a local video URI, transcodes it into multiple resolution renditions
 * as fMP4 segments with HLS playlists. Segments are emitted incrementally
 * via [HlsListener] callbacks.
 *
 * Usage:
 * ```
 * HlsPreparer.start(context, videoUri, HlsConfig()) { ... }
 * ```
 */
object HlsPreparer : CoroutineScope by MainScope() {

    private var currentJob: Job? = null
    private var transcoder: HlsTranscoder? = null

    /**
     * Start HLS preparation for a single video.
     *
     * @param context Android context
     * @param uri source video URI
     * @param config HLS configuration (ladder, codec, segment duration)
     * @param listener callbacks for progress, segments, and completion
     * @return [Job] that can be used to track completion
     */
    @JvmStatic
    fun start(
        context: Context,
        uri: Uri,
        config: HlsConfig = HlsConfig(),
        listener: HlsListener,
    ): Job {
        cancel() // Cancel any running preparation
        val job = launch(Dispatchers.IO) {
            prepareHls(context, uri, config, listener)
        }
        currentJob = job
        return job
    }

    /** Cancel any running HLS preparation. */
    @JvmStatic
    fun cancel() {
        transcoder?.isCancelled = true
        currentJob?.cancel()
        currentJob = null
        transcoder = null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prepareHls(
        context: Context,
        uri: Uri,
        config: HlsConfig,
        listener: HlsListener,
    ) {
        try {
            // Extract source metadata
            val sourceInfo = extractSourceInfo(context, uri) ?: run {
                withContext(Dispatchers.Main) {
                    listener.onFailure(
                        HlsError("Failed to read source video metadata", emptyList(), emptyList()),
                    )
                }
                return
            }

            // Filter ladder to source resolution
            val effectiveLadder = config.ladder.forSource(sourceInfo.shortSide)
            if (effectiveLadder.renditions.isEmpty()) {
                withContext(Dispatchers.Main) {
                    listener.onFailure(
                        HlsError(
                            "No renditions match source resolution (${sourceInfo.shortSide}p)",
                            emptyList(),
                            emptyList(),
                        ),
                    )
                }
                return
            }

            withContext(Dispatchers.Main) {
                listener.onStart(effectiveLadder.renditions.size)
            }

            val hlsTranscoder = HlsTranscoder(context, uri, config)
            transcoder = hlsTranscoder
            val tempDir = File(context.cacheDir, "hls_temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            val completed = mutableListOf<RenditionResult>()
            val failed = mutableListOf<Rendition>()

            try {
                for (rendition in effectiveLadder.renditions) {
                    if (hlsTranscoder.isCancelled) {
                        withContext(Dispatchers.Main) { listener.onCancelled() }
                        return
                    }

                    val (actualWidth, actualHeight) = calculateDimensions(
                        rendition, sourceInfo.width, sourceInfo.height,
                    )

                    withContext(Dispatchers.Main) {
                        listener.onRenditionStart(rendition)
                    }

                    val result = withContext(Dispatchers.Default) {
                        hlsTranscoder.encodeRendition(
                            rendition = rendition,
                            actualWidth = actualWidth,
                            actualHeight = actualHeight,
                            listener = listener,
                            tempDir = tempDir,
                        )
                    }

                    if (result != null) {
                        completed.add(result)
                    } else {
                        failed.add(rendition)
                        // TODO: Consider client-controlled failure handling where
                        // onRenditionFailure callback returns Continue/Abort
                    }
                }

                if (completed.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        listener.onFailure(
                            HlsError(
                                "All renditions failed",
                                failedRenditions = failed,
                                completedRenditions = emptyList(),
                            ),
                        )
                    }
                } else {
                    val masterPlaylist = PlaylistGenerator().buildMasterPlaylist(completed)
                    withContext(Dispatchers.Main) {
                        listener.onComplete(masterPlaylist)
                    }
                }
            } finally {
                tempDir.deleteRecursively()
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            withContext(Dispatchers.Main) { listener.onCancelled() }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                listener.onFailure(HlsError(e.message ?: "Unknown error", emptyList(), emptyList()))
            }
        } finally {
            transcoder = null
        }
    }

    private fun extractSourceInfo(context: Context, uri: Uri): SourceInfo? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val width = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
            )?.toIntOrNull() ?: return null
            val height = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
            )?.toIntOrNull() ?: return null
            val rotation = retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
            )?.toIntOrNull() ?: 0
            retriever.release()

            val (effectiveWidth, effectiveHeight) = if (rotation == 90 || rotation == 270) {
                height to width
            } else {
                width to height
            }

            SourceInfo(
                width = effectiveWidth,
                height = effectiveHeight,
                shortSide = min(effectiveWidth, effectiveHeight),
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Calculate actual output dimensions for a rendition, preserving source aspect ratio.
     * The rendition's resolution targets the short side.
     */
    internal fun calculateDimensions(
        rendition: Rendition,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Pair<Int, Int> {
        val targetShortSide = rendition.resolution.shortSide
        val isPortrait = sourceHeight > sourceWidth
        val aspectRatio = sourceWidth.toDouble() / sourceHeight.toDouble()

        val (width, height) = if (isPortrait) {
            val w = targetShortSide
            val h = (w / aspectRatio).toInt()
            w to h
        } else {
            val h = targetShortSide
            val w = (h * aspectRatio).toInt()
            w to h
        }

        // Round to even (MediaCodec requirement)
        return (width and 1.inv()) to (height and 1.inv())
    }

    private data class SourceInfo(
        val width: Int,
        val height: Int,
        val shortSide: Int,
    )
}
```

- [ ] **Step 2: Build to verify compilation**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew :lightcompressor:assembleDebug 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add lightcompressor/src/main/java/com/davotoula/lightcompressor/HlsPreparer.kt
git commit -m "feat(hls): add HlsPreparer public entry point with multi-rendition orchestration"
```

---

## Task 11: Full Build Verification and Test Suite

**Files:** All new and modified files.

- [ ] **Step 1: Run full build**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew assembleDebug 2>&1 | tail -10`
Expected: BUILD SUCCESSFUL (both modules).

- [ ] **Step 2: Run all unit tests**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew testDebugUnitTest 2>&1 | tail -10`
Expected: All tests PASS (existing + new).

- [ ] **Step 3: Run ktlint**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew ktlintCheck 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. If violations found, run `./gradlew ktlintFormat` and fix manually.

- [ ] **Step 4: Run detekt**

Run: `cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls && ./gradlew detekt 2>&1 | tail -5`
Expected: BUILD SUCCESSFUL. If violations found, add targeted `@Suppress` annotations or refactor.

- [ ] **Step 5: Commit any lint/format fixes**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add -A
git commit -m "style: fix ktlint and detekt violations in HLS code"
```

---

## Task 12: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Add HLS section to CLAUDE.md**

Add after the existing "Compression pipeline" section in the Library architecture:

```markdown
**HLS preparation pipeline:**
1. `HlsPreparer.start()` → extracts source metadata, filters encoding ladder
2. `HlsTranscoder` — encodes each rendition sequentially (lowest-first) using Surface/GL pipeline
3. `SegmentAccumulator` — detects keyframe-aligned segment boundaries in encoder output
4. `Mp4SegmentWriter` / `BoxWriter` — writes fMP4 init and media segments (ISO 14496-12)
5. `PlaylistGenerator` — builds VOD m3u8 playlists as strings
6. Segments emitted via `HlsListener.onSegmentReady()` callback; temp files deleted after callback returns
```

Add to the Testing section:

```markdown
- `BoxWriterTest` — ISO BMFF box encoding
- `Mp4SegmentWriterTest` — fMP4 init and media segment structure
- `HlsConfigTest` — encoding ladder configuration
- `PlaylistGeneratorTest` — m3u8 playlist generation
- `PlaylistRewriterTest` — URL remapping
- `SegmentAccumulatorTest` — keyframe boundary detection
```

- [ ] **Step 2: Commit**

```bash
cd /Users/david/StudioProjects/LightCompressor-fork/LightCompressor-hls
git add CLAUDE.md
git commit -m "docs: add HLS preparation architecture to CLAUDE.md"
```

---

## Task Summary

| Task | Component | Type | Dependencies |
|------|-----------|------|-------------|
| 1 | BoxWriter | Foundation | None |
| 2 | Mp4SegmentWriter init | Foundation | Task 1 |
| 3 | Mp4SegmentWriter media | Foundation | Task 2 |
| 4 | Resolution enum | Refactor | None |
| 5 | HLS API types | API | Task 4 |
| 6 | PlaylistGenerator | Feature | Task 5 |
| 7 | PlaylistRewriter | Feature | None |
| 8 | SegmentAccumulator | Feature | Task 3 |
| 9 | HlsTranscoder | Core | Tasks 3, 5, 6, 8 |
| 10 | HlsPreparer | Entry point | Task 9 |
| 11 | Build verification | Verification | All |
| 12 | CLAUDE.md update | Docs | All |
