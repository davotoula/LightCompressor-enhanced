package com.abedelazizshe.lightcompressorlibrary.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NumbersUtilsTest {

    @Test
    fun `roundDimension - 1080 stays 1080`() {
        assertEquals(1080, roundDimension(1080.0))
    }

    @Test
    fun `roundDimension - 720 stays 720`() {
        assertEquals(720, roundDimension(720.0))
    }

    @Test
    fun `roundDimension - 1920 stays 1920`() {
        assertEquals(1920, roundDimension(1920.0))
    }

    @Test
    fun `roundDimension - 2400 stays 2400`() {
        assertEquals(2400, roundDimension(2400.0))
    }

    @Test
    fun `roundDimension - odd value floors to even`() {
        assertEquals(810, roundDimension(811.0))
    }

    @Test
    fun `roundDimension - fractional floors to even`() {
        assertEquals(486, roundDimension(486.7))
    }

    @Test
    fun `roundDimension - odd fractional floors to even`() {
        assertEquals(486, roundDimension(487.9))
    }

    @Test
    fun `roundDimension - zero`() {
        assertEquals(0, roundDimension(0.0))
    }

    // fallbackTo16Aligned tests

    @Test
    fun `fallback - 1080x2400 returns 1072x2400`() {
        assertEquals(Pair(1072, 2400), fallbackTo16Aligned(1080, 2400))
    }

    @Test
    fun `fallback - already 16-aligned returns null`() {
        assertNull(fallbackTo16Aligned(1280, 720))
    }

    @Test
    fun `fallback - 1920x1080 returns 1920x1072`() {
        assertEquals(Pair(1920, 1072), fallbackTo16Aligned(1920, 1080))
    }

    @Test
    fun `fallback - both dimensions not 16-aligned`() {
        assertEquals(Pair(1072, 1072), fallbackTo16Aligned(1080, 1080))
    }

    @Test
    fun `fallback - too small for 16-alignment returns null`() {
        assertNull(fallbackTo16Aligned(10, 720))
    }

    @Test
    fun `fallback - zero width returns null`() {
        assertNull(fallbackTo16Aligned(0, 720))
    }

    @Test
    fun `fallback - 4K 3840x2160 already aligned returns null`() {
        assertNull(fallbackTo16Aligned(3840, 2160))
    }
}
