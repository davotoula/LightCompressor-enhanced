package com.davotoula.lightcompressor.utils

import android.media.MediaExtractor
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class SampleBufferTest {
    /** An extractor whose current sample is [size] bytes; reading it into a smaller buffer throws, as on a device. */
    private fun extractorWith(size: Int): MediaExtractor =
        mockk {
            every { sampleSize } returns size.toLong()
            every { readSampleData(any(), 0) } answers {
                require(firstArg<ByteBuffer>().capacity() >= size) { "buffer too small" }
                size
            }
        }

    @Test
    fun read_largerSampleWithSampleSize_growsTheBuffer() {
        val buffer = SampleBuffer(initialSize = 4, canPeekSampleSize = true)

        assertEquals(10, buffer.read(extractorWith(10)))
        assertTrue("capacity ${buffer.data.capacity()}", buffer.data.capacity() >= 10)
    }

    @Test
    fun read_smallerSample_keepsTheBuffer() {
        val buffer = SampleBuffer(initialSize = 16, canPeekSampleSize = true)
        val before = buffer.data

        buffer.read(extractorWith(10))

        assertSame(before, buffer.data)
    }

    @Test
    fun read_withoutSampleSize_cannotGrow() {
        // Below API 28 the initial size has to be enough.
        val buffer = SampleBuffer(initialSize = 4, canPeekSampleSize = false)

        assertThrows(IllegalArgumentException::class.java) { buffer.read(extractorWith(10)) }
    }
}
