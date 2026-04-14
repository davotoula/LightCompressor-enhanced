package com.davotoula.lightcompressor.hls

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HlsContentTypesTest {
    @Test
    fun `HLS playlist constant matches Apple spec`() {
        assertEquals("application/vnd.apple.mpegurl", HlsContentTypes.HLS_PLAYLIST)
    }

    @Test
    fun `fMP4 segment constant is video mp4`() {
        assertEquals("video/mp4", HlsContentTypes.FMP4_SEGMENT)
    }

    @Test
    fun `forPlaylist returns HLS playlist type`() {
        assertEquals("application/vnd.apple.mpegurl", HlsContentTypes.forPlaylist())
    }

    @Test
    fun `forSegment returns video mp4 for init segment`() {
        val segment =
            HlsSegment(
                file = File("init.mp4"),
                index = 0,
                durationSeconds = 0.0,
                isInitSegment = true,
            )
        assertEquals("video/mp4", HlsContentTypes.forSegment(segment))
    }

    @Test
    fun `forSegment returns video mp4 for media segment`() {
        val segment =
            HlsSegment(
                file = File("segment_000.m4s"),
                index = 0,
                durationSeconds = 6.0,
                isInitSegment = false,
            )
        assertEquals("video/mp4", HlsContentTypes.forSegment(segment))
    }

    @Test
    fun `forSegment returns video mp4 for combined rendition`() {
        val segment =
            HlsSegment(
                file = File("720p.mp4"),
                index = 0,
                durationSeconds = 30.0,
                isInitSegment = false,
                isCombinedRendition = true,
            )
        assertEquals("video/mp4", HlsContentTypes.forSegment(segment))
    }
}
