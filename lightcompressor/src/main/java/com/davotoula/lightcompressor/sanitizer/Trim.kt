package com.davotoula.lightcompressor.sanitizer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import com.davotoula.lightcompressor.utils.SampleBuffer
import com.davotoula.lightcompressor.utils.codecFlags
import com.davotoula.lightcompressor.utils.throwIfInterrupted
import java.io.IOException
import java.nio.ByteBuffer

/** The most video frames held past a trim's end: deeper than any B-frame reordering a camera writes. */
internal const val MAX_HELD_FRAMES = 8

/**
 * Where a trim cuts. [baseUs] is the source time written as 0: the video key frame at or before the start
 * (negative only in a source whose edit list starts mid-GOP), or for audio-only the first audio sample, never
 * below 0. Every track is shifted by the same [baseUs], so audio and video stay in sync. [videoTrack] is -1 for
 * audio-only.
 */
internal class TrimWindow(
    val baseUs: Long,
    val endUs: Long,
    val videoTrack: Int,
) {
    /**
     * Where to seek the copying extractor, or null to read from the source's beginning. Not seeking keeps the
     * audio priming, which sits at negative times a seek to 0 or later skips.
     */
    val seekUs: Long? get() = baseUs.takeIf { it > 0 }

    /**
     * Whether the cut leaves out a sample of [track] at [timeUs] at the start. Video before the base (open-GOP
     * leading pictures) can't decode without the frames before it. Audio before a base at or below 0 is the
     * source's encoder priming: written at its still negative time, `MediaMuxer` keeps it behind an edit list.
     */
    fun isBeforeStart(
        track: Int,
        timeUs: Long,
    ): Boolean = timeUs < baseUs && (track == videoTrack || baseUs > 0)
}

/** What [planTrim] decided: the cut, and the extractor tracks with samples in it. */
internal class TrimPlan(
    val window: TrimWindow,
    val tracks: Set<Int>,
)

/**
 * Plans a trim of [tracks] (extractor indices, [videoTracks] among them) to [trim], reading [probe], an
 * extractor of the same source with no track selected. Probing with its own extractor leaves the copying one
 * unread, so it can still start at the source's beginning. Reads a few samples per track: a GOP at most.
 *
 * A track with no sample in the cut is left out of the plan, since `MediaMuxer` would otherwise write an empty
 * track (or fail to stop); the clock's track (the video, else the first audio) always has one.
 *
 * @throws IOException when there is more than one video track, or [trim] starts after the clock's track ends.
 */
internal fun planTrim(
    probe: MediaExtractor,
    tracks: Set<Int>,
    videoTracks: Set<Int>,
    trim: TrimRange,
): TrimPlan {
    if (videoTracks.size > 1) throw IOException("A trim needs one video track; the source has ${videoTracks.size}")
    val videoTrack = videoTracks.firstOrNull() ?: -1
    val clockTrack = videoTracks.firstOrNull() ?: tracks.first()
    val firstUs = probeFirstSample(probe, clockTrack, trim)
    val window = TrimWindow(if (videoTrack >= 0) firstUs else maxOf(firstUs, 0), trim.endUs, videoTrack)
    val kept = tracks.filter { it == clockTrack || hasSampleInCut(probe, it, window) }
    return TrimPlan(window, kept.toSet())
}

/** The time of [track]'s sync sample at or before [trim]'s start. Throws when no sample is at or after the start. */
private fun probeFirstSample(
    probe: MediaExtractor,
    track: Int,
    trim: TrimRange,
): Long {
    probe.selectTrack(track)
    probe.seekTo(trim.startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    if (probe.sampleTrackIndex < 0) throw IOException("Track $track has no samples")
    val firstUs = probe.sampleTime
    // A start past the end would otherwise keep the last GOP: a clip of something the range doesn't cover.
    while (probe.sampleTime < trim.startUs) {
        throwIfInterrupted()
        if (!probe.advance()) throw IOException("Trim start ${trim.startMs} ms is after the end of track $track")
    }
    probe.unselectTrack(track)
    return firstUs
}

/** Whether [track] has a sample [copyTrimmed] would write in [window], reading from where the copy starts. */
private fun hasSampleInCut(
    probe: MediaExtractor,
    track: Int,
    window: TrimWindow,
): Boolean {
    probe.selectTrack(track)
    probe.seekTo(window.seekUs ?: 0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    while (probe.sampleTrackIndex >= 0 && window.isBeforeStart(track, probe.sampleTime)) {
        throwIfInterrupted()
        probe.advance()
    }
    val hasSample = probe.sampleTrackIndex >= 0 && probe.sampleTime <= window.endUs
    probe.unselectTrack(track)
    return hasSample
}

/**
 * Copies [extractor]'s selected tracks into [muxer] within [window], shifted so [TrimWindow.baseUs] is 0. The
 * extractor must be at [TrimWindow.seekUs] already (or unread, when that is null). Samples are copied unchanged,
 * as [com.davotoula.lightcompressor.utils.copySamples] does, with its interrupt check and buffer growth.
 *
 * Past the end, up to [MAX_HELD_FRAMES] video non-key frames are held and written only if a frame at or before
 * the end follows them: a B-frame decodes after the frame it points forward to. A track stops at its first sample
 * past the end that isn't held: for video a key frame, or the frame after a full hold.
 *
 * @return the number of samples written, indexed like [muxerTracks].
 */
internal fun copyTrimmed(
    extractor: MediaExtractor,
    muxer: MediaMuxer,
    muxerTracks: IntArray,
    initialBufferSize: Int,
    window: TrimWindow,
): IntArray = TrimmedCopy(extractor, muxer, muxerTracks, initialBufferSize, window).run()

private class TrimmedCopy(
    private val extractor: MediaExtractor,
    private val muxer: MediaMuxer,
    private val muxerTracks: IntArray,
    initialBufferSize: Int,
    private val window: TrimWindow,
) {
    private val written = IntArray(muxerTracks.size)
    private val sample = SampleBuffer(initialBufferSize)
    private val info = MediaCodec.BufferInfo()
    private val held = ArrayList<HeldSample>()

    fun run(): IntArray {
        var more = true
        while (more) more = copyNext()
        return written
    }

    /** Handles the current sample; false at the end of the stream. */
    private fun copyNext(): Boolean {
        throwIfInterrupted()
        val track = extractor.sampleTrackIndex
        val timeUs = extractor.sampleTime
        return when {
            track < 0 -> false
            window.isBeforeStart(track, timeUs) -> {
                extractor.advance()
                true
            }
            timeUs > window.endUs -> {
                pastEnd(track, timeUs)
                true
            }
            else -> write(track, timeUs)
        }
    }

    /** Holds a video non-key frame past the end while there is room; otherwise stops the track. */
    private fun pastEnd(
        track: Int,
        timeUs: Long,
    ) {
        val flags = extractor.sampleFlags
        val holdable = track == window.videoTrack && flags and MediaExtractor.SAMPLE_FLAG_SYNC == 0
        val size = if (holdable && held.size < MAX_HELD_FRAMES) sample.read(extractor) else -1
        if (size >= 0) {
            val bytes = ByteArray(size)
            sample.data
                .duplicate()
                .apply {
                    position(0)
                    limit(size)
                }.get(bytes)
            held += HeldSample(bytes, timeUs, codecFlags(flags))
            extractor.advance()
        } else {
            // No frame before the end came back for the held run: it is not needed.
            if (track == window.videoTrack) held.clear()
            extractor.unselectTrack(track)
        }
    }

    /** Writes the current sample, after the held frames it follows; false at the end of the stream. */
    private fun write(
        track: Int,
        timeUs: Long,
    ): Boolean {
        val flags = extractor.sampleFlags
        val size = sample.read(extractor)
        if (size < 0) return false
        if (track == window.videoTrack) {
            held.forEach { heldSample ->
                info.set(0, heldSample.bytes.size, heldSample.timeUs - window.baseUs, heldSample.flags)
                muxer.writeSampleData(muxerTracks[track], ByteBuffer.wrap(heldSample.bytes), info)
                written[track]++
            }
            held.clear()
        }
        info.set(0, size, timeUs - window.baseUs, codecFlags(flags))
        muxer.writeSampleData(muxerTracks[track], sample.data, info)
        written[track]++
        extractor.advance()
        return true
    }
}

/** A video frame read past the trim's end, held in case a frame before the end needs it. */
private class HeldSample(
    val bytes: ByteArray,
    val timeUs: Long,
    val flags: Int,
)
