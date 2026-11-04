package com.davotoula.lightcompressor

import org.junit.Assert.assertEquals
import org.junit.Test

class FailureMessagesTest {
    @Test
    fun `describe names the exception and its message`() {
        assertEquals("IllegalStateException: boom", IllegalStateException("boom").describe())
    }

    @Test
    fun `describe names the exception alone when the message is null or blank`() {
        assertEquals("IllegalStateException", IllegalStateException().describe())
        assertEquals("IllegalStateException", IllegalStateException("").describe())
        assertEquals("IllegalStateException", IllegalStateException("  ").describe())
    }

    @Test
    fun `describe trims the message`() {
        assertEquals("IOException: disk full", java.io.IOException(" disk full \n").describe())
    }

    @Test
    fun `describe falls back to the full name for an anonymous class`() {
        val anonymous = object : RuntimeException("boom") {}
        assertEquals("${anonymous.javaClass.name}: boom", anonymous.describe())
    }

    @Test
    fun `failureMessageOrDefault keeps and trims a message`() {
        assertEquals("Compression cancelled", failureMessageOrDefault(" Compression cancelled "))
    }

    @Test
    fun `failureMessageOrDefault replaces null and blank messages`() {
        assertEquals(DEFAULT_FAILURE_MESSAGE, failureMessageOrDefault(null))
        assertEquals(DEFAULT_FAILURE_MESSAGE, failureMessageOrDefault(""))
        assertEquals(DEFAULT_FAILURE_MESSAGE, failureMessageOrDefault("   "))
    }
}
