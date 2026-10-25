package com.davotoula.lightcompressor.hdr

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.config.HdrMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrPolicyTest {
    private val hlgEnc = HdrEncoder("hlg.enc", CodecProfileLevel.HEVCProfileMain10)
    private val pqEnc = HdrEncoder("pq.enc", CodecProfileLevel.HEVCProfileMain10HDR10)

    private fun caps(
        hlg: HdrEncoder? = hlgEnc,
        pq: HdrEncoder? = pqEnc,
    ) = object : HdrCapabilities {
        override fun hlgEncoder(mime: String) = hlg

        override fun pqEncoder(mime: String) = pq
    }

    private fun info(
        transfer: Int?,
        standard: Int? = MediaFormat.COLOR_STANDARD_BT2020,
        staticInfo: ByteArray? = null,
    ) = ColorInfo(standard, transfer, MediaFormat.COLOR_RANGE_LIMITED, staticInfo)

    private val hlg = MediaFormat.COLOR_TRANSFER_HLG
    private val pq = MediaFormat.COLOR_TRANSFER_ST2084

    private fun decide(
        info: ColorInfo,
        codec: VideoCodec = VideoCodec.H265,
        mode: HdrMode = HdrMode.KEEP_IF_SUPPORTED,
        isHls: Boolean = false,
        caps: HdrCapabilities = caps(),
    ) = HdrPolicy.decide(info, codec, mode, isHls, caps)

    private fun staticInfoWithCll(cll: Int): ByteArray =
        ByteArray(25).also {
            it[21] = (cll and 0xFF).toByte()
            it[22] = (cll shr 8).toByte()
        }

    @Test
    fun sdrInput_isSdr() {
        val d = decide(info(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, MediaFormat.COLOR_STANDARD_BT709))
        assertTrue(d.plan is ColorPlan.Sdr)
    }

    @Test
    fun untaggedTenBitInput_isSdr() {
        val d = decide(info(null, null), caps = caps())
        assertTrue(d.plan is ColorPlan.Sdr)
    }

    @Test
    fun hlg_h265_keep_capable_isKeepHdr() {
        val plan = decide(info(hlg)).plan as ColorPlan.KeepHdr
        assertEquals("hlg.enc", plan.encoderName)
        assertEquals(CodecProfileLevel.HEVCProfileMain10, plan.profile)
        assertEquals(hlg, plan.transfer)
    }

    @Test
    fun hlg_missingStandard_isKeepHdr() {
        assertTrue(decide(info(hlg, standard = null)).plan is ColorPlan.KeepHdr)
    }

    @Test
    fun pq_h265_keep_capable_isKeepHdrWithStaticInfo() {
        val si = staticInfoWithCll(650)
        val plan = decide(info(pq, staticInfo = si)).plan as ColorPlan.KeepHdr
        assertEquals("pq.enc", plan.encoderName)
        assertEquals(CodecProfileLevel.HEVCProfileMain10HDR10, plan.profile)
        assertEquals(pq, plan.transfer)
        assertTrue(si contentEquals plan.staticInfo)
    }

    @Test
    fun hlg_h264_isToneMap() {
        assertTrue(decide(info(hlg), codec = VideoCodec.H264).plan is ColorPlan.ToneMapToSdr)
    }

    @Test
    fun hlg_toneMapMode_isToneMap() {
        assertTrue(decide(info(hlg), mode = HdrMode.TONE_MAP_TO_SDR).plan is ColorPlan.ToneMapToSdr)
    }

    @Test
    fun hlg_hls_isToneMap_evenWhenCapable() {
        assertTrue(decide(info(hlg), isHls = true).plan is ColorPlan.ToneMapToSdr)
    }

    @Test
    fun hlg_noEncoder_isToneMap_reasonMentionsEncoder() {
        val d = decide(info(hlg), caps = caps(hlg = null))
        assertTrue(d.plan is ColorPlan.ToneMapToSdr)
        assertTrue(d.reason, d.reason.contains("encoder"))
    }

    @Test
    fun hlg_bt709Standard_isToneMap_evenWhenCapable() {
        val d = decide(info(hlg, standard = MediaFormat.COLOR_STANDARD_BT709))
        assertTrue(d.plan is ColorPlan.ToneMapToSdr)
        assertTrue(d.reason, d.reason.contains("standard"))
    }

    @Test
    fun pq_toneMap_lwFromMaxCll() {
        val plan = decide(info(pq, staticInfo = staticInfoWithCll(650)), mode = HdrMode.TONE_MAP_TO_SDR).plan
        assertNotNull(plan)
        assertEquals(650f / 203f, (plan as ColorPlan.ToneMapToSdr).lw, 1e-4f)
    }

    @Test
    fun pq_toneMap_lwFallsBackTo1000() {
        val plan = decide(info(pq), mode = HdrMode.TONE_MAP_TO_SDR).plan as ColorPlan.ToneMapToSdr
        assertEquals(1000f / 203f, plan.lw, 1e-4f)
    }

    @Test
    fun hlg_toneMap_lwUses1000() {
        val plan = HdrPolicy.fallbackFor(info(hlg, staticInfo = staticInfoWithCll(4000)))
        assertEquals(1000f / 203f, plan.lw, 1e-4f)
    }
}
