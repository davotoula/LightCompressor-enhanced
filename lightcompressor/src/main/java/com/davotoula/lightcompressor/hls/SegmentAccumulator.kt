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
        if (isKeyFrame && accumulatedDurationUs + sample.durationUs >= targetSegmentDurationUs) {
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

        val flushed =
            FlushedSegment(
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
        val flushed =
            FlushedSegment(
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
