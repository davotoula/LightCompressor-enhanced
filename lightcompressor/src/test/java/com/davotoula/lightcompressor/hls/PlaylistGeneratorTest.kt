package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistGeneratorTest {
    private val generator = PlaylistGenerator()

    @Test
    fun `media playlist starts with EXTM3U and version 7`() {
        val playlist =
            generator.buildMediaPlaylist(
                segments =
                    listOf(
                        SegmentInfo("segment_000.m4s", 6.0),
                    ),
                targetDurationSeconds = 6,
            )
        assertTrue(playlist.startsWith("#EXTM3U\n"))
        assertTrue("#EXT-X-VERSION:7" in playlist)
    }

    @Test
    fun `media playlist ends with EXT-X-ENDLIST`() {
        val playlist =
            generator.buildMediaPlaylist(
                segments = listOf(SegmentInfo("segment_000.m4s", 6.0)),
                targetDurationSeconds = 6,
            )
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"))
    }

    @Test
    fun `media playlist includes EXT-X-MAP for init segment`() {
        val playlist =
            generator.buildMediaPlaylist(
                segments = listOf(SegmentInfo("segment_000.m4s", 6.0)),
                targetDurationSeconds = 6,
            )
        assertTrue("#EXT-X-MAP:URI=\"init.mp4\"" in playlist)
    }

    @Test
    fun `media playlist includes EXTINF for each segment`() {
        val playlist =
            generator.buildMediaPlaylist(
                segments =
                    listOf(
                        SegmentInfo("segment_000.m4s", 6.0),
                        SegmentInfo("segment_001.m4s", 6.0),
                        SegmentInfo("segment_002.m4s", 4.320),
                    ),
                targetDurationSeconds = 6,
            )
        assertEquals(3, Regex("#EXTINF:").findAll(playlist).count())
        assertTrue("#EXTINF:4.320," in playlist)
    }

    @Test
    fun `media playlist target duration is ceiling of max segment duration`() {
        val playlist =
            generator.buildMediaPlaylist(
                segments =
                    listOf(
                        SegmentInfo("segment_000.m4s", 6.0),
                        SegmentInfo("segment_001.m4s", 6.5),
                    ),
                targetDurationSeconds = 7,
            )
        assertTrue("#EXT-X-TARGETDURATION:7" in playlist)
    }

    @Test
    fun `master playlist includes STREAM-INF for each rendition`() {
        val master =
            generator.buildMasterPlaylist(
                renditions =
                    listOf(
                        RenditionResult(
                            rendition = Rendition(Resolution.SD_360, 500),
                            actualWidth = 640,
                            actualHeight = 360,
                            codecString = "avc1.64001E",
                            playlistFilename = "360p/media.m3u8",
                            mediaPlaylist = "",
                        ),
                        RenditionResult(
                            rendition = Rendition(Resolution.HD_720, 2500),
                            actualWidth = 1280,
                            actualHeight = 720,
                            codecString = "avc1.640020",
                            playlistFilename = "720p/media.m3u8",
                            mediaPlaylist = "",
                        ),
                    ),
            )
        assertTrue("#EXTM3U" in master)
        assertTrue("BANDWIDTH=500000" in master)
        assertTrue("BANDWIDTH=2500000" in master)
        assertTrue("RESOLUTION=640x360" in master)
        assertTrue("RESOLUTION=1280x720" in master)
        assertTrue("CODECS=\"avc1.64001E\"" in master)
        assertTrue("360p/media.m3u8" in master)
        assertTrue("720p/media.m3u8" in master)
    }

    @Test
    fun `master playlist renditions ordered by bandwidth ascending`() {
        val master =
            generator.buildMasterPlaylist(
                renditions =
                    listOf(
                        RenditionResult(
                            rendition = Rendition(Resolution.HD_720, 2500),
                            actualWidth = 1280,
                            actualHeight = 720,
                            codecString = "avc1.640020",
                            playlistFilename = "720p/media.m3u8",
                            mediaPlaylist = "",
                        ),
                        RenditionResult(
                            rendition = Rendition(Resolution.SD_360, 500),
                            actualWidth = 640,
                            actualHeight = 360,
                            codecString = "avc1.64001E",
                            playlistFilename = "360p/media.m3u8",
                            mediaPlaylist = "",
                        ),
                    ),
            )
        val idx360 = master.indexOf("360p/media.m3u8")
        val idx720 = master.indexOf("720p/media.m3u8")
        assertTrue("360p should appear before 720p", idx360 < idx720)
    }
}
