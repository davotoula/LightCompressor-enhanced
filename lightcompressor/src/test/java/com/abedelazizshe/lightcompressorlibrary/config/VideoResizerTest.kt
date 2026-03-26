package com.abedelazizshe.lightcompressorlibrary.config

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoResizerTest {

    private val delta = 0.01

    // -- limitSize (two-value) --

    @Test
    fun `limitSize - landscape within bounds`() {
        val resizer = VideoResizer.limitSize(1920.0, 1080.0)
        val (w, h) = resizer.resize(1280.0, 720.0)
        assertEquals(1280.0, w, delta)
        assertEquals(720.0, h, delta)
    }

    @Test
    fun `limitSize - landscape exceeding bounds`() {
        val resizer = VideoResizer.limitSize(1920.0, 1080.0)
        val (w, h) = resizer.resize(3840.0, 2160.0)
        assertEquals(1920.0, w, delta)
        assertEquals(1080.0, h, delta)
    }

    @Test
    fun `limitSize - exactly at bounds returns unchanged dimensions`() {
        val resizer = VideoResizer.limitSize(1920.0, 1080.0)
        val (w, h) = resizer.resize(1920.0, 1080.0)
        assertEquals(1920.0, w, delta)
        assertEquals(1080.0, h, delta)
    }

    @Test
    fun `limitSize - portrait into landscape box crushes dimensions`() {
        val resizer = VideoResizer.limitSize(1920.0, 1080.0)
        val (w, h) = resizer.resize(1080.0, 2400.0)
        assertEquals(486.0, w, delta)
        assertEquals(1080.0, h, delta)
    }

    @Test
    fun `limitSize - single value`() {
        val resizer = VideoResizer.limitSize(1920.0)
        val (w, h) = resizer.resize(1080.0, 2400.0)
        assertEquals(864.0, w, delta)
        assertEquals(1920.0, h, delta)
    }

    // -- scale --

    @Test
    fun `scale - by 0_5`() {
        val resizer = VideoResizer.scale(0.5)
        val (w, h) = resizer.resize(1920.0, 1080.0)
        assertEquals(960.0, w, delta)
        assertEquals(540.0, h, delta)
    }

    @Test
    fun `scale - by 1_0 no change`() {
        val resizer = VideoResizer.scale(1.0)
        val (w, h) = resizer.resize(1920.0, 1080.0)
        assertEquals(1920.0, w, delta)
        assertEquals(1080.0, h, delta)
    }

    @Test
    fun `scale - portrait by 0_75`() {
        val resizer = VideoResizer.scale(0.75)
        val (w, h) = resizer.resize(1080.0, 1920.0)
        assertEquals(810.0, w, delta)
        assertEquals(1440.0, h, delta)
    }

    // -- matchSize --

    @Test
    fun `matchSize - landscape`() {
        val resizer = VideoResizer.matchSize(1920.0, 1080.0)
        val (w, h) = resizer.resize(3840.0, 2160.0)
        assertEquals(1920.0, w, delta)
        assertEquals(1080.0, h, delta)
    }

    @Test
    fun `matchSize - portrait`() {
        val resizer = VideoResizer.matchSize(1920.0, 1080.0)
        val (w, h) = resizer.resize(1080.0, 2400.0)
        assertEquals(486.0, w, delta)
        assertEquals(1080.0, h, delta)
    }

    @Test
    fun `matchSize - with stretch ignores aspect ratio`() {
        val resizer = VideoResizer.matchSize(1280.0, 720.0, stretch = true)
        val (w, h) = resizer.resize(1080.0, 1920.0)
        assertEquals(1280.0, w, delta)
        assertEquals(720.0, h, delta)
    }

    // -- limitShortSide (new API) --

    @Test
    fun `limitShortSide - portrait short side at limit no resize`() {
        val resizer = VideoResizer.limitShortSide(1080.0)
        val (w, h) = resizer.resize(1080.0, 2400.0)
        assertEquals(1080.0, w, delta)
        assertEquals(2400.0, h, delta)
    }

    @Test
    fun `limitShortSide - landscape short side at limit no resize`() {
        val resizer = VideoResizer.limitShortSide(1080.0)
        val (w, h) = resizer.resize(1920.0, 1080.0)
        assertEquals(1920.0, w, delta)
        assertEquals(1080.0, h, delta)
    }

    @Test
    fun `limitShortSide - portrait short side above limit scales down`() {
        val resizer = VideoResizer.limitShortSide(1080.0)
        val (w, h) = resizer.resize(2160.0, 3840.0)
        assertEquals(1080.0, w, delta)
        assertEquals(1920.0, h, delta)
    }

    @Test
    fun `limitShortSide - landscape short side above limit scales down`() {
        val resizer = VideoResizer.limitShortSide(1080.0)
        val (w, h) = resizer.resize(3840.0, 2160.0)
        assertEquals(1920.0, w, delta)
        assertEquals(1080.0, h, delta)
    }

    @Test
    fun `limitShortSide - short side below limit no resize`() {
        val resizer = VideoResizer.limitShortSide(720.0)
        val (w, h) = resizer.resize(640.0, 480.0)
        assertEquals(640.0, w, delta)
        assertEquals(480.0, h, delta)
    }

    @Test
    fun `limitShortSide - square input above limit`() {
        val resizer = VideoResizer.limitShortSide(720.0)
        val (w, h) = resizer.resize(1080.0, 1080.0)
        assertEquals(720.0, w, delta)
        assertEquals(720.0, h, delta)
    }

    @Test
    fun `limitShortSide - square input at limit`() {
        val resizer = VideoResizer.limitShortSide(720.0)
        val (w, h) = resizer.resize(720.0, 720.0)
        assertEquals(720.0, w, delta)
        assertEquals(720.0, h, delta)
    }

    @Test
    fun `limitShortSide - non-integer scale factor`() {
        val resizer = VideoResizer.limitShortSide(700.0)
        val (w, h) = resizer.resize(1920.0, 1080.0)
        assertEquals(700.0, h, delta)
        assertEquals(1244.44, w, 0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `limitShortSide - zero limit throws`() {
        VideoResizer.limitShortSide(0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `limitShortSide - negative limit throws`() {
        VideoResizer.limitShortSide(-1.0)
    }
}
