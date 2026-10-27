package com.davotoula.lightcompressor.utils

import android.media.MediaExtractor
import android.media.MediaFormat
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTrackSelectionTest {
    private fun extractor(vararg mimes: String?): MediaExtractor {
        val e = mockk<MediaExtractor>()
        every { e.trackCount } returns mimes.size
        mimes.forEachIndexed { i, mime ->
            val f = mockk<MediaFormat>()
            every { f.getString(MediaFormat.KEY_MIME) } returns mime
            every { e.getTrackFormat(i) } returns f
        }
        return e
    }

    /** Dolby Vision 8.x (iPhone HDR): Android lists the DV track first and the HEVC base layer second. */
    @Test
    fun dolbyVisionWithBaseLayer_picksBaseLayer() {
        val e = extractor("video/dolby-vision", "video/hevc", "audio/mp4a-latm")
        assertEquals(1, CompressorUtils.findVideoTrack(e))
    }

    /** Profile 5 has no base layer: the DV track is returned so the decoder check fails cleanly. */
    @Test
    fun dolbyVisionOnly_picksDolbyVisionTrack() {
        assertEquals(0, CompressorUtils.findVideoTrack(extractor("video/dolby-vision")))
    }

    @Test
    fun audioFirst_picksFirstVideoTrack() {
        assertEquals(1, CompressorUtils.findVideoTrack(extractor("audio/mp4a-latm", "video/avc")))
    }

    @Test
    fun plainHevc_picksIt() {
        assertEquals(0, CompressorUtils.findVideoTrack(extractor("video/hevc", "audio/mp4a-latm")))
    }

    @Test
    fun noVideoTrack_isNotFound() {
        assertTrue(CompressorUtils.findVideoTrack(extractor("audio/mp4a-latm", null)) < 0)
    }
}
