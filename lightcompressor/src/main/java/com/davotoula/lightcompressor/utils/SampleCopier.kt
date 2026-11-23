package com.davotoula.lightcompressor.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.IOException
import java.io.InterruptedIOException
import java.nio.ByteBuffer

/** The largest `KEY_MAX_INPUT_SIZE` among [formats], and at least [floor]: a starting size for [copySamples]. */
internal fun sampleBufferSize(
    formats: Collection<MediaFormat>,
    floor: Int,
): Int =
    maxOf(
        floor,
        formats.maxOfOrNull {
            if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
        } ?: 0,
    )

/** Throws [InterruptedIOException] when the thread is interrupted, the only way to stop a long copy; keeps the flag. */
internal fun throwIfInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Interrupted reading samples")
}

/**
 * A reusable buffer for the extractor's current sample. From API 28 (`getSampleSize`) it grows for a larger
 * sample; below it the initial size has to be enough, since `readSampleData` throws on a buffer that is too small.
 */
internal class SampleBuffer(
    initialSize: Int,
    // A parameter so JVM tests, where SDK_INT is 0, can reach the growth path.
    private val canPeekSampleSize: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P,
) {
    /** The last sample [read], from position 0. Replaced when it grows, so read it after each [read]. */
    var data: ByteBuffer = ByteBuffer.allocateDirect(initialSize)
        private set

    /** Reads [extractor]'s current sample into [data]: its size, or < 0 at the end of the stream. */
    fun read(extractor: MediaExtractor): Int {
        if (canPeekSampleSize) {
            val nextSampleSize = extractor.sampleSize
            if (nextSampleSize > data.capacity()) data = ByteBuffer.allocateDirect(nextSampleSize.toInt())
        }
        data.clear()
        return extractor.readSampleData(data, 0)
    }
}

/** What [copySamples] does with a sample whose time is negative. */
internal enum class NegativeTime {
    /** End the copy there, as Transcoder's audio passthrough always has. */
    STOP,

    /**
     * Write it. Audio uses this: a negative time there is encoder priming (one AAC frame, ~21 ms, in ffmpeg
     * output), and `MediaMuxer` keeps it behind an edit list.
     */
    COPY,

    /**
     * Throw. Video uses this: a negative time there is a stream-copy trim whose edit list starts mid-GOP,
     * and `MediaMuxer` would re-base those frames to 0 and show the trimmed-out part.
     */
    REJECT,
}

/**
 * Copies every sample of [extractor]'s selected tracks into [muxer], unchanged, until the extractor runs out.
 *
 * [muxerTracks] maps an extractor track index to its muxer track, and [negativeTime] says per extractor track
 * what a negative sample time does. The buffer starts at [initialBufferSize] and grows as [SampleBuffer] does.
 *
 * @return the number of samples written, indexed like [muxerTracks].
 * @throws InterruptedIOException when the calling thread is interrupted.
 * @throws IOException at a negative sample time on a [NegativeTime.REJECT] track.
 */
internal fun copySamples(
    extractor: MediaExtractor,
    muxer: MediaMuxer,
    muxerTracks: IntArray,
    initialBufferSize: Int,
    negativeTime: (track: Int) -> NegativeTime = { NegativeTime.STOP },
): IntArray {
    val written = IntArray(muxerTracks.size)
    val sample = SampleBuffer(initialBufferSize)
    val bufferInfo = MediaCodec.BufferInfo()
    while (true) {
        throwIfInterrupted()
        val sampleSize = sample.read(extractor)
        val track = extractor.sampleTrackIndex
        val sampleTime = extractor.sampleTime
        // readSampleData < 0 is the end of the stream; checked first, since the sample time is then -1 too.
        if (sampleSize < 0 || !isCopyable(sampleTime, track, negativeTime)) break
        bufferInfo.set(0, sampleSize, sampleTime, codecFlags(extractor.sampleFlags))
        muxer.writeSampleData(muxerTracks[track], sample.data, bufferInfo)
        written[track]++
        extractor.advance()
    }
    return written
}

/** Whether to write a sample at [sampleTime]; false stops the copy. Throws for [NegativeTime.REJECT]. */
private fun isCopyable(
    sampleTime: Long,
    track: Int,
    negativeTime: (track: Int) -> NegativeTime,
): Boolean =
    sampleTime >= 0 ||
        when (negativeTime(track)) {
            NegativeTime.STOP -> false
            NegativeTime.COPY -> true
            NegativeTime.REJECT -> throw IOException(
                "Track $track has a sample at $sampleTime us, before its edit list starts (a stream-copy " +
                    "trim); remuxing would show the trimmed-out frames",
            )
        }

/** Maps `MediaExtractor` sample flags to the `MediaCodec` buffer flags `MediaMuxer.writeSampleData` takes. */
internal fun codecFlags(extractorFlags: Int): Int {
    var codecFlags = 0
    if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0
    ) {
        codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    }
    return codecFlags
}
