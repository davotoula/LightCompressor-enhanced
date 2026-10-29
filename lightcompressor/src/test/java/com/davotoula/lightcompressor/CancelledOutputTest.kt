package com.davotoula.lightcompressor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CancelledOutputTest {
    private val logged: MutableList<String> = Collections.synchronizedList(mutableListOf())

    // The production pairing: CompressionBatches runs each video on IO, Compressor transcodes on
    // Default, so the cancellation check on withContext's way back is the one exercised.
    private val scope = CoroutineScope(Dispatchers.IO)
    private val file = File.createTempFile("cancelled-output", ".mp4").apply { delete() }

    @After
    fun tearDown() {
        file.delete()
    }

    private fun CountDownLatch.awaitOrFail() = assertTrue("timed out", await(5, TimeUnit.SECONDS))

    @Test
    fun `deletes the output when the block ends cancelled after writing it`() {
        val written = CountDownLatch(1)
        val cancelLanded = CountDownLatch(1)
        val job =
            scope.launch {
                file.deletedIfCancelled(log = { logged += it }) {
                    withContext(Dispatchers.Default) {
                        file.writeBytes(ByteArray(16))
                        written.countDown()
                        // Finishes normally after the cancel, as a transcode does once its last frame is in.
                        cancelLanded.await(5, TimeUnit.SECONDS)
                        "success"
                    }
                }
            }

        written.awaitOrFail()
        job.cancel()
        cancelLanded.countDown()
        runBlocking { job.join() }

        assertFalse("the finished output must not survive the cancel", file.exists())
        assertTrue(job.isCancelled)
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun `keeps the output and returns the value when not cancelled`() {
        val result =
            runBlocking {
                scope
                    .async {
                        file.deletedIfCancelled(log = { logged += it }) {
                            withContext(Dispatchers.Default) {
                                file.writeBytes(ByteArray(16))
                                "success"
                            }
                        }
                    }.await()
            }

        assertEquals("success", result)
        assertTrue(file.exists())
        assertEquals(emptyList<String>(), logged)
    }

    @Test
    fun `rethrows when nothing was written`() {
        val started = CountDownLatch(1)
        val job =
            scope.launch {
                file.deletedIfCancelled(log = { logged += it }) {
                    withContext(Dispatchers.Default) {
                        started.countDown()
                        awaitCancellation()
                    }
                }
            }

        started.awaitOrFail()
        job.cancel()
        runBlocking { job.join() }

        assertTrue(job.isCancelled)
        assertFalse(file.exists())
        assertEquals(emptyList<String>(), logged)
    }
}
