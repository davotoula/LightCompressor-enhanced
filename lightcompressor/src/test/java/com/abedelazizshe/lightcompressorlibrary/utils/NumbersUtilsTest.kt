package com.abedelazizshe.lightcompressorlibrary.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class NumbersUtilsTest {

    @Test
    fun `roundDimension - already aligned to 16`() {
        assertEquals(1280, roundDimension(1280.0))
    }

    @Test
    fun `roundDimension - rounds up to next 16`() {
        assertEquals(1088, roundDimension(1080.0))
    }

    @Test
    fun `roundDimension - rounds down to previous 16`() {
        assertEquals(480, roundDimension(486.0))
    }

    @Test
    fun `roundDimension - small value`() {
        assertEquals(16, roundDimension(17.0))
    }

    @Test
    fun `roundDimension - exact multiple of 16`() {
        assertEquals(1600, roundDimension(1600.0))
    }

    @Test
    fun `roundDimension - rounding boundary at 0_5`() {
        assertEquals(48, roundDimension(40.0))
    }

    @Test
    fun `roundDimension - zero`() {
        assertEquals(0, roundDimension(0.0))
    }
}
