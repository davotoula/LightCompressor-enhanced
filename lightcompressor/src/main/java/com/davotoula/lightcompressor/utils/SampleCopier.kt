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

/**
 * Copies every sample of [extractor]'s selected tracks into [muxer], unchanged, until the extractor runs out.
 *
 * A negative sample time means the source's edit list starts after its first samples: a stream-copy trim
 * starting mid-GOP. `MediaMuxer` would re-base such samples to 0 and show the trimmed-out frames. With
 * [rejectNegativeTimes] the copy throws there; otherwise it stops there, as Transcoder's audio passthrough
 * always has.
 *
 * [muxerTracks] maps an extractor track index to its muxer track. The buffer starts at [initialBufferSize]
 * and, from API 28 (`getSampleSize`), grows for a larger sample; below it the initial size has to be enough,
 * since `readSampleData` throws on a buffer that is too small.
 *
 * @return the number of samples written, indexed like [muxerTracks].
 * @throws InterruptedIOException when the calling thread is interrupted.
 * @throws IOException at a negative sample time, with [rejectNegativeTimes].
 */
internal fun copySamples(
    extractor: MediaExtractor,
    muxer: MediaMuxer,
    muxerTracks: IntArray,
    initialBufferSize: Int,
    rejectNegativeTimes: Boolean = false,
): IntArray {
    val written = IntArray(muxerTracks.size)
    var buffer = ByteBuffer.allocateDirect(initialBufferSize)
    val bufferInfo = MediaCodec.BufferInfo()
    val canPeekSampleSize = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
    while (true) {
        // A thread interrupt is the only way to stop a long copy; the flag stays set for the caller.
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Sample copy interrupted")
        if (canPeekSampleSize) {
            val nextSampleSize = extractor.sampleSize
            if (nextSampleSize > buffer.capacity()) buffer = ByteBuffer.allocateDirect(nextSampleSize.toInt())
        }
        buffer.clear()
        val sampleSize = extractor.readSampleData(buffer, 0)
        val track = extractor.sampleTrackIndex
        val sampleTime = extractor.sampleTime
        // readSampleData < 0 is the end of the stream; checked first, since the sample time is then -1 too.
        if (sampleSize < 0 || !isCopyable(sampleTime, track, rejectNegativeTimes)) break
        bufferInfo.set(0, sampleSize, sampleTime, codecFlags(extractor.sampleFlags))
        muxer.writeSampleData(muxerTracks[track], buffer, bufferInfo)
        written[track]++
        extractor.advance()
    }
    return written
}

/** False to stop the copy at a negative [sampleTime]; throws there instead with [rejectNegativeTimes]. */
private fun isCopyable(
    sampleTime: Long,
    track: Int,
    rejectNegativeTimes: Boolean,
): Boolean {
    if (sampleTime < 0 && rejectNegativeTimes) {
        throw IOException(
            "Track $track has a sample at $sampleTime us, before its edit list starts (a stream-copy trim); " +
                "remuxing would show the trimmed-out frames",
        )
    }
    return sampleTime >= 0
}

/** Maps `MediaExtractor` sample flags to the `MediaCodec` buffer flags `MediaMuxer.writeSampleData` takes. */
private fun codecFlags(extractorFlags: Int): Int {
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
