package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.Resolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsConfigTest {
    @Test
    fun `default ladder has 5 renditions`() {
        val ladder = HlsLadder.default()
        assertEquals(5, ladder.renditions.size)
    }

    @Test
    fun `default ladder is ordered lowest to highest resolution`() {
        val ladder = HlsLadder.default()
        val shortSides = ladder.renditions.map { it.resolution.shortSide }
        assertEquals(shortSides.sorted(), shortSides)
    }

    @Test
    fun `default ladder contains expected resolutions`() {
        val ladder = HlsLadder.default()
        val labels = ladder.renditions.map { it.resolution.label }
        assertEquals(listOf("360p", "540p", "720p", "1080p", "4K"), labels)
    }

    @Test
    fun `drop removes renditions by label`() {
        val ladder = HlsLadder.default().drop("4K", "360p")
        assertEquals(3, ladder.renditions.size)
        val labels = ladder.renditions.map { it.resolution.label }
        assertTrue("4K" !in labels)
        assertTrue("360p" !in labels)
    }

    @Test
    fun `add appends and re-sorts by resolution`() {
        val ladder =
            HlsLadder(
                listOf(Rendition(Resolution.HD_720, 2500)),
            ).add(Rendition(Resolution.SD_360, 500))
        assertEquals(2, ladder.renditions.size)
        assertEquals(Resolution.SD_360, ladder.renditions[0].resolution)
        assertEquals(Resolution.HD_720, ladder.renditions[1].resolution)
    }

    @Test
    fun `forSource filters renditions above source short side`() {
        val ladder = HlsLadder.default().forSource(sourceShortSide = 720)
        val labels = ladder.renditions.map { it.resolution.label }
        assertEquals(listOf("360p", "540p", "720p"), labels)
    }

    @Test
    fun `forSource keeps rendition matching source exactly`() {
        val ladder = HlsLadder.default().forSource(sourceShortSide = 1080)
        assertTrue(ladder.renditions.any { it.resolution == Resolution.FHD_1080 })
    }

    @Test
    fun `default config uses H264 and 6-second segments`() {
        val config = HlsConfig()
        assertEquals(com.davotoula.lightcompressor.VideoCodec.H264, config.codec)
        assertEquals(6, config.segmentDurationSeconds)
        assertEquals(false, config.disableAudio)
    }
}
