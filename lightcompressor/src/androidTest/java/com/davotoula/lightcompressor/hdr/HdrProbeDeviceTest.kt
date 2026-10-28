package com.davotoula.lightcompressor.hdr

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.VideoCompressor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs [VideoCompressor.probeHdr] on real files: the 10-bit HLG clip `orig2.mp4` and the 10-bit SDR clip
 * `jf_sdr_hevc10_1080p_3M.mp4`. Push them to the test app's external files dir first, as for
 * [KeepHdrRetryTest]. The Dolby Vision 8.1 case runs only when `jf_dv_p81_1080p.mp4` is there too.
 */
@RunWith(AndroidJUnit4::class)
class HdrProbeDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = context.getExternalFilesDir(null)!!

    @Test
    fun hlgClip_isHlg_canKeepWhenDeviceHasHlgEncoder() {
        val probe = VideoCompressor.probeHdr(context, source("orig2.mp4"))

        assertEquals(HdrInput.HLG, probe.input)
        val hasEncoder = MediaCodecHdrCapabilities().hlgEncoder(VideoCodec.H265.mimeType) != null
        assertEquals("canKeepHdr should match the device's HLG encoder support", hasEncoder, probe.canKeepHdr)
    }

    @Test
    fun sdrClip_isSdr() {
        assertEquals(
            HdrProbe(HdrInput.SDR, false),
            VideoCompressor.probeHdr(context, source("jf_sdr_hevc10_1080p_3M.mp4")),
        )
    }

    @Test
    fun missingFile_isSdr_doesNotThrow() {
        val missing = Uri.fromFile(File(dir, "does_not_exist.mp4"))

        assertEquals(HdrProbe(HdrInput.SDR, false), VideoCompressor.probeHdr(context, missing))
    }

    /** The HEVC base layer is chosen, and colour tags it lacks come from the Dolby Vision track. */
    @Test
    fun dolbyVisionProfile81_isPq() {
        val src = File(dir, "jf_dv_p81_1080p.mp4")
        assumeTrue("jf_dv_p81_1080p.mp4 not pushed", src.exists())

        assertEquals(HdrInput.PQ, VideoCompressor.probeHdr(context, Uri.fromFile(src)).input)
    }

    private fun source(name: String): Uri {
        val src = File(dir, name)
        assertTrue("push $name to ${dir.path} first", src.exists())
        return Uri.fromFile(src)
    }
}
