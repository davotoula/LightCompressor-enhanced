package com.davotoula.lightcompressor.utils

import android.media.MediaExtractor
import android.media.MediaMuxer
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
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
}
