package com.davotoula.lightcompressor.hls

import org.junit.Assert.assertEquals
import org.junit.Test

class HlsContentTypesTest {
    @Test
    fun `HLS playlist constant matches Apple spec`() {
        assertEquals("application/vnd.apple.mpegurl", HlsContentTypes.HLS_PLAYLIST)
    }

    @Test
    fun `fMP4 segment constant is video mp4`() {
        assertEquals("video/mp4", HlsContentTypes.FMP4_SEGMENT)
    }
}
