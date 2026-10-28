package com.davotoula.lightcompressor.hdr

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class HdrProbeTest {
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
    ) = ColorInfo(standard, transfer, MediaFormat.COLOR_RANGE_LIMITED, null)

    @Test
    fun untagged_isSdr() {
        assertEquals(HdrProbe(HdrInput.SDR, false), hdrProbeOf(ColorInfo(null, null, null, null), caps()))
    }

    @Test
    fun bt709Sdr_isSdr() {
        val sdr = info(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, MediaFormat.COLOR_STANDARD_BT709)
        assertEquals(HdrProbe(HdrInput.SDR, false), hdrProbeOf(sdr, caps()))
    }

    @Test
    fun hlg_withHlgEncoder_canKeep() {
        assertEquals(HdrProbe(HdrInput.HLG, true), hdrProbeOf(info(MediaFormat.COLOR_TRANSFER_HLG), caps()))
    }

    @Test
    fun hlg_noHdrEncoder_cannotKeep() {
        val probe = hdrProbeOf(info(MediaFormat.COLOR_TRANSFER_HLG), caps(hlg = null, pq = null))
        assertEquals(HdrProbe(HdrInput.HLG, false), probe)
    }

    @Test
    fun pq_withHdrEncoder_canKeep() {
        assertEquals(HdrProbe(HdrInput.PQ, true), hdrProbeOf(info(MediaFormat.COLOR_TRANSFER_ST2084), caps()))
    }

    @Test
    fun hlg_nonBt2020Standard_cannotKeep() {
        val probe = hdrProbeOf(info(MediaFormat.COLOR_TRANSFER_HLG, MediaFormat.COLOR_STANDARD_BT709), caps())
        assertEquals(HdrProbe(HdrInput.HLG, false), probe)
    }

    @Test
    fun hdrProbe_isDataClass() {
        assertEquals(HdrProbe(HdrInput.PQ, true), HdrProbe(HdrInput.PQ, true))
    }
}
