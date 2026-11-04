package com.davotoula.lce

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsTrackerTest {
    @Test
    fun `reportableFailureMessage keeps a message`() {
        assertEquals("Compression cancelled", reportableFailureMessage("Compression cancelled"))
    }

    @Test
    fun `reportableFailureMessage replaces a blank message`() {
        assertEquals(NO_FAILURE_MESSAGE, reportableFailureMessage(""))
        assertEquals(NO_FAILURE_MESSAGE, reportableFailureMessage("  "))
    }
}
