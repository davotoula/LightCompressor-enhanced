package com.davotoula.lightcompressor.hdr

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import com.davotoula.lightcompressor.utils.CompressorUtils
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.ByteBuffer

class ColorPlanTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun info(
        standard: Int? = null,
        transfer: Int? = null,
        range: Int? = null,
    ) = ColorInfo(standard, transfer, range, null)

    private fun out(): MediaFormat = mockk(relaxed = true)

    private fun outputWith(
        profile: Int? = null,
        transfer: Int? = null,
    ): MediaFormat {
        val f = mockk<MediaFormat>(relaxed = true)
        every { f.containsKey(MediaFormat.KEY_PROFILE) } returns (profile != null)
        if (profile != null) every { f.getInteger(MediaFormat.KEY_PROFILE) } returns profile
        every { f.containsKey(MediaFormat.KEY_COLOR_TRANSFER) } returns (transfer != null)
        if (transfer != null) every { f.getInteger(MediaFormat.KEY_COLOR_TRANSFER) } returns transfer
        return f
    }

    private fun keepHlg(staticInfo: ByteArray? = null) =
        ColorPlan.KeepHdr(
            MediaFormat.COLOR_TRANSFER_HLG,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            "c2.x",
            staticInfo,
            MediaFormat.COLOR_RANGE_LIMITED,
        )

    @Test
    fun sdr_copiesInputStandardAndTransfer() {
        val f = out()
        ColorPlan
            .Sdr(info(MediaFormat.COLOR_STANDARD_BT601_NTSC, MediaFormat.COLOR_TRANSFER_SDR_VIDEO))
            .applyTo(f, 24)
        verify { f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT601_NTSC) }
        verify { f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO) }
        verify { f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED) }
    }

    @Test
    fun sdr_defaultsToBt709WhenMissing() {
        val f = out()
        ColorPlan.Sdr(info()).applyTo(f, 24)
        verify { f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709) }
        verify { f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO) }
        verify { f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED) }
    }

    @Test
    fun sdr_belowApi24_setsNoColourKeys() {
        val f = out()
        ColorPlan.Sdr(info()).applyTo(f, 23)
        verify(exactly = 0) { f.setInteger(MediaFormat.KEY_COLOR_STANDARD, any()) }
        verify(exactly = 0) { f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, any()) }
        verify(exactly = 0) { f.setInteger(MediaFormat.KEY_COLOR_RANGE, any()) }
    }

    @Test
    fun sdr_fullRangeInput_isForcedLimitedAndLogs() {
        val f = out()
        ColorPlan.Sdr(info(range = MediaFormat.COLOR_RANGE_FULL)).applyTo(f, 24)
        verify { f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED) }
        verify { Log.w(any(), any<String>()) }
    }

    @Test
    fun toneMap_tagsBt709Sdr() {
        val f = out()
        ColorPlan.ToneMapToSdr(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_RANGE_LIMITED, 4.93f).applyTo(f, 33)
        verify { f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709) }
        verify { f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO) }
        verify { f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED) }
    }

    @Test
    fun keepHlg_tagsBt2020HlgAndMain10() {
        val f = out()
        keepHlg().applyTo(f, 33)
        verify { f.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020) }
        verify { f.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_HLG) }
        verify { f.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED) }
        verify { f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10) }
        verify(exactly = 0) { f.setByteBuffer(any(), any()) }
    }

    @Test
    fun keepPq_setsStaticInfo() {
        val f = out()
        val blob = ByteArray(25) { it.toByte() }
        ColorPlan
            .KeepHdr(
                MediaFormat.COLOR_TRANSFER_ST2084,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
                "c2.x",
                blob,
                MediaFormat.COLOR_RANGE_LIMITED,
            ).applyTo(f, 33)
        verify { f.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, ByteBuffer.wrap(blob)) }
    }

    @Test
    fun keep_matchesOutput_whenProfileAndTransferAgree() {
        val f = outputWith(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, MediaFormat.COLOR_TRANSFER_HLG)
        assertTrue(keepHlg().matchesEncoderOutput(f, 33))
    }

    @Test
    fun keep_rejectsOutput_whenProfileIsMain() {
        val f = outputWith(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, MediaFormat.COLOR_TRANSFER_HLG)
        assertFalse(keepHlg().matchesEncoderOutput(f, 33))
    }

    @Test
    fun keep_rejectsOutput_whenTransferIsSdr() {
        val f = outputWith(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        assertFalse(keepHlg().matchesEncoderOutput(f, 33))
    }

    private fun keepPq() =
        ColorPlan.KeepHdr(
            MediaFormat.COLOR_TRANSFER_ST2084,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10,
            "c2.x",
            null,
            MediaFormat.COLOR_RANGE_LIMITED,
        )

    /** Exynos reports HDR10 output as plain Main10: HDR10 is Main10 plus metadata, not a bitstream profile. */
    @Test
    fun keepPq_matchesOutput_whenEncoderReportsMain10() {
        val f = outputWith(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10, MediaFormat.COLOR_TRANSFER_ST2084)
        assertTrue(keepPq().matchesEncoderOutput(f, 33))
    }

    @Test
    fun keepPq_matchesOutput_whenEncoderReportsMain10Hdr10() {
        val f = outputWith(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10, MediaFormat.COLOR_TRANSFER_ST2084)
        assertTrue(keepPq().matchesEncoderOutput(f, 33))
    }

    @Test
    fun keepPq_rejectsOutput_whenProfileIsMain() {
        val f = outputWith(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain, MediaFormat.COLOR_TRANSFER_ST2084)
        assertFalse(keepPq().matchesEncoderOutput(f, 33))
    }

    @Test
    fun keep_matchesOutput_whenKeysAbsent() {
        assertTrue(keepHlg().matchesEncoderOutput(outputWith(), 33))
    }

    @Test
    fun toneMap_renderSpec_picksShaderByTransfer() {
        val hlg = ColorPlan.ToneMapToSdr(MediaFormat.COLOR_TRANSFER_HLG, null, 4.93f).render
        val pq = ColorPlan.ToneMapToSdr(MediaFormat.COLOR_TRANSFER_ST2084, null, 4.93f).render
        assertEquals(RenderSpec(gles3 = true, tenBit = false, shader = RenderShader.ToneMapHlg), hlg)
        assertEquals(RenderSpec(gles3 = true, tenBit = false, shader = RenderShader.ToneMapPq), pq)
    }

    @Test
    fun setOutputFileParameters_keepHdr_skipsProfileAndLevel() {
        val input = mockk<MediaFormat>(relaxed = true)
        every { input.containsKey(any()) } returns false
        val f = out()
        every { f.getString(MediaFormat.KEY_MIME) } returns "video/hevc"
        CompressorUtils.setOutputFileParameters(input, f, 5_000_000L, keepHlg(), 33)
        verify(exactly = 0) { f.setInteger(MediaFormat.KEY_LEVEL, any()) }
        verify(exactly = 1) { f.setInteger(MediaFormat.KEY_PROFILE, any()) }
    }
}
