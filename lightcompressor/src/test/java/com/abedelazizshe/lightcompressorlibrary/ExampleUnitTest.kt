package com.abedelazizshe.lightcompressorlibrary

import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for the LightCompressor library, including new bps bitrate API
 */
class ExampleUnitTest {
    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun configuration_bps_bitrate_works() {
        // Test Configuration with bps bitrate
        val config = Configuration.withBitrateInBps(
            quality = VideoQuality.HIGH,
            videoBitrateInBps = 2500000L, // 2.5 Mbps in bps
            videoNames = listOf("test_video.mp4")
        )

        assertEquals(2500000L, config.getEffectiveBitrateInBps())
        assertNull(config.videoBitrateInMbps)
        assertEquals(2500000L, config.videoBitrateInBps)
    }

    @Test
    fun configuration_mbps_bitrate_works() {
        // Test Configuration with Mbps bitrate (legacy)
        val config = Configuration.withBitrateInMbps(
            quality = VideoQuality.HIGH,
            videoBitrateInMbps = 3, // 3 Mbps
            videoNames = listOf("test_video.mp4")
        )

        assertEquals(3000000L, config.getEffectiveBitrateInBps())
        assertEquals(3, config.videoBitrateInMbps)
        assertNull(config.videoBitrateInBps)
    }

    @Test
    fun configuration_bps_takes_precedence() {
        // Test that bps takes precedence over Mbps when both are specified
        val config = Configuration.withBitrateInBps(
            quality = VideoQuality.HIGH,
            videoBitrateInBps = 2500000L, // 2.5 Mbps in bps
            videoNames = listOf("test_video.mp4")
        )

        config.videoBitrateInMbps = 3 // Set Mbps after creation

        // Should return bps value, not converted Mbps value
        assertEquals(2500000L, config.getEffectiveBitrateInBps())
    }

    @Test
    fun configuration_no_bitrate_returns_null() {
        // Test Configuration with no custom bitrate
        val config = Configuration.withBitrateInBps(
            quality = VideoQuality.HIGH,
            videoNames = listOf("test_video.mp4")
        )

        assertNull(config.getEffectiveBitrateInBps())
        assertNull(config.videoBitrateInMbps)
        assertNull(config.videoBitrateInBps)
    }

    @Test
    fun configuration_validates_positive_bps() {
        // Test validation for positive bps values
        val config = Configuration.withBitrateInBps(
            quality = VideoQuality.HIGH,
            videoBitrateInBps = -1000L, // Invalid negative value
            videoNames = listOf("test_video.mp4")
        )

        try {
            config.getEffectiveBitrateInBps()
            fail("Expected IllegalArgumentException for negative bitrate")
        } catch (e: IllegalArgumentException) {
            assertEquals("videoBitrateInBps must be positive", e.message)
        }
    }

    @Test
    fun configuration_validates_positive_mbps() {
        // Test validation for positive Mbps values
        val config = Configuration.withBitrateInMbps(
            quality = VideoQuality.HIGH,
            videoBitrateInMbps = -1, // Invalid negative value
            videoNames = listOf("test_video.mp4")
        )

        try {
            config.getEffectiveBitrateInBps()
            fail("Expected IllegalArgumentException for negative bitrate")
        } catch (e: IllegalArgumentException) {
            assertEquals("videoBitrateInMbps must be positive", e.message)
        }
    }

    @Test
    fun configuration_codec_h264_default() {
        // Test that H.264 is the default codec
        val config = Configuration.withBitrateInBps(
            quality = VideoQuality.HIGH,
            videoNames = listOf("test_video.mp4")
        )

        assertEquals(VideoCodec.H264, config.videoCodec)
    }

    @Test
    fun configuration_codec_h265_works() {
        // Test H.265 codec selection
        val config = Configuration.withBitrateInBps(
            quality = VideoQuality.HIGH,
            videoBitrateInBps = 2000000L,
            videoNames = listOf("test_video.mp4"),
            videoCodec = VideoCodec.H265
        )

        assertEquals(VideoCodec.H265, config.videoCodec)
        assertEquals(2000000L, config.videoBitrateInBps)
    }

    @Test
    fun configuration_supports_both_codec_types() {
        // Test both codec configurations
        val h264Config = Configuration.withBitrateInMbps(
            quality = VideoQuality.MEDIUM,
            videoBitrateInMbps = 2,
            videoNames = listOf("video.mp4"),
            videoCodec = VideoCodec.H264
        )

        val h265Config = Configuration.withBitrateInMbps(
            quality = VideoQuality.MEDIUM,
            videoBitrateInMbps = 2,
            videoNames = listOf("video.mp4"),
            videoCodec = VideoCodec.H265
        )

        assertEquals(VideoCodec.H264, h264Config.videoCodec)
        assertEquals(VideoCodec.H265, h265Config.videoCodec)
        assertEquals(h264Config.videoBitrateInMbps, h265Config.videoBitrateInMbps)
    }
}
