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
        var pts = 0L
        val frameDuration = 33_333L
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        pts += frameDuration
        while (pts < segmentDurationUs) {
            acc.addVideoSample(frameSample(pts, frameDuration))
            pts += frameDuration
        }
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
    fun `audio samples included in flushed segment at keyframe boundary`() {
        val acc = SegmentAccumulator(segmentDurationUs)
        val frameDuration = 33_333L
        val audioDuration = 21_333L // ~1024 samples at 48kHz
        var videoPts = 0L
        var audioPts = 0L

        // Add first keyframe
        acc.addVideoSample(keyframeSample(videoPts, frameDuration))
        videoPts += frameDuration

        // Add video and audio samples until segment duration
        while (videoPts < segmentDurationUs) {
            acc.addVideoSample(frameSample(videoPts, frameDuration))
            videoPts += frameDuration
            // Add audio samples up to video PTS (mimics the fix behavior)
            while (audioPts < videoPts) {
                acc.addAudioSample(audioSample(audioPts, audioDuration))
                audioPts += audioDuration
            }
        }

        // Trigger flush with next keyframe
        acc.addVideoSample(keyframeSample(videoPts, frameDuration))
        val flushed = acc.flushIfReady()!!

        assertTrue("Flushed segment should contain video samples", flushed.videoSamples.isNotEmpty())
        assertTrue("Flushed segment should contain audio samples", flushed.audioSamples.isNotEmpty())
        assertTrue("Audio sample count should be reasonable", flushed.audioSamples.size > 100)
    }

    @Test
    fun `audio samples cleared after flush`() {
        val acc = SegmentAccumulator(1_000_000L) // 1 second for faster test
        val frameDuration = 33_333L

        // Build first segment
        var pts = 0L
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        acc.addAudioSample(audioSample(0L, 21_333L))
        acc.addAudioSample(audioSample(21_333L, 21_333L))
        pts += frameDuration
        while (pts < 1_000_000L) {
            acc.addVideoSample(frameSample(pts, frameDuration))
            pts += frameDuration
        }
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        val first = acc.flushIfReady()!!
        assertEquals(2, first.audioSamples.size)

        // Second segment should start fresh (no audio added yet)
        pts += frameDuration
        while (pts < 2_000_000L) {
            acc.addVideoSample(frameSample(pts, frameDuration))
            pts += frameDuration
        }
        acc.addVideoSample(keyframeSample(pts, frameDuration))
        val second = acc.flushIfReady()!!
        assertEquals("Second segment should have no audio (none added)", 0, second.audioSamples.size)
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

    private fun keyframeSample(
        pts: Long,
        duration: Long,
    ) = EncodedSample(
        data = ByteArray(1000),
        presentationTimeUs = pts,
        durationUs = duration,
        flags = 1, // KEY_FRAME
    )

    private fun frameSample(
        pts: Long,
        duration: Long,
    ) = EncodedSample(
        data = ByteArray(200),
        presentationTimeUs = pts,
        durationUs = duration,
        flags = 0,
    )

    private fun audioSample(
        pts: Long,
        duration: Long,
    ) = EncodedSample(
        data = ByteArray(50),
        presentationTimeUs = pts,
        durationUs = duration,
        flags = 0,
    )
}
