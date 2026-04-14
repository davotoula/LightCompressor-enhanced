package com.davotoula.lightcompressor.hls

import android.util.Log
import com.davotoula.lightcompressor.muxer.Mp4SegmentWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Strategy interface for emitting fMP4 output of one rendition.
 *
 * Two implementations exist:
 * - [MultiFileSegmentSink] — writes init.mp4 plus one .m4s file per media segment, fires a
 *   listener callback per file, deletes each temp file after the callback returns. Builds a
 *   conventional HLS media playlist that references each segment by filename.
 * - [SingleFileSegmentSink] — concatenates the init segment and every media segment into one
 *   `<label>.mp4` file, defers the listener callback until [finish], and builds a media playlist
 *   that references each segment by `#EXT-X-BYTERANGE` inside that combined file.
 *
 * Both sinks share the same lifecycle:
 * 1. [writeInit] once after the encoder reports its output format
 * 2. [writeMedia] once per flushed segment from the encoder loop
 * 3. [finish] to emit any deferred callback and close file handles
 * 4. [buildPlaylist] to obtain the m3u8 string for this rendition
 *
 * **Important:** In [SingleFileSegmentSink], the combined file is deleted immediately after
 * the [HlsListener.onSegmentReady] callback returns. Listeners must copy or upload the file
 * synchronously — async/deferred patterns will fail because the file will no longer exist.
 *
 * [close] is the safety net invoked from `finally`; it must not throw and is safe to call
 * multiple times. It releases held resources without emitting any callbacks.
 */
internal sealed class SegmentSink(
    protected val rendition: Rendition,
    protected val listener: HlsListener,
    protected val tempDir: File,
) {
    abstract fun writeInit(writer: Mp4SegmentWriter)

    abstract fun writeMedia(
        writer: Mp4SegmentWriter,
        flushed: FlushedSegment,
    )

    abstract fun buildPlaylist(targetDurationSeconds: Int): String

    abstract fun finish()

    abstract fun close()

    /** Number of media segments observed so far. Used to pick a sensible target duration. */
    abstract val mediaSegmentCount: Int

    /** Maximum observed segment duration, in seconds. Used by the playlist target duration. */
    abstract val maxSegmentDurationSeconds: Double

    // Shared constants for subclasses (companion cannot be protected, so using internal)
    protected companion object {
        const val TAG = "SegmentSink"
        const val MICROS_PER_SECOND = 1_000_000.0
    }
}

/**
 * Multi-file sink: one init.mp4 + one .m4s per media segment, each emitted to the listener
 * inline. Matches the original HLS output layout.
 */
internal class MultiFileSegmentSink(
    rendition: Rendition,
    listener: HlsListener,
    tempDir: File,
) : SegmentSink(rendition, listener, tempDir) {
    private val segments = mutableListOf<SegmentInfo>()

    override val mediaSegmentCount: Int
        get() = segments.size

    override val maxSegmentDurationSeconds: Double
        get() = segments.maxOfOrNull { it.durationSeconds } ?: 0.0

    override fun writeInit(writer: Mp4SegmentWriter) {
        val initFile = File(tempDir, "init_${rendition.resolution.label}.mp4")
        FileOutputStream(initFile).use { fos ->
            writer.writeInitSegment(fos)
        }
        listener.onSegmentReady(
            rendition,
            HlsSegment(
                file = initFile,
                index = 0,
                durationSeconds = 0.0,
                isInitSegment = true,
            ),
        )
        if (initFile.exists() && !initFile.delete()) {
            Log.w(TAG, "Failed to delete init temp file: ${initFile.absolutePath}")
        }
    }

    override fun writeMedia(
        writer: Mp4SegmentWriter,
        flushed: FlushedSegment,
    ) {
        val filename = SEGMENT_FILENAME_FORMAT.format(flushed.sequenceNumber - 1)
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
        val durationSeconds = flushed.durationUs / MICROS_PER_SECOND
        segments.add(SegmentInfo(filename, durationSeconds))
        listener.onSegmentReady(
            rendition,
            HlsSegment(
                file = segmentFile,
                index = flushed.sequenceNumber - 1,
                durationSeconds = durationSeconds,
                isInitSegment = false,
            ),
        )
        if (segmentFile.exists() && !segmentFile.delete()) {
            Log.w(TAG, "Failed to delete segment temp file: ${segmentFile.absolutePath}")
        }
    }

    override fun buildPlaylist(targetDurationSeconds: Int): String =
        PlaylistGenerator.buildMediaPlaylist(
            segments = segments,
            targetDurationSeconds = targetDurationSeconds,
        )

    override fun finish() = Unit

    override fun close() = Unit

    private companion object {
        private const val SEGMENT_FILENAME_FORMAT = "segment_%03d.m4s"
    }
}

/**
 * Single-file sink: concatenates init + every media segment into one `<label>.mp4` and emits
 * the entire rendition via a single [HlsListener.onSegmentReady] call from [finish]. The
 * resulting playlist references each segment by `#EXT-X-BYTERANGE` inside that combined file.
 */
internal class SingleFileSegmentSink(
    rendition: Rendition,
    listener: HlsListener,
    tempDir: File,
) : SegmentSink(rendition, listener, tempDir) {
    private val combinedFile = File(tempDir, "combined_${rendition.resolution.label}.mp4")
    private var counting: CountingOutputStream? = null
    private var initLength: Long = 0L
    private val byteRangeSegments = mutableListOf<ByteRangeSegment>()
    private var finished = false

    override val mediaSegmentCount: Int
        get() = byteRangeSegments.size

    override val maxSegmentDurationSeconds: Double
        get() = byteRangeSegments.maxOfOrNull { it.durationSeconds } ?: 0.0

    override fun writeInit(writer: Mp4SegmentWriter) {
        check(counting == null) { "writeInit called twice" }
        val counter = CountingOutputStream(FileOutputStream(combinedFile))
        counting = counter
        writer.writeInitSegment(counter)
        initLength = counter.bytesWritten
    }

    override fun writeMedia(
        writer: Mp4SegmentWriter,
        flushed: FlushedSegment,
    ) {
        val counter =
            counting
                ?: error("writeMedia called before writeInit")
        val offset = counter.bytesWritten
        writer.writeMediaSegment(
            videoSamples = flushed.videoSamples,
            audioSamples = flushed.audioSamples,
            sequenceNumber = flushed.sequenceNumber,
            baseDecodeTimeUs = flushed.baseDecodeTimeUs,
            output = counter,
        )
        val length = counter.bytesWritten - offset
        val durationSeconds = flushed.durationUs / MICROS_PER_SECOND
        byteRangeSegments.add(
            ByteRangeSegment(
                durationSeconds = durationSeconds,
                offset = offset,
                length = length,
            ),
        )
    }

    override fun buildPlaylist(targetDurationSeconds: Int): String =
        PlaylistGenerator.buildByteRangeMediaPlaylist(
            combinedFilename = "${rendition.resolution.label}.mp4",
            initRangeLength = initLength,
            segments = byteRangeSegments,
            targetDurationSeconds = targetDurationSeconds,
        )

    override fun finish() {
        if (finished) return
        finished = true
        // Close the underlying file before we hand it to the listener so the consumer sees
        // every byte.
        closeStreams()
        val totalDurationSeconds = byteRangeSegments.sumOf { it.durationSeconds }
        listener.onSegmentReady(
            rendition,
            HlsSegment(
                file = combinedFile,
                index = 0,
                durationSeconds = totalDurationSeconds,
                isInitSegment = false,
                isCombinedRendition = true,
            ),
        )
        if (combinedFile.exists() && !combinedFile.delete()) {
            Log.w(TAG, "Failed to delete combined rendition temp file: ${combinedFile.absolutePath}")
        }
    }

    override fun close() {
        closeStreams()
    }

    private fun closeStreams() {
        runCatching { counting?.close() }
            .onFailure { Log.w(TAG, "Error closing counting stream: ${it.message}") }
        counting = null
    }
}

/**
 * Tiny [OutputStream] wrapper that counts bytes written to its delegate. Used by
 * [SingleFileSegmentSink] to derive byte-range offsets for each media segment without
 * relying on `File.length()` or `FileChannel.position()`.
 *
 * **Note:** This implementation assumes the delegate writes all requested bytes atomically
 * on success (as [FileOutputStream] does). If substituting a delegate that may perform
 * partial writes, the byte counting logic would need revision.
 */
internal class CountingOutputStream(
    private val delegate: OutputStream,
) : OutputStream() {
    var bytesWritten: Long = 0L
        private set

    override fun write(b: Int) {
        delegate.write(b)
        bytesWritten++
    }

    override fun write(b: ByteArray) {
        delegate.write(b)
        bytesWritten += b.size
    }

    override fun write(
        b: ByteArray,
        off: Int,
        len: Int,
    ) {
        delegate.write(b, off, len)
        bytesWritten += len
    }

    override fun flush() {
        delegate.flush()
    }

    override fun close() {
        delegate.close()
    }
}
