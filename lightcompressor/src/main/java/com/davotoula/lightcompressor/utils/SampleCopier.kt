package com.davotoula.lightcompressor.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
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
 * [muxerTracks] maps an extractor track index to its muxer track. The buffer starts at [initialBufferSize]
 * and, from API 28 (`getSampleSize`), grows for a larger sample; below it the initial size has to be enough,
 * since `readSampleData` throws on a buffer that is too small.
 *
 * @throws InterruptedIOException when the calling thread is interrupted.
 */
internal fun copySamples(
    extractor: MediaExtractor,
    muxer: MediaMuxer,
    muxerTracks: IntArray,
    initialBufferSize: Int,
) {
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
        val sampleTime = extractor.sampleTime
        if (sampleSize < 0 || sampleTime < 0) break
        bufferInfo.set(0, sampleSize, sampleTime, codecFlags(extractor.sampleFlags))
        muxer.writeSampleData(muxerTracks[extractor.sampleTrackIndex], buffer, bufferInfo)
        extractor.advance()
    }
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
