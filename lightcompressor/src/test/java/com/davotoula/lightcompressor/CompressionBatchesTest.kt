package com.davotoula.lightcompressor

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class CompressionBatchesTest {
    private val errors: MutableList<Throwable> = Collections.synchronizedList(mutableListOf())
    private val batches = CompressionBatches(logError = { errors += it })
    private val events: MutableList<String> = Collections.synchronizedList(mutableListOf())

    @After
    fun tearDown() {
        batches.cancelAll()
    }

    /**
     * Launches a batch whose terminal callbacks are recorded as "<name>:<event>:<index>", and returns
     * its handle.
     */
    private fun launch(
        name: String,
        count: Int,
        done: CountDownLatch,
        into: CompressionBatches = batches,
        video: suspend CoroutineScope.(Int) -> String,
    ) = into.launch(
        count = count,
        onResult = { i, result ->
            events += "$name:$result:$i"
            done.countDown()
        },
        onFailure = { i, message ->
            events += "$name:failure($message):$i"
            done.countDown()
        },
        onCancelled = { i ->
            events += "$name:cancelled:$i"
            done.countDown()
        },
        video = video,
    )

    private fun CountDownLatch.awaitOrFail() = assertTrue("timed out waiting for callbacks", await(5, TimeUnit.SECONDS))

    @Test
    fun `cancelAll cancels every running batch`() {
        val started = CountDownLatch(4)
        val done = CountDownLatch(4)
        listOf("a", "b").forEach { name ->
            launch(name, 2, done) {
                started.countDown()
                awaitCancellation()
            }
        }
        started.awaitOrFail()

        batches.cancelAll()

        done.awaitOrFail()
        assertEquals(
            setOf("a:cancelled:0", "a:cancelled:1", "b:cancelled:0", "b:cancelled:1"),
            events.toSet(),
        )
        assertEquals(4, events.size)
    }

    @Test
    fun `a batch started after cancelAll runs to completion`() {
        val started = CountDownLatch(1)
        val done = CountDownLatch(2)
        launch("old", 1, done) {
            started.countDown()
            awaitCancellation()
        }
        started.awaitOrFail()

        batches.cancelAll()
        launch("new", 1, done) { "success" }

        done.awaitOrFail()
        assertEquals(setOf("old:cancelled:0", "new:success:0"), events.toSet())
    }

    @Test
    fun `a video that never started reports onCancelled`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dispatcherFree = CountDownLatch(1)
            executor.execute { dispatcherFree.await() }
            val blockedBatches = CompressionBatches(executor.asCoroutineDispatcher()) { errors += it }
            val bodyRan = AtomicBoolean(false)
            val done = CountDownLatch(2)
            launch("a", 2, done, into = blockedBatches) {
                bodyRan.set(true)
                "success"
            }

            blockedBatches.cancelAll()
            dispatcherFree.countDown()

            done.awaitOrFail()
            assertEquals(setOf("a:cancelled:0", "a:cancelled:1"), events.toSet())
            assertFalse(bodyRan.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a video reaching its start after cancelAll stays cancelled`() {
        val resolving = CountDownLatch(1)
        val pathResolved = CountDownLatch(1)
        val sawActive = AtomicBoolean(true)
        val done = CountDownLatch(1)
        launch("old", 1, done) {
            resolving.countDown()
            // Blocking work that ignores cancellation, like resolving the input path.
            pathResolved.await(5, TimeUnit.SECONDS)
            sawActive.set(isActive)
            ensureActive()
            "success"
        }
        resolving.awaitOrFail()

        batches.cancelAll()
        // A later start() used to switch the shared running flag back on.
        launch("new", 0, CountDownLatch(0)) { "success" }
        pathResolved.countDown()

        done.awaitOrFail()
        assertFalse(sawActive.get())
        assertEquals(listOf("old:cancelled:0"), events.toList())
    }

    @Test
    fun `a video that fails after cancelAll reports onCancelled`() {
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val done = CountDownLatch(1)
        launch("a", 1, done) {
            started.countDown()
            // A cancel that breaks the encoder surfaces as an ordinary exception.
            cancelled.await(5, TimeUnit.SECONDS)
            error("encoder released")
        }
        started.awaitOrFail()

        batches.cancelAll()
        cancelled.countDown()

        done.awaitOrFail()
        assertEquals(listOf("a:cancelled:0"), events.toList())
    }

    @Test
    fun `a failing video reports onFailure and the rest of its batch carries on`() {
        val done = CountDownLatch(2)
        launch("a", 2, done) { i ->
            if (i == 0) error("boom")
            "success"
        }

        done.awaitOrFail()
        // Give a duplicate callback the chance to arrive.
        Thread.sleep(100)
        assertEquals(setOf("a:failure(boom):0", "a:success:1"), events.toSet())
        assertEquals(2, events.size)
        assertEquals(listOf("boom"), errors.map { it.message })
    }

    @Test
    fun `a failure without a message reports the exception`() {
        val done = CountDownLatch(1)
        launch("a", 1, done) { throw IllegalStateException() }

        done.awaitOrFail()
        assertEquals(listOf("a:failure(java.lang.IllegalStateException):0"), events.toList())
    }

    @Test
    fun `an exception thrown by a callback is logged`() {
        val logged = CountDownLatch(1)
        val loggingBatches =
            CompressionBatches(Dispatchers.IO) {
                errors += it
                logged.countDown()
            }
        loggingBatches.launch(
            count = 1,
            onResult = { _, _: String -> throw IllegalStateException("listener bug") },
            onFailure = { _, _ -> events += "failure" },
            onCancelled = { events += "cancelled" },
        ) { "success" }

        logged.awaitOrFail()
        assertEquals("listener bug", errors.single().cause?.message)
        assertEquals(emptyList<String>(), events.toList())
    }

    @Test
    fun `cancelAll is seen by a job polled from another thread, as Compressor builds it`() {
        val polling = CountDownLatch(1)
        val sawCancel = AtomicBoolean(false)
        val done = CountDownLatch(1)
        launch("a", 1, done) {
            withContext(Dispatchers.Default) {
                val isCancelled = coroutineContext.job::isCancelled
                // The transcode blocks its coroutine; the watchdog polls from its own thread.
                val watchdog =
                    Thread {
                        polling.countDown()
                        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                        while (!isCancelled() && System.nanoTime() < deadline) Thread.sleep(1)
                        sawCancel.set(isCancelled())
                    }.apply { start() }
                watchdog.join()
            }
            "success"
        }
        polling.awaitOrFail()

        batches.cancelAll()

        done.awaitOrFail()
        assertTrue(sawCancel.get())
        assertEquals(listOf("a:cancelled:0"), events.toList())
    }

    @Test
    fun `a video that returned keeps its result when cancelAll lands afterwards`() {
        val done = CountDownLatch(1)
        launch("a", 1, done) { "success" }
        done.awaitOrFail()

        batches.cancelAll()

        Thread.sleep(100)
        assertEquals(listOf("a:success:0"), events.toList())
    }

    @Test
    fun `handle cancel cancels its own batch only`() {
        val started = CountDownLatch(4)
        val release = CompletableDeferred<Unit>()
        val aDone = CountDownLatch(2)
        val bDone = CountDownLatch(2)
        val a =
            launch("a", 2, aDone) {
                started.countDown()
                awaitCancellation()
            }
        launch("b", 2, bDone) {
            started.countDown()
            release.await()
            "success"
        }
        started.awaitOrFail()

        a.cancel()

        aDone.awaitOrFail()
        assertEquals(setOf("a:cancelled:0", "a:cancelled:1"), events.toSet())
        release.complete(Unit)
        bDone.awaitOrFail()
        assertEquals(
            setOf("a:cancelled:0", "a:cancelled:1", "b:success:0", "b:success:1"),
            events.toSet(),
        )
        assertEquals(4, events.size)
    }

    @Test
    fun `handle cancel after the batch finished is a no-op`() {
        val done = CountDownLatch(1)
        val handle = launch("x", 1, done) { "success" }
        done.awaitOrFail()

        handle.cancel()

        Thread.sleep(100)
        assertEquals(listOf("x:success:0"), events.toList())
    }

    @Test
    fun `handle cancel reports onCancelled for a video that never started`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val started = CountDownLatch(1)
            val cancelled = CountDownLatch(1)
            val laterBodyRan = AtomicBoolean(false)
            val singleThreadBatches = CompressionBatches(executor.asCoroutineDispatcher()) { errors += it }
            val done = CountDownLatch(2)
            val handle =
                launch("a", 2, done, into = singleThreadBatches) { i ->
                    if (i == 0) {
                        started.countDown()
                        // Holds the only thread, so video 1 can't start until the cancel has landed.
                        cancelled.await(5, TimeUnit.SECONDS)
                        ensureActive()
                    }
                    laterBodyRan.set(true)
                    "success"
                }
            started.awaitOrFail()

            handle.cancel()
            cancelled.countDown()

            done.awaitOrFail()
            assertEquals(setOf("a:cancelled:0", "a:cancelled:1"), events.toSet())
            assertFalse(laterBodyRan.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
