package com.davotoula.lightcompressor.hdr

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MediaCodecHdrCapabilitiesTest {
    private val hevc = "video/hevc"

    @Before
    fun setUp() {
        MediaCodecHdrCapabilities.clearCache()
    }

    private fun codec(
        codecName: String,
        features: Set<String>,
        profiles: List<Int>,
        mime: String = hevc,
        encoder: Boolean = true,
    ): MediaCodecInfo {
        val levels =
            profiles
                .map { p -> mockk<CodecProfileLevel>().apply { profile = p } }
                .toTypedArray()
        val caps =
            mockk<CodecCapabilities>(relaxed = true).apply {
                profileLevels = levels
                every { isFeatureSupported(any()) } answers { firstArg<String>() in features }
            }
        return mockk<MediaCodecInfo>().apply {
            every { name } returns codecName
            every { isEncoder } returns encoder
            every { supportedTypes } returns arrayOf(mime)
            every { getCapabilitiesForType(any()) } returns caps
        }
    }

    private fun caps(
        sdkInt: Int,
        vararg infos: MediaCodecInfo,
    ) = MediaCodecHdrCapabilities(sdkInt = sdkInt, codecInfos = { arrayOf(*infos) })

    private val main10 = CodecProfileLevel.HEVCProfileMain10
    private val main10Hdr10 = CodecProfileLevel.HEVCProfileMain10HDR10
    private val hdrEditing = CodecCapabilities.FEATURE_HdrEditing
    private val hlgEditing = CodecCapabilities.FEATURE_HlgEditing

    @Test
    fun picksTheEncoderWithTheFeature_notTheFirstHevcEncoder() {
        val c =
            caps(
                33,
                codec("c2.android.hevc.encoder", emptySet(), listOf(main10)),
                codec("c2.vendor.hevc.encoder", setOf(hdrEditing), listOf(main10)),
            )
        assertEquals(HdrEncoder("c2.vendor.hevc.encoder", main10), c.hlgEncoder(hevc))
    }

    @Test
    fun hlgEditing_ignoredBelowApi35() {
        val c = caps(34, codec("enc", setOf(hlgEditing), listOf(main10)))
        assertNull(c.hlgEncoder(hevc))
    }

    @Test
    fun hlgEditing_usedOnApi35() {
        val c = caps(35, codec("enc", setOf(hlgEditing), listOf(main10)))
        assertEquals(HdrEncoder("enc", main10), c.hlgEncoder(hevc))
    }

    @Test
    fun pq_requiresMain10Hdr10() {
        val c = caps(33, codec("enc", setOf(hdrEditing), listOf(main10)))
        assertNull(c.pqEncoder(hevc))
        assertNotNull(c.hlgEncoder(hevc))
    }

    @Test
    fun pq_usesMain10Hdr10Profile() {
        val c = caps(33, codec("enc", setOf(hdrEditing), listOf(main10, main10Hdr10)))
        assertEquals(HdrEncoder("enc", main10Hdr10), c.pqEncoder(hevc))
    }

    @Test
    fun belowApi33_nothing() {
        val info = codec("enc", setOf(hdrEditing, hlgEditing), listOf(main10, main10Hdr10))
        val c = caps(32, info)
        assertNull(c.hlgEncoder(hevc))
        assertNull(c.pqEncoder(hevc))
        verify(exactly = 0) { info.getCapabilitiesForType(any()) }
    }

    @Test
    fun decoders_andOtherMimes_areIgnored() {
        val c =
            caps(
                33,
                codec("dec", setOf(hdrEditing), listOf(main10), encoder = false),
                codec("avc", setOf(hdrEditing), listOf(main10), mime = "video/avc"),
            )
        assertNull(c.hlgEncoder(hevc))
    }

    @Test
    fun missesAreCached() {
        var calls = 0
        val c =
            MediaCodecHdrCapabilities(sdkInt = 33, codecInfos = {
                calls++
                emptyArray()
            })
        assertNull(c.hlgEncoder(hevc))
        assertNull(MediaCodecHdrCapabilities(sdkInt = 33, codecInfos = { emptyArray() }).hlgEncoder(hevc))
        assertEquals(1, calls)
    }
}
