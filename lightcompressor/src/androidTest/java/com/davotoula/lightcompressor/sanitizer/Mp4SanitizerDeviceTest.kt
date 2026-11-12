package com.davotoula.lightcompressor.sanitizer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davotoula.lightcompressor.freshTestDir
import com.davotoula.lightcompressor.pushedClip
import com.davotoula.lightcompressor.utils.CompressorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Runs [Mp4Sanitizer.sanitize] on the camera clip `orig2.mp4` (push it to the test app's external files
 * dir first, as for `KeepHdrRetryTest`) and on an `.m4a` extracted from it.
 */
@RunWith(AndroidJUnit4::class)
class Mp4SanitizerDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun video_isFastStartWithoutUserDataAndKeepsTracksDurationAndRotation() {
        val source = source()
        val sourceHash = sha256(source)
        val output = File(freshDir("sanitize_video"), "orig2_clean.mp4")

        val result = Mp4Sanitizer.sanitize(context, Uri.fromFile(source), output)

        Log.i(TAG, "source boxes: ${boxTree(source)}")
        Log.i(TAG, "output boxes: ${boxTree(output)}")
        assertEquals(output, result.file)
        assertTrue("result says not fast-start", result.fastStart)
        assertTrue("output is not fast-start", Mp4Sanitizer.isFastStart(output))
        assertEquals(listOf("ftyp", "moov"), topLevelBoxes(output).take(2))
        val boxes = boxTree(output)
        assertFalse("output has udta: $boxes", "udta" in boxes)
        // MediaMuxer writes its own meta box with the OS version; nothing else may survive in one.
        assertEquals("meta keys", listOf("com.android.version"), metaKeys(output))
        val sourceMimes = trackMimes(source)
        val outputMimes = trackMimes(output)
        assertEquals(
            "audio/video tracks",
            sourceMimes.filter { it.startsWith("audio/") || it.startsWith("video/") },
            outputMimes,
        )
        assertFalse("output kept a metadata track: $outputMimes", outputMimes.any { it.startsWith("application/") })
        val sourceMs = durationMs(source)
        val outputMs = durationMs(output)
        Log.i(TAG, "duration source=$sourceMs ms output=$outputMs ms")
        assertTrue("duration $outputMs ms vs source $sourceMs ms", abs(outputMs - sourceMs) <= DURATION_TOLERANCE_MS)
        assertEquals("rotation", rotation(source), rotation(output))
        assertEquals("source clip changed", sourceHash, sha256(source))
        assertFalse("muxer temp file left behind", File(output.path + ".mux.tmp").exists())
    }

    @Test
    fun audio_m4aIsFastStartWithOneSoundTrack() {
        val source = source()
        val sourceHash = sha256(source)
        val outDir = freshDir("sanitize_audio")
        val m4a = File(outDir, "orig2_audio.m4a")
        extractAudio(source, m4a)
        val output = File(outDir, "orig2_audio_clean.m4a")

        val result = Mp4Sanitizer.sanitize(context, Uri.fromFile(m4a), output)

        Log.i(TAG, "m4a boxes: ${boxTree(m4a)}")
        Log.i(TAG, "clean m4a boxes: ${boxTree(output)}")
        assertTrue("result says not fast-start", result.fastStart)
        assertTrue("output is not fast-start", Mp4Sanitizer.isFastStart(output))
        assertEquals("handler types", listOf("soun"), handlerTypes(output))
        assertEquals("source clip changed", sourceHash, sha256(source))
    }

    /**
     * `trimmed_edit_list.mp4` (an androidTest asset) is a 4 s H.264 + AAC clip stream-copied from 1.5 s with ffmpeg
     * (`-ss 1.5 -i src.mp4 -c copy`, plus title and location tags), so its edit list starts mid-GOP and the
     * extractor reports the lead-in samples with negative times. Treating those as the end of the stream once
     * produced an output with no tracks that still reported success; copying the video ones makes MediaMuxer show
     * the trimmed-out frames. Sanitize refuses such a source.
     */
    @Test
    fun video_trimmedStreamCopy_throwsAndLeavesNoOutput() {
        val outDir = freshDir("sanitize_edit_list")
        val source = asset("trimmed_edit_list.mp4", outDir)
        val output = File(outDir, "trimmed_clean.mp4")

        val error =
            assertThrows(IOException::class.java) {
                Mp4Sanitizer.sanitize(context, Uri.fromFile(source), output)
            }

        Log.i(TAG, "trimmed source: ${error.message}")
        assertTrue(error.message, error.message!!.contains("edit list"))
        assertFalse("output exists", output.exists())
        assertFalse("muxer temp file left behind", File(output.path + ".mux.tmp").exists())
    }

    /**
     * `bframes_aac_priming.mp4` (an androidTest asset) is a plain, untrimmed 3 s ffmpeg encode: H.264 with B-frames
     * and AAC (`-f lavfi -i testsrc … -i sine … -t 3 -c:v libx264 -bf 3 -c:a aac`). Its first AAC frame is encoder
     * priming that the edit list skips, so the extractor reports it at -21333 us. Rejecting every negative time
     * refused this ordinary file; the priming must be kept, at the same time, and nothing else changed.
     */
    @Test
    fun video_aacPriming_isKept() {
        val outDir = freshDir("sanitize_priming")
        val source = asset("bframes_aac_priming.mp4", outDir)
        val output = File(outDir, "priming_clean.mp4")

        val result = Mp4Sanitizer.sanitize(context, Uri.fromFile(source), output)

        assertTrue("result says not fast-start", result.fastStart)
        assertEquals("tracks", trackMimes(source), trackMimes(output))
        val sourceTimes = firstSampleTimes(source)
        Log.i(TAG, "priming first sample times: source=$sourceTimes output=${firstSampleTimes(output)}")
        assertTrue("source has no priming sample: $sourceTimes", sourceTimes.any { it < 0 })
        assertEquals("first sample time per track", sourceTimes, firstSampleTimes(output))
        assertEquals("samples per track", sampleCounts(source), sampleCounts(output))
        val sourceMs = durationMs(source)
        val outputMs = durationMs(output)
        assertTrue("duration $outputMs ms vs source $sourceMs ms", abs(outputMs - sourceMs) <= DURATION_TOLERANCE_MS)
    }

    /** Copies the androidTest asset [name] into [dir]. */
    private fun asset(
        name: String,
        dir: File,
    ): File =
        File(dir, name).also { file ->
            InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
        }

    private fun firstSampleTimes(file: File): List<Long> = perTrack(file) { it.sampleTime }

    private fun sampleCounts(file: File): List<Int> =
        perTrack(file) { extractor ->
            var count = 0
            while (extractor.sampleTrackIndex >= 0) {
                count++
                extractor.advance()
            }
            count
        }

    /** [read] on each track in turn, with a fresh extractor so no seek skips samples before time 0. */
    private fun <T> perTrack(
        file: File,
        read: (MediaExtractor) -> T,
    ): List<T> =
        trackMimes(file).indices.map { track ->
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(file.path)
                extractor.selectTrack(track)
                read(extractor)
            } finally {
                extractor.release()
            }
        }

    private fun source(): File = pushedClip(context, "orig2.mp4")

    private fun freshDir(name: String): File = freshTestDir(context, name)

    /** Copies the first audio track of [source] into [m4a] with the platform muxer. */
    private fun extractAudio(
        source: File,
        m4a: File,
    ) {
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(m4a.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            extractor.setDataSource(source.path)
            val track = CompressorUtils.findTrack(extractor, isVideo = false)
            extractor.selectTrack(track)
            val muxerTrack = muxer.addTrack(extractor.getTrackFormat(track))
            muxer.start()
            val buffer = ByteBuffer.allocateDirect(BUFFER_SIZE)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, extractor.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(muxerTrack, buffer, info)
                extractor.advance()
            }
            muxer.stop()
        } finally {
            muxer.release()
            extractor.release()
        }
    }

    private fun trackMimes(file: File): List<String> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val mimes = (0 until extractor.trackCount).map { CompressorUtils.trackMime(extractor, it)!! }
            Log.i(TAG, "${file.name} tracks: $mimes")
            return mimes
        } finally {
            extractor.release()
        }
    }

    /** The `mdta` key names in the `keys` box of every `meta` box under `moov`. */
    private fun metaKeys(file: File): List<String> =
        RandomAccessFile(file, "r").use { raf ->
            val keys = mutableListOf<String>()
            for (meta in boxes(raf).filter { it.type == "meta" }) {
                val keysBox =
                    boxesIn(raf, childStart(raf, meta), meta.end).firstOrNull { it.type == "keys" }
                        ?: continue
                // keys: version/flags, entry_count, then (key_size, namespace, name) entries.
                raf.seek(keysBox.start + keysBox.headerSize + FULL_BOX_HEADER)
                repeat(raf.readInt()) {
                    val keySize = raf.readInt()
                    raf.skipBytes(4)
                    keys += String(ByteArray(keySize - 8).also { raf.readFully(it) }, Charsets.UTF_8)
                }
            }
            keys
        }

    private fun durationMs(file: File): Long = metadata(file, MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()

    private fun rotation(file: File): String? = metadata(file, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)

    private fun metadata(
        file: File,
        key: Int,
    ): String? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.path)
            return retriever.extractMetadata(key)
        } finally {
            retriever.release()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class Box(
        val type: String,
        val start: Long,
        val headerSize: Long,
        val size: Long,
    ) {
        val end get() = start + size
    }

    /** The single child of [parent] with [type]. */
    private fun child(
        raf: RandomAccessFile,
        parent: Box,
        type: String,
    ): Box = boxesIn(raf, childStart(raf, parent), parent.end).single { it.type == type }

    private fun boxesIn(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
    ): List<Box> {
        val boxes = mutableListOf<Box>()
        var offset = start
        while (offset + HEADER_SIZE <= end) {
            raf.seek(offset)
            var size = raf.readInt().toLong() and UINT32_MASK
            val typeBytes = ByteArray(4).also { raf.readFully(it) }
            var headerSize = HEADER_SIZE
            when (size) {
                0L -> size = end - offset
                1L -> {
                    size = raf.readLong()
                    headerSize = LARGE_HEADER_SIZE
                }
            }
            if (size < headerSize || offset + size > end) break
            boxes += Box(String(typeBytes, Charsets.ISO_8859_1), offset, headerSize, size)
            offset += size
        }
        return boxes
    }

    private fun topLevelBoxes(file: File): List<String> =
        RandomAccessFile(file, "r").use { raf -> boxesIn(raf, 0, raf.length()).map { it.type } }

    /** Every box type in the file, depth first, descending into the containers that can hold user data. */
    private fun boxTree(file: File): List<String> =
        RandomAccessFile(file, "r").use { raf -> boxes(raf).map { it.type } }

    private fun boxes(raf: RandomAccessFile): List<Box> {
        val all = mutableListOf<Box>()

        fun walk(
            start: Long,
            end: Long,
        ) {
            for (box in boxesIn(raf, start, end)) {
                all += box
                if (box.type in CONTAINERS) walk(childStart(raf, box), box.end)
            }
        }
        walk(0, raf.length())
        return all
    }

    /**
     * Where [box]'s children start. An ISO `meta` is a full box (4 bytes of version/flags before its
     * children); the QuickTime-style `meta` MediaMuxer writes under `moov` is not: its `hdlr` follows the header.
     */
    private fun childStart(
        raf: RandomAccessFile,
        box: Box,
    ): Long {
        val start = box.start + box.headerSize
        if (box.type != "meta") return start
        raf.seek(start + 4)
        val firstChildType = String(ByteArray(4).also { raf.readFully(it) }, Charsets.ISO_8859_1)
        return if (firstChildType == "hdlr") start else start + FULL_BOX_HEADER
    }

    /** The `hdlr` handler type of each `trak`, in file order. */
    private fun handlerTypes(file: File): List<String> =
        RandomAccessFile(file, "r").use { raf ->
            val moov = boxesIn(raf, 0, raf.length()).single { it.type == "moov" }
            boxesIn(raf, childStart(raf, moov), moov.end)
                .filter { it.type == "trak" }
                .map { trak ->
                    val hdlr = child(raf, child(raf, trak, "mdia"), "hdlr")
                    // version/flags (4) and pre_defined (4) precede handler_type.
                    raf.seek(hdlr.start + hdlr.headerSize + FULL_BOX_HEADER + 4)
                    String(ByteArray(4).also { raf.readFully(it) }, Charsets.ISO_8859_1)
                }
        }

    private companion object {
        const val TAG = "Mp4SanitizerDeviceTest"
        const val DURATION_TOLERANCE_MS = 50
        const val BUFFER_SIZE = 1 shl 20
        const val HEADER_SIZE = 8L
        const val LARGE_HEADER_SIZE = 16L
        const val FULL_BOX_HEADER = 4L
        const val UINT32_MASK = 0xFFFFFFFFL
        val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "udta", "meta", "mvex")
    }
}
