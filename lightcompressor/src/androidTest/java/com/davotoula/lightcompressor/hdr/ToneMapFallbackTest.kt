package com.davotoula.lightcompressor.hdr

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davotoula.lightcompressor.CompressionProgressListener
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.compressor.Transcoder
import com.davotoula.lightcompressor.config.HdrMode
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Tone maps the 10-bit HLG clip `orig2.mp4` through both sampling paths, so their outputs can be
 * compared off-device. Push the clip to the test app's external files dir first (see the HDR plan).
 */
@RunWith(AndroidJUnit4::class)
class ToneMapFallbackTest {
    private val dir: File =
        InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!

    @After
    fun resetFlag() {
        HdrDebug.forceExternalSampling = false
    }

    @Test
    fun toneMap_externalSampling() {
        HdrDebug.forceExternalSampling = true
        toneMap("tm_es2.mp4")
    }

    @Test
    fun toneMap_yuvTargetSampling() {
        HdrDebug.forceExternalSampling = false
        toneMap("tm_es3.mp4")
    }

    private fun toneMap(outName: String) {
        val src = File(dir, "orig2.mp4")
        assertTrue("push orig2.mp4 to ${dir.path} first", src.exists())
        val out = File(dir, outName).apply { delete() }
        // As Compressor builds it for this −90° source: dimensions swapped, rotation 0, because the
        // decoder's surface transform applies the rotation.
        val request =
            Transcoder.Request(
                index = 0,
                width = 1080,
                height = 1920,
                bitrate = 10_000_000L,
                destination = out,
                streamable = false,
                disableAudio = true,
                rotation = 0,
                durationUs = 4_610_400L,
                listener = NoOpListener,
                isCancelled = { false },
                hdrMode = HdrMode.TONE_MAP_TO_SDR,
            )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val result = Transcoder(VideoCodec.H265, context, Uri.fromFile(src), request).transcode()
        assertTrue("transcode failed: ${result.failureMessage}", result.success)
        assertTrue("no output written", out.length() > 0)
    }

    private object NoOpListener : CompressionProgressListener {
        override fun onProgressChanged(
            index: Int,
            percent: Float,
        ) = Unit

        override fun onProgressCancelled(index: Int) = Unit
    }
}
