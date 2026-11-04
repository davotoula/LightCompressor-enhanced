package com.davotoula.lightcompressor

import org.junit.Assert.assertEquals
import org.junit.Test

class FailureMessagesTest {
    @Test
    fun `describe names the exception and its message`() {
        assertEquals("java.lang.IllegalStateException: boom", IllegalStateException("boom").describe())
    }

    @Test
    fun `describe names the exception alone when the message is null`() {
        assertEquals("java.lang.IllegalStateException", IllegalStateException().describe())
    }

    @Test
    fun `describe names the exception alone when the message is blank`() {
        assertEquals("java.lang.IllegalStateException", IllegalStateException("").describe())
        assertEquals("java.lang.IllegalStateException", IllegalStateException("  ").describe())
    }

    @Test
    fun `describe trims the message`() {
        assertEquals("java.io.IOException: disk full", java.io.IOException(" disk full \n").describe())
    }

    @Test
    fun `failureMessageOrDefault keeps a message`() {
        assertEquals("Compression cancelled", failureMessageOrDefault("Compression cancelled"))
    }

    @Test
    fun `failureMessageOrDefault replaces null and blank messages`() {
        assertEquals(DEFAULT_FAILURE_MESSAGE, failureMessageOrDefault(null))
        assertEquals(DEFAULT_FAILURE_MESSAGE, failureMessageOrDefault(""))
        assertEquals(DEFAULT_FAILURE_MESSAGE, failureMessageOrDefault("   "))
    }
}
