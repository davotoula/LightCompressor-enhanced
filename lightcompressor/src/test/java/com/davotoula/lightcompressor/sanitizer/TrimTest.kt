package com.davotoula.lightcompressor.sanitizer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class TrimTest {
    /** A sample as the muxer got it: muxer track, presentation time, codec flags. */
    private data class Written(
        val track: Int,
        val timeUs: Long,
        val flags: Int = 0,
    )

    private val written = mutableListOf<Written>()
    private val muxer = mockk<MediaMuxer>()

    @Before
    fun setUp() {
        // BufferInfo is a stub on the JVM, and under mockkConstructor `self` is MockK's stand-in, not the instance:
        // keep what set() was given, which is what the write right after it carries.
        var lastSet = 0L to 0
        mockkConstructor(MediaCodec.BufferInfo::class)
        every { anyConstructed<MediaCodec.BufferInfo>().set(any(), any(), any(), any()) } answers {
            lastSet = thirdArg<Long>() to arg<Int>(3)
        }
        every { muxer.writeSampleData(any(), any(), any()) } answers {
            written += Written(firstArg(), lastSet.first, lastSet.second)
        }
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // --- copyTrimmed ---

    @Test
    fun copyTrimmed_rebasesToTheKeyFrameAndDropsAudioBeforeIt() {
        // The copy seeks to the key frame at 1 s; the audio lands on the sample just before it.
        val extractor =
            fakeExtractor(
                listOf(videoMs(1000, 1033, 1066), audioUs(980_000, 1_001_000, 1_022_000)),
                selected = setOf(0, 1),
            )

        val counts = copy(extractor, window(1_000_000))

        assertEquals(listOf(Written(VIDEO, 0, KEY), Written(VIDEO, 33_000), Written(VIDEO, 66_000)), video())
        assertEquals(listOf(Written(AUDIO, 1_000, KEY), Written(AUDIO, 22_000, KEY)), audio())
        assertArrayEquals(intArrayOf(3, 2), counts)
    }

    @Test
    fun copyTrimmed_dropsVideoThatDisplaysBeforeTheKeyFrame() {
        // Open GOP: the two leading pictures after the key frame display before it and need the GOP before.
        val extractor = fakeExtractor(listOf(videoMs(1000, 933, 966, 1033)), selected = setOf(0))

        copy(extractor, window(1_000_000))

        assertEquals(listOf(0L, 33_000L), video().map { it.timeUs })
    }

    @Test
    fun copyTrimmed_writesTheHeldFramesWhenAFrameBeforeTheEndFollows() {
        // Decode order I0 P100 B33 B66 P200 B133 B166, end 150 ms: P200 is past the end but B133 references it.
        // B166 is held too, but nothing at or before the end follows it, so it is dropped.
        val extractor = fakeExtractor(listOf(videoMs(0, 100, 33, 66, 200, 133, 166)), selected = setOf(0))

        copy(extractor, window(0, endUs = 150_000))

        assertEquals(listOf(0L, 100_000L, 33_000L, 66_000L, 200_000L, 133_000L), video().map { it.timeUs })
    }

    @Test
    fun copyTrimmed_endIsInclusive() {
        val extractor = fakeExtractor(listOf(audioUs(0, 21_000, 42_000, 63_000)), selected = setOf(0))

        copy(extractor, window(0, endUs = 42_000, videoTrack = -1))

        assertEquals(listOf(0L, 21_000L, 42_000L), written.map { it.timeUs })
    }

    @Test
    fun copyTrimmed_keyFramePastTheEnd_stopsTheTrackAndDropsTheHeldFrames() {
        // P200 is held, then the key frame at 300 ends the track: the frame at 133 after it is never read.
        val extractor =
            fakeExtractor(listOf(videoMs(0, 100, 200, 300, 133, keys = setOf(0, 300))), selected = setOf(0))

        copy(extractor, window(0, endUs = 150_000))

        assertEquals(listOf(0L, 100_000L), video().map { it.timeUs })
        verify { extractor.unselectTrack(0) }
    }

    @Test
    fun copyTrimmed_fullHold_stopsTheTrack() {
        // One non-key frame more past the end than the hold takes: the frame at 140 after them isn't written.
        val pastEnd = LongArray(MAX_HELD_FRAMES + 1) { 200L + it * 10 }
        val extractor = fakeExtractor(listOf(videoMs(0, *pastEnd, 140)), selected = setOf(0))

        copy(extractor, window(0, endUs = 150_000))

        assertEquals(listOf(0L), video().map { it.timeUs })
    }

    @Test
    fun copyTrimmed_audioStopsAtItsFirstSamplePastTheEnd() {
        val extractor =
            fakeExtractor(listOf(videoMs(0, 33, 66, 100), audioUs(0, 50_000, 100_000)), selected = setOf(0, 1))

        copy(extractor, window(0, endUs = 70_000))

        assertEquals(listOf(0L, 50_000L), audio().map { it.timeUs })
        assertEquals(listOf(0L, 33_000L, 66_000L), video().map { it.timeUs })
    }

    @Test
    fun copyTrimmed_baseZero_keepsTheAudioPrimingNegative() {
        // Unseeked, as at the source's beginning: the priming at -21333 us stays behind MediaMuxer's edit list.
        val extractor =
            fakeExtractor(listOf(videoMs(0, 33), audioUs(-21_333, 0, 21_333)), selected = setOf(0, 1))

        copy(extractor, window(0))

        assertEquals(listOf(-21_333L, 0L, 21_333L), audio().map { it.timeUs })
        assertEquals(listOf(0L, 33_000L), video().map { it.timeUs })
    }

    @Test
    fun copyTrimmed_negativeBase_shiftsEveryTrackAlikeSoTheyStayInSync() {
        // Edit list starting mid-GOP: the key frame is at -33 ms. It is written at 0, and so is everything else
        // shifted by 33 ms: the source's 0 is at 33 ms on both tracks. The priming after the key frame plays
        // (as does the video before 0, which the cut snapped back to); priming before it stays negative.
        val extractor =
            fakeExtractor(
                listOf(videoMs(-33, 0, keys = setOf(-33)), audioUs(-43_000, -21_000, 0, 21_000)),
                selected = setOf(0, 1),
            )

        copy(extractor, window(-33_000))

        assertEquals(listOf(0L, 33_000L), video().map { it.timeUs })
        assertEquals(listOf(-10_000L, 12_000L, 33_000L, 54_000L), audio().map { it.timeUs })
    }

    @Test
    fun copyTrimmed_startAfterTheEnd_throws() {
        // The seek to the start lands on the last key frame, at 1 s, and nothing reaches the start at 5 s.
        val extractor = fakeExtractor(listOf(gop(0, 1000)), selected = setOf(0))

        val error =
            assertThrows(IOException::class.java) {
                copy(extractor, window(1_000_000, startUs = 5_000_000))
            }

        assertTrue(error.message, error.message!!.contains("5000 ms"))
    }

    @Test
    fun copyTrimmed_startBetweenFramesBeforeTheEnd_isNotAfterTheEnd() {
        // No frame in 1.01..1.02 s, but the source goes on: a short clip from the key frame, not a failure.
        val extractor = fakeExtractor(listOf(videoMs(1000, 1033, 1066)), selected = setOf(0))

        copy(extractor, window(1_000_000, startUs = 1_010_000, endUs = 1_020_000))

        assertEquals(listOf(0L), video().map { it.timeUs })
    }

    @Test
    fun copyTrimmed_interruptedThread_throwsBeforeWriting() {
        val extractor = fakeExtractor(listOf(videoMs(0, 33)), selected = setOf(0))
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                copy(extractor, window(0))
            }
            assertTrue("interrupt flag should stay set", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        assertTrue(written.isEmpty())
    }

    // --- planTrim ---

    @Test
    fun planTrim_baseIsTheKeyFrameAtOrBeforeTheStart() {
        val probe = fakeExtractor(listOf(gop(0, 1000, 2000), audioUs(*LongArray(150) { it * 20_000L })))

        val plan = planTrim(probe, setOf(0, 1), setOf(0), TrimRange(1500, 2500))

        assertEquals(1_000_000L, plan.window.baseUs)
        assertEquals(1_000_000L, plan.window.seekUs)
        assertEquals(2_500_000L, plan.window.endUs)
        assertEquals(setOf(0, 1), plan.tracks)
    }

    @Test
    fun planTrim_keyFrameAtZero_readsFromTheBeginningToKeepThePriming() {
        val probe = fakeExtractor(listOf(gop(0, 1000), audioUs(-21_333, 0, 21_333)))

        val plan = planTrim(probe, setOf(0, 1), setOf(0), TrimRange(500, 1500))

        assertEquals(0L, plan.window.baseUs)
        assertNull(plan.window.seekUs)
    }

    @Test
    fun planTrim_negativeKeyFrame_isTheBase() {
        val probe = fakeExtractor(listOf(videoMs(-33, 0, 33, keys = setOf(-33))))

        val plan = planTrim(probe, setOf(0), setOf(0), TrimRange(0, 1000))

        assertEquals(-33_000L, plan.window.baseUs)
        assertNull(plan.window.seekUs)
    }

    @Test
    fun planTrim_audioOnly_baseIsTheSampleAtOrBeforeTheStart() {
        val fromStart = planTrim(primedAudio(), setOf(0), emptySet(), TrimRange(0, 30))
        assertEquals(0L, fromStart.window.baseUs)
        assertNull("the priming is read from the beginning", fromStart.window.seekUs)
        assertEquals(-1, fromStart.window.videoTrack)

        assertEquals(21_333L, planTrim(primedAudio(), setOf(0), emptySet(), TrimRange(30, 40)).window.baseUs)
    }

    @Test
    fun planTrim_leavesOutAnAudioTrackWithNoSampleInTheCut() {
        // Audio ends at 5 s; the trim is of 8..9 s of a 10 s video.
        val probe = fakeExtractor(listOf(gop(0, 2000, 4000, 6000, 8000), audioUs(0, 2_500_000, 5_000_000)))

        val plan = planTrim(probe, setOf(0, 1), setOf(0), TrimRange(8000, 9000))

        assertEquals(setOf(0), plan.tracks)
    }

    @Test
    fun planTrim_audioStartingAfterTheEnd_isLeftOut() {
        val probe = fakeExtractor(listOf(gop(0, 1000, 2000), audioUs(1_500_000, 1_600_000)))

        assertEquals(setOf(0), planTrim(probe, setOf(0, 1), setOf(0), TrimRange(0, 1000)).tracks)
    }

    @Test
    fun planTrim_twoVideoTracks_throws() {
        val probe = fakeExtractor(listOf(gop(0), gop(0)))

        assertThrows(IOException::class.java) { planTrim(probe, setOf(0, 1), setOf(0, 1), TrimRange(0, 1000)) }
    }

    // --- TrimRange ---

    @Test
    fun trimRange_rejectsANegativeStartAndAnEndNotAfterIt() {
        assertThrows(IllegalArgumentException::class.java) { TrimRange(-1, 10) }
        assertThrows(IllegalArgumentException::class.java) { TrimRange(10, 10) }
    }

    @Test
    fun trimRange_maxEnd_doesNotOverflow() {
        assertTrue(TrimRange(0, Long.MAX_VALUE).endUs > 0)
    }

    private fun copy(
        extractor: MediaExtractor,
        window: TrimWindow,
    ): IntArray = copyTrimmed(extractor, muxer, intArrayOf(VIDEO, AUDIO), initialBufferSize = 16, window)

    /** A window from [baseUs] (also the start, unless given) with the video, or for audio-only track 0, as clock. */
    private fun window(
        baseUs: Long,
        startUs: Long = maxOf(baseUs, 0),
        endUs: Long = Long.MAX_VALUE,
        videoTrack: Int = 0,
    ) = TrimWindow(baseUs, startUs, endUs, videoTrack, clockTrack = maxOf(videoTrack, 0))

    private fun video() = written.filter { it.track == VIDEO }

    private fun audio() = written.filter { it.track == AUDIO }

    private fun primedAudio() = fakeExtractor(listOf(audioUs(-21_333, 0, 21_333, 42_666)))

    /** A video track of one-second GOPs (30 fps) starting at each of [keyFramesMs]. */
    private fun gop(vararg keyFramesMs: Long): List<FakeSample> =
        keyFramesMs.flatMap { key -> (0 until 30).map { FakeSample((key + it * 33) * 1000, sync = it == 0) } }

    private companion object {
        // Muxer track indices, distinct from the extractor's, so a mix-up shows.
        const val VIDEO = 5
        const val AUDIO = 6
        const val KEY = MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
}
