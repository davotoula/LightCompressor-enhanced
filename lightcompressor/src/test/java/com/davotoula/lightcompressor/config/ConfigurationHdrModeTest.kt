package com.davotoula.lightcompressor.config

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfigurationHdrModeTest {
    @Test
    fun default_isKeepIfSupported() =
        assertEquals(HdrMode.KEEP_IF_SUPPORTED, Configuration(videoNames = listOf("a")).hdrMode)

    @Test
    fun bpsFactory_passesHdrMode() =
        assertEquals(
            HdrMode.TONE_MAP_TO_SDR,
            Configuration.withBitrateInBps(videoNames = listOf("a"), hdrMode = HdrMode.TONE_MAP_TO_SDR).hdrMode,
        )

    @Test
    fun mbpsFactory_defaultsToKeep() =
        assertEquals(
            HdrMode.KEEP_IF_SUPPORTED,
            Configuration.withBitrateInMbps(videoNames = listOf("a")).hdrMode,
        )
}
