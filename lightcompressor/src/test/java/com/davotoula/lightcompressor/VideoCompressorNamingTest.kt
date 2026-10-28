package com.davotoula.lightcompressor

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoCompressorNamingTest {
    @Test
    fun `a bare name gets the mp4 extension`() {
        assertEquals("clip.mp4", VideoCompressor.validatedFileName("clip"))
    }

    @Test
    fun `a name with the mp4 extension is kept`() {
        assertEquals("clip.mp4", VideoCompressor.validatedFileName("clip.mp4"))
    }
}
