package com.abedelazizshe.lightcompressorlibrary

import com.abedelazizshe.lightcompressorlibrary.config.VideoResizer
import com.abedelazizshe.lightcompressorlibrary.utils.roundDimension
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Integration tests verifying the full resolution pipeline: resizer -> roundDimension.
 * Documents the original bug and its fix.
 */
class ResolutionPipelineTest {

    @Test
    fun `original bug - limitSize crushes portrait 1080x2400 to 480x1088`() {
        val resizer = VideoResizer.limitSize(1920.0, 1080.0)
        val (w, h) = resizer.resize(1080.0, 2400.0)
        val roundedW = roundDimension(w)
        val roundedH = roundDimension(h)
        assertEquals(480, roundedW)
        assertEquals(1088, roundedH)
    }

    @Test
    fun `fix - limitShortSide preserves portrait 1080x2400`() {
        val resizer = VideoResizer.limitShortSide(1080.0)
        val (w, h) = resizer.resize(1080.0, 2400.0)
        val roundedW = roundDimension(w)
        val roundedH = roundDimension(h)
        assertEquals(1088, roundedW)
        assertEquals(2400, roundedH)
    }

    @Test
    fun `portrait MEDIUM downscale with limitShortSide`() {
        val resizer = VideoResizer.limitShortSide(720.0)
        val (w, h) = resizer.resize(1080.0, 1920.0)
        val roundedW = roundDimension(w)
        val roundedH = roundDimension(h)
        assertEquals(720, roundedW)
        assertEquals(1280, roundedH)
    }

    @Test
    fun `standard 16x9 landscape unchanged with limitShortSide`() {
        val resizer = VideoResizer.limitShortSide(1080.0)
        val (w, h) = resizer.resize(1920.0, 1080.0)
        val roundedW = roundDimension(w)
        val roundedH = roundDimension(h)
        assertEquals(1920, roundedW)
        assertEquals(1088, roundedH)
    }

    @Test
    fun `roundDimension may push short side slightly above limit - expected codec alignment`() {
        val resizer = VideoResizer.limitShortSide(1080.0)
        val (w, _) = resizer.resize(1080.0, 2400.0)
        assertEquals(1080.0, w, 0.01)
        assertEquals(1088, roundDimension(w))
    }
}
