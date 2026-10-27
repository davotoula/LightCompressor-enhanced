package com.davotoula.lightcompressor.hls

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaFormat
import android.net.Uri
import com.davotoula.lightcompressor.hdr.ColorInfo
import com.davotoula.lightcompressor.hdr.ColorPlan
import com.davotoula.lightcompressor.hdr.HdrCapabilities
import com.davotoula.lightcompressor.hdr.HdrEncoder
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsTranscoderPlanTest {
    private val capableDevice =
        object : HdrCapabilities {
            override fun hlgEncoder(mime: String) = HdrEncoder("hlg.enc", CodecProfileLevel.HEVCProfileMain10)

            override fun pqEncoder(mime: String) = HdrEncoder("pq.enc", CodecProfileLevel.HEVCProfileMain10HDR10)
        }

    private fun transcoder() =
        HlsTranscoder(
            context = mockk<Context>(relaxed = true),
            srcUri = mockk<Uri>(relaxed = true),
            config = HlsConfig(),
            hdrCapabilities = lazy { capableDevice },
        )

    @Test
    fun hlgSource_capableDevice_isToneMap() {
        val info =
            ColorInfo(
                MediaFormat.COLOR_STANDARD_BT2020,
                MediaFormat.COLOR_TRANSFER_HLG,
                MediaFormat.COLOR_RANGE_LIMITED,
                null,
            )
        assertTrue(transcoder().planFor(info).plan is ColorPlan.ToneMapToSdr)
    }

    @Test
    fun sdrSource_isSdr() {
        val info =
            ColorInfo(
                MediaFormat.COLOR_STANDARD_BT709,
                MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                MediaFormat.COLOR_RANGE_LIMITED,
                null,
            )
        assertTrue(transcoder().planFor(info).plan is ColorPlan.Sdr)
    }
}
