package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistGeneratorTest {
    @Test
    fun `media playlist starts with EXTM3U and version 7`() {
        val playlist =
            PlaylistGenerator.buildMediaPlaylist(
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
            PlaylistGenerator.buildMediaPlaylist(
                segments = listOf(SegmentInfo("segment_000.m4s", 6.0)),
                targetDurationSeconds = 6,
            )
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"))
    }

    @Test
    fun `media playlist includes EXT-X-MAP for init segment`() {
        val playlist =
            PlaylistGenerator.buildMediaPlaylist(
                segments = listOf(SegmentInfo("segment_000.m4s", 6.0)),
                targetDurationSeconds = 6,
            )
        assertTrue("#EXT-X-MAP:URI=\"init.mp4\"" in playlist)
    }

    @Test
    fun `media playlist includes EXTINF for each segment`() {
        val playlist =
            PlaylistGenerator.buildMediaPlaylist(
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
            PlaylistGenerator.buildMediaPlaylist(
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
            PlaylistGenerator.buildMasterPlaylist(
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
            PlaylistGenerator.buildMasterPlaylist(
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

    @Test
    fun `master playlist includes audio codec in CODECS when present`() {
        val master =
            PlaylistGenerator.buildMasterPlaylist(
                renditions =
                    listOf(
                        RenditionResult(
                            rendition = Rendition(Resolution.HD_720, 2500),
                            actualWidth = 1280,
                            actualHeight = 720,
                            codecString = "avc1.640020,mp4a.40.2",
                            playlistFilename = "720p/media.m3u8",
                            mediaPlaylist = "",
                        ),
                    ),
            )
        assertTrue(
            "CODECS should include both video and audio codec",
            "CODECS=\"avc1.640020,mp4a.40.2\"" in master,
        )
    }

    @Test
    fun `byterange media playlist references single file via EXT-X-MAP with byterange`() {
        val playlist =
            PlaylistGenerator.buildByteRangeMediaPlaylist(
                combinedFilename = "540p.mp4",
                initRangeLength = 1024L,
                segments =
                    listOf(
                        ByteRangeSegment(durationSeconds = 6.0, offset = 1024L, length = 524288L),
                    ),
                targetDurationSeconds = 6,
            )
        assertTrue("must declare init range", "#EXT-X-MAP:URI=\"540p.mp4\",BYTERANGE=\"1024@0\"" in playlist)
        assertTrue("must reference combined file in segment", "540p.mp4" in playlist)
    }

    @Test
    fun `byterange media playlist emits EXT-X-BYTERANGE for each segment with offset`() {
        val playlist =
            PlaylistGenerator.buildByteRangeMediaPlaylist(
                combinedFilename = "720p.mp4",
                initRangeLength = 800L,
                segments =
                    listOf(
                        ByteRangeSegment(durationSeconds = 6.0, offset = 800L, length = 100_000L),
                        ByteRangeSegment(durationSeconds = 6.0, offset = 100_800L, length = 110_000L),
                        ByteRangeSegment(durationSeconds = 4.32, offset = 210_800L, length = 80_000L),
                    ),
                targetDurationSeconds = 7,
            )
        assertEquals(3, Regex("#EXT-X-BYTERANGE:").findAll(playlist).count())
        assertTrue("first segment", "#EXT-X-BYTERANGE:100000@800" in playlist)
        assertTrue("second segment", "#EXT-X-BYTERANGE:110000@100800" in playlist)
        assertTrue("third segment", "#EXT-X-BYTERANGE:80000@210800" in playlist)
        assertTrue("third segment EXTINF", "#EXTINF:4.320," in playlist)
    }

    @Test
    fun `byterange media playlist starts with EXTM3U version 7 and ends with ENDLIST`() {
        val playlist =
            PlaylistGenerator.buildByteRangeMediaPlaylist(
                combinedFilename = "360p.mp4",
                initRangeLength = 512L,
                segments =
                    listOf(
                        ByteRangeSegment(durationSeconds = 6.0, offset = 512L, length = 1000L),
                    ),
                targetDurationSeconds = 6,
            )
        assertTrue(playlist.startsWith("#EXTM3U\n"))
        assertTrue("#EXT-X-VERSION:7" in playlist)
        assertTrue(playlist.trimEnd().endsWith("#EXT-X-ENDLIST"))
    }

    @Test
    fun `byterange media playlist target duration is respected`() {
        val playlist =
            PlaylistGenerator.buildByteRangeMediaPlaylist(
                combinedFilename = "1080p.mp4",
                initRangeLength = 600L,
                segments =
                    listOf(
                        ByteRangeSegment(durationSeconds = 6.5, offset = 600L, length = 1234L),
                    ),
                targetDurationSeconds = 7,
            )
        assertTrue("#EXT-X-TARGETDURATION:7" in playlist)
    }

    @Test
    fun `master playlist works with HEVC and audio codec`() {
        val master =
            PlaylistGenerator.buildMasterPlaylist(
                renditions =
                    listOf(
                        RenditionResult(
                            rendition = Rendition(Resolution.UHD_4K, 15000),
                            actualWidth = 3840,
                            actualHeight = 2160,
                            codecString = "hev1.1.6.L150.B0,mp4a.40.2",
                            playlistFilename = "4K/media.m3u8",
                            mediaPlaylist = "",
                        ),
                    ),
            )
        assertTrue(
            "CODECS should include HEVC and audio codec",
            "CODECS=\"hev1.1.6.L150.B0,mp4a.40.2\"" in master,
        )
    }
}
