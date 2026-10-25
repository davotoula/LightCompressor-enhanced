package com.davotoula.lightcompressor.hdr

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.davotoula.lightcompressor.CompressionProgressListener
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.compressor.Transcoder
import com.davotoula.lightcompressor.config.HdrMode
import com.davotoula.lightcompressor.video.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Drives the keep-HDR → tone-map retry in [Transcoder] end to end on the 10-bit HLG clip `orig2.mp4`
 * (push it to the test app's external files dir first, as for [ToneMapFallbackTest]).
 *
 * Both tests record the plan each attempt was given, so they prove the job decided KeepHdr, was
 * rejected, and then succeeded as ToneMapToSdr.
 */
@RunWith(AndroidJUnit4::class)
class KeepHdrRetryTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir: File = context.getExternalFilesDir(null)!!

    /** The plan names an encoder that does not exist, so `createByCodecName` fails before anything starts. */
    @Test
    fun earlyRejection_unknownEncoder_retriesAsToneMap() {
        val caps =
            object : HdrCapabilities {
                override fun hlgEncoder(mime: String) =
                    HdrEncoder("does.not.exist", CodecProfileLevel.HEVCProfileMain10)

                override fun pqEncoder(mime: String): HdrEncoder? = null
            }
        val outDir = freshDir("keep_retry_early")
        val transcoder =
            RecordingTranscoder(context, source(), request(outDir), lazyOf(caps), downgradeKeepProfile = false)

        val result = transcoder.transcode()

        assertRetriedAsToneMap(transcoder, result)
        assertFalse("the keep attempt's encoder should not have been created", transcoder.keepEncoderCreated)
        assertFalse("the keep attempt should not have reached the decoder", transcoder.keepDecoderCreated)
        assertSingleSdrOutput(outDir, result)
    }

    /**
     * A real HDR encoder is configured with a Main (8-bit) profile in place of Main10, so it starts,
     * the decoder starts, and the plan's output-format check rejects at INFO_OUTPUT_FORMAT_CHANGED.
     */
    @Test
    fun lateRejection_outputFormatMismatch_retriesAsToneMap() {
        val caps = MediaCodecHdrCapabilities()
        assumeTrue(
            "device has no HEVC encoder with HLG/HDR editing",
            caps.hlgEncoder(VideoCodec.H265.mimeType) != null,
        )
        val outDir = freshDir("keep_retry_late")
        val transcoder =
            RecordingTranscoder(context, source(), request(outDir), lazyOf(caps), downgradeKeepProfile = true)

        val result = transcoder.transcode()

        assertRetriedAsToneMap(transcoder, result)
        assertTrue("the keep attempt's encoder should have been created", transcoder.keepEncoderCreated)
        assertTrue("the keep attempt should have started the decoder", transcoder.keepDecoderCreated)
        assertSingleSdrOutput(outDir, result)
    }

    private fun source(): Uri {
        val src = File(dir, "orig2.mp4")
        assertTrue("push orig2.mp4 to ${dir.path} first", src.exists())
        return Uri.fromFile(src)
    }

    private fun freshDir(name: String): File =
        File(dir, name).apply {
            deleteRecursively()
            assertTrue("could not create $path", mkdirs())
        }

    // As Compressor builds it for this −90° source: dimensions swapped, rotation 0.
    private fun request(outDir: File) =
        Transcoder.Request(
            index = 0,
            width = 1080,
            height = 1920,
            bitrate = 10_000_000L,
            destination = File(outDir, "out.mp4"),
            streamablePath = File(outDir, "out_streamable.mp4").path,
            disableAudio = true,
            rotation = 0,
            durationUs = 4_610_400L,
            listener = NoOpListener,
            hdrMode = HdrMode.KEEP_IF_SUPPORTED,
        )

    private fun assertRetriedAsToneMap(
        transcoder: RecordingTranscoder,
        result: Result,
    ) {
        val kinds = transcoder.attempts.map { it.javaClass.simpleName }
        assertEquals("attempted plans", listOf("KeepHdr", "ToneMapToSdr"), kinds)
        assertTrue("transcode failed: ${result.failureMessage}", result.success)
    }

    private fun assertSingleSdrOutput(
        outDir: File,
        result: Result,
    ) {
        val files =
            outDir
                .listFiles()
                .orEmpty()
                .map { it.name }
                .sorted()
        assertFalse("temporary muxer files left behind: $files", files.any { it.startsWith("transcode_") })
        assertEquals("expected exactly one output file, found $files", 1, files.size)
        val output = File(outDir, files.single())
        assertEquals(output.path, result.path)
        assertTrue("output is empty", output.length() > 0)

        val format = videoFormat(output)
        val transfer = format.intOrNull(MediaFormat.KEY_COLOR_TRANSFER)
        assertNotEquals("output is still HLG: $format", MediaFormat.COLOR_TRANSFER_HLG, transfer)
        format.intOrNull(MediaFormat.KEY_COLOR_STANDARD)?.let {
            assertEquals("output colour standard: $format", MediaFormat.COLOR_STANDARD_BT709, it)
        }
        format.intOrNull(MediaFormat.KEY_PROFILE)?.let {
            assertNotEquals("output is still Main10: $format", CodecProfileLevel.HEVCProfileMain10, it)
        }
    }

    private fun videoFormat(file: File): MediaFormat {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            return (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .first { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    /**
     * Records each attempt's plan and how far a KeepHdr attempt got. With [downgradeKeepProfile], a
     * KeepHdr attempt's encoder is configured with HEVC Main instead of the plan's Main10 profile.
     */
    private class RecordingTranscoder(
        context: Context,
        srcUri: Uri,
        request: Transcoder.Request,
        caps: Lazy<HdrCapabilities>,
        private val downgradeKeepProfile: Boolean,
    ) : Transcoder(VideoCodec.H265, context, srcUri, request, caps) {
        val attempts = mutableListOf<ColorPlan>()
        var keepEncoderCreated = false
        var keepDecoderCreated = false

        override fun tuneEncoderFormat(
            encoderFormat: MediaFormat,
            plan: ColorPlan,
        ) {
            attempts += plan
            super.tuneEncoderFormat(encoderFormat, plan)
            if (downgradeKeepProfile && plan is ColorPlan.KeepHdr) {
                encoderFormat.setInteger(MediaFormat.KEY_PROFILE, CodecProfileLevel.HEVCProfileMain)
            }
        }

        override fun createEncoderGuarded(
            outputFormat: MediaFormat,
            plan: ColorPlan,
        ): MediaCodec? =
            super.createEncoderGuarded(outputFormat, plan).also {
                if (it != null && plan is ColorPlan.KeepHdr) keepEncoderCreated = true
            }

        override fun createDecoderGuarded(
            mime: String,
            format: MediaFormat,
            surface: Surface?,
        ): MediaCodec? =
            super.createDecoderGuarded(mime, format, surface).also {
                if (it != null && attempts.lastOrNull() is ColorPlan.KeepHdr) keepDecoderCreated = true
            }
    }

    private object NoOpListener : CompressionProgressListener {
        override fun onProgressChanged(
            index: Int,
            percent: Float,
        ) = Unit

        override fun onProgressCancelled(index: Int) = Unit
    }
}
