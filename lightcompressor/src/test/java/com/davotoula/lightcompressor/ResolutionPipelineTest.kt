package com.davotoula.lightcompressor

import com.davotoula.lightcompressor.config.VideoResizer
import com.davotoula.lightcompressor.utils.roundDimension
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Integration tests verifying the full resolution pipeline: resizer -> roundDimension.
 * roundDimension floors to the nearest even number for codec compatibility.
 */
class ResolutionPipelineTest {
    @Test
    fun `limitShortSide preserves portrait 1080x2400 exactly`() {
        val resizer = VideoResizer.limitShortSide(1080.0)
        val (w, h) = resizer.resize(1080.0, 2400.0)
        assertEquals(1080, roundDimension(w))
        assertEquals(2400, roundDimension(h))
    }

    @Test
    fun `limitShortSide 720p downscale portrait`() {
        val resizer = VideoResizer.limitShortSide(720.0)
        val (w, h) = resizer.resize(1080.0, 1920.0)
        assertEquals(720, roundDimension(w))
        assertEquals(1280, roundDimension(h))
    }

    @Test
    fun `limitShortSide 1080 landscape 16x9`() {
        val resizer = VideoResizer.limitShortSide(1080.0)
        val (w, h) = resizer.resize(1920.0, 1080.0)
        assertEquals(1920, roundDimension(w))
        assertEquals(1080, roundDimension(h))
    }

    @Test
    fun `limitShortSide 720p landscape`() {
        val resizer = VideoResizer.limitShortSide(720.0)
        val (w, h) = resizer.resize(1920.0, 1080.0)
        assertEquals(1280, roundDimension(w))
        assertEquals(720, roundDimension(h))
    }

    @Test
    fun `limitSize creates landscape bounding box - crushes portrait`() {
        val resizer = VideoResizer.limitSize(1920.0, 1080.0)
        val (w, h) = resizer.resize(1080.0, 2400.0)
        assertEquals(486, roundDimension(w))
        assertEquals(1080, roundDimension(h))
    }

    @Test
    fun `no resize when already within limit`() {
        val resizer = VideoResizer.limitShortSide(1080.0)
        val (w, h) = resizer.resize(720.0, 1280.0)
        assertEquals(720, roundDimension(w))
        assertEquals(1280, roundDimension(h))
    }
}
