package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.Resolution
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class HlsPathsTest {
    private val rendition720 = Rendition(Resolution.HD_720, bitrateKbps = 2500)
    private val rendition1080 = Rendition(Resolution.FHD_1080, bitrateKbps = 5000)

    @Test
    fun `mediaPlaylistFilename is label slash media m3u8`() {
        assertEquals("720p/media.m3u8", rendition720.mediaPlaylistFilename())
        assertEquals("1080p/media.m3u8", rendition1080.mediaPlaylistFilename())
    }

    @Test
    fun `suggestedFilename for init segment includes label prefix`() {
        val segment = segment(isInitSegment = true, index = 0)
        assertEquals("720p/init.mp4", rendition720.suggestedFilename(segment))
    }

    @Test
    fun `suggestedFilename for media segment zero-pads index`() {
        assertEquals("720p/segment_000.m4s", rendition720.suggestedFilename(segment(index = 0)))
        assertEquals("720p/segment_003.m4s", rendition720.suggestedFilename(segment(index = 3)))
        assertEquals("720p/segment_100.m4s", rendition720.suggestedFilename(segment(index = 100)))
    }

    @Test
    fun `suggestedFilename for combined rendition drops subdirectory`() {
        val segment = segment(isCombinedRendition = true, index = 0)
        assertEquals("720p.mp4", rendition720.suggestedFilename(segment))
        assertEquals("1080p.mp4", rendition1080.suggestedFilename(segment))
    }

    private fun segment(
        index: Int = 0,
        isInitSegment: Boolean = false,
        isCombinedRendition: Boolean = false,
    ) = HlsSegment(
        file = File("tmp"),
        index = index,
        durationSeconds = 6.0,
        isInitSegment = isInitSegment,
        isCombinedRendition = isCombinedRendition,
    )
}
