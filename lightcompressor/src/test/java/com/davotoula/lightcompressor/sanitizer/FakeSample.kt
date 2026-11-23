package com.davotoula.lightcompressor.sanitizer

import android.media.MediaExtractor
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs

/** One sample of a [fakeExtractor] track: its presentation time, and whether it is a sync (key) frame. */
internal data class FakeSample(
    val timeUs: Long,
    val sync: Boolean = true,
)

/**
 * A `MediaExtractor` over [tracks] (each in decode order), behaving as the platform's does for the calls the
 * sanitizer makes: every track keeps its own read position, `seekTo` with `SEEK_TO_PREVIOUS_SYNC` moves each
 * selected track to its last sync sample at or before the time (its first sample when there is none), and the
 * current sample is the selected track's next one with the lowest time. [selected] are selected at the start.
 * Every sample reads as 10 bytes.
 */
internal fun fakeExtractor(
    tracks: List<List<FakeSample>>,
    selected: Set<Int> = emptySet(),
): MediaExtractor {
    val chosen = selected.toMutableSet()
    val cursors = IntArray(tracks.size)

    fun current(): Int =
        chosen
            .filter { cursors[it] < tracks[it].size }
            .minByOrNull { tracks[it][cursors[it]].timeUs } ?: -1

    fun currentSample(): FakeSample? = current().takeIf { it >= 0 }?.let { tracks[it][cursors[it]] }

    return mockk {
        every { trackCount } returns tracks.size
        every { selectTrack(any()) } answers { chosen += firstArg<Int>() }
        every { unselectTrack(any()) } answers { chosen -= firstArg<Int>() }
        every { seekTo(any(), MediaExtractor.SEEK_TO_PREVIOUS_SYNC) } answers {
            val timeUs = firstArg<Long>()
            chosen.forEach { track ->
                cursors[track] =
                    tracks[track].indexOfLast { it.sync && it.timeUs <= timeUs }.coerceAtLeast(0)
            }
        }
        every { sampleTrackIndex } answers { current() }
        every { sampleTime } answers { currentSample()?.timeUs ?: -1L }
        every { sampleFlags } answers { if (currentSample()?.sync == true) MediaExtractor.SAMPLE_FLAG_SYNC else 0 }
        every { readSampleData(any(), 0) } answers { if (current() >= 0) 10 else -1 }
        every { advance() } answers {
            val track = current()
            if (track >= 0) cursors[track]++
            current() >= 0
        }
        every { release() } just runs
    }
}

/** Video frames from [times] in ms, key frames where [keys] says, in the order given (decode order). */
internal fun videoMs(
    vararg times: Long,
    keys: Set<Long> = setOf(times.first()),
): List<FakeSample> = times.map { FakeSample(it * 1000, sync = it in keys) }

/** Audio samples (all sync) at [times] in us. */
internal fun audioUs(vararg times: Long): List<FakeSample> = times.map { FakeSample(it) }
