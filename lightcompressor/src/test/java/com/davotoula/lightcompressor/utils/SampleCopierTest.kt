package com.davotoula.lightcompressor.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class SampleCopierTest {
    private val muxer = mockk<MediaMuxer>()

    @Before
    fun setUp() {
        // BufferInfo.set is a stub on the JVM.
        mockkConstructor(MediaCodec.BufferInfo::class)
        every { anyConstructed<MediaCodec.BufferInfo>().set(any(), any(), any(), any()) } just runs
        every { muxer.writeSampleData(any(), any(), any()) } just runs
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun copySamples_writesEverySampleToItsMuxerTrackUntilEndOfStream() {
        // Track 1 maps to muxer track 7; at the end of the stream the time is -1 too, which must not throw.
        val extractor = extractorWith(times = listOf(0L, 33_333L, 66_666L), track = 1)

        val written =
            copySamples(extractor, muxer, intArrayOf(0, 7), initialBufferSize = 16) { NegativeTime.REJECT }

        assertArrayEquals(intArrayOf(0, 3), written)
        verify(exactly = 3) { muxer.writeSampleData(7, any(), any()) }
    }

    @Test
    fun copySamples_interruptedThread_throwsBeforeReadingAndKeepsTheFlag() {
        val extractor = mockk<MediaExtractor>()
        Thread.currentThread().interrupt()
        try {
            assertThrows(InterruptedIOException::class.java) {
                copySamples(extractor, muxer, IntArray(1), initialBufferSize = 16)
            }
            assertTrue("interrupt flag should stay set", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
        verify(exactly = 0) { extractor.readSampleData(any(), any()) }
    }

    @Test
    fun copySamples_negativeTime_rejectThrows() {
        val error =
            assertThrows(IOException::class.java) {
                copySamples(primedExtractor(), muxer, IntArray(1), initialBufferSize = 16) { NegativeTime.REJECT }
            }

        assertTrue(error.message, error.message!!.contains("-21333"))
        verify(exactly = 0) { muxer.writeSampleData(any(), any(), any()) }
    }

    @Test
    fun copySamples_negativeTime_stopsByDefault() {
        val written = copySamples(primedExtractor(), muxer, IntArray(1), initialBufferSize = 16)

        assertArrayEquals(intArrayOf(0), written)
        verify(exactly = 0) { muxer.writeSampleData(any(), any(), any()) }
    }

    @Test
    fun copySamples_negativeTime_copyWritesIt() {
        val written =
            copySamples(primedExtractor(), muxer, IntArray(1), initialBufferSize = 16) { NegativeTime.COPY }

        assertArrayEquals(intArrayOf(2), written)
    }

    /** A priming sample at -21333 us, then one at 0, as ffmpeg AAC reads. */
    private fun primedExtractor() = extractorWith(times = listOf(-21_333L, 0L), track = 0)

    /** Samples at [times] on [track], then the end of the stream (-1 size, -1 time, track -1). */
    private fun extractorWith(
        times: List<Long>,
        track: Int,
    ): MediaExtractor {
        var index = 0
        return mockk {
            every { readSampleData(any(), 0) } answers { if (index < times.size) 10 else -1 }
            every { sampleTrackIndex } answers { if (index < times.size) track else -1 }
            every { sampleTime } answers { times.getOrElse(index) { -1L } }
            every { sampleFlags } returns MediaExtractor.SAMPLE_FLAG_SYNC
            every { advance() } answers {
                index++
                index < times.size
            }
        }
    }
}
