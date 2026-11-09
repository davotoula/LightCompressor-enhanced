package com.davotoula.lightcompressor.utils

import android.media.MediaExtractor
import android.media.MediaMuxer
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class SampleCopierTest {
    @Test
    fun copySamples_interruptedThread_throwsBeforeReadingAndKeepsTheFlag() {
        val extractor = mockk<MediaExtractor>()
        val muxer = mockk<MediaMuxer>()
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
    fun copySamples_negativeTime_throwsWhenRejecting() {
        val muxer = mockk<MediaMuxer>()

        val error =
            assertThrows(IOException::class.java) {
                copySamples(trimmedExtractor(), muxer, IntArray(1), initialBufferSize = 16, rejectNegativeTimes = true)
            }

        assertTrue(error.message, error.message!!.contains("-1000"))
        verify(exactly = 0) { muxer.writeSampleData(any(), any(), any()) }
    }

    @Test
    fun copySamples_negativeTime_stopsByDefault() {
        val muxer = mockk<MediaMuxer>()

        val written = copySamples(trimmedExtractor(), muxer, IntArray(1), initialBufferSize = 16)

        assertArrayEquals(intArrayOf(0), written)
        verify(exactly = 0) { muxer.writeSampleData(any(), any(), any()) }
    }

    /** First sample at -1 ms, as a stream-copy trim's lead-in reads. */
    private fun trimmedExtractor(): MediaExtractor =
        mockk {
            every { readSampleData(any(), 0) } returns 10
            every { sampleTrackIndex } returns 0
            every { sampleTime } returns -1000L
        }
}
