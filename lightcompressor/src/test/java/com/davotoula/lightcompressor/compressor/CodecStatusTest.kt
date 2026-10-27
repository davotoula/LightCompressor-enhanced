package com.davotoula.lightcompressor.compressor

import android.media.MediaCodec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodecStatusTest {
    @Test
    fun `output buffers changed is recognised`() {
        assertTrue(isOutputBuffersChanged(-3))
    }

    @Test
    fun `other statuses are not treated as output buffers changed`() {
        assertFalse(isOutputBuffersChanged(MediaCodec.INFO_TRY_AGAIN_LATER))
        assertFalse(isOutputBuffersChanged(MediaCodec.INFO_OUTPUT_FORMAT_CHANGED))
        assertFalse(isOutputBuffersChanged(-4))
        assertFalse(isOutputBuffersChanged(-1000))
        assertFalse(isOutputBuffersChanged(0))
    }
}
