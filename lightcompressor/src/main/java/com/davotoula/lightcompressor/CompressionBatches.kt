package com.davotoula.lightcompressor

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs each [VideoCompressor.start] call as a batch: a parent job whose children are the batch's
 * videos, one coroutine each. [cancelAll] cancels every batch running at that moment; a batch
 * launched afterwards is unaffected, and nothing can undo a cancel.
 *
 * Every video ends in exactly one terminal callback, reported when its coroutine ends: `onResult`
 * with what the video returned, or else `onCancelled` if its batch was cancelled (including a video
 * whose coroutine never started), or else `onFailure`. A video's exception, and any exception a
 * callback throws, goes to [logError] rather than to the caller.
 */
internal class CompressionBatches(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logError: (Throwable) -> Unit = { Log.e(TAG, "Compression job error", it) },
) {
    private val batches = SupervisorJob()

    fun <T : Any> launch(
        count: Int,
        onResult: (index: Int, result: T) -> Unit,
        onFailure: (index: Int, message: String) -> Unit,
        onCancelled: (index: Int) -> Unit,
        video: suspend CoroutineScope.(index: Int) -> T,
    ) {
        // Supervisor, so one video failing doesn't cancel the rest of its batch.
        val batch = SupervisorJob(batches)
        // Failures are reported on completion below; the handler keeps them from crashing the app.
        val scope = CoroutineScope(batch + dispatcher + CoroutineExceptionHandler { _, e -> logError(e) })
        repeat(count) { i ->
            val result = AtomicReference<T?>()
            // Lazy, so the handler is registered before the video can finish: a handler added to a
            // finished job would run on this thread, inside VideoCompressor.start().
            val job = scope.launch(start = CoroutineStart.LAZY) { result.set(video(i)) }
            job.invokeOnCompletion { cause ->
                val returned = result.get()
                when {
                    returned != null -> onResult(i, returned)
                    batch.isCancelled || cause is CancellationException -> onCancelled(i)
                    else -> onFailure(i, cause?.run { message ?: toString() }.orEmpty())
                }
            }
            job.start()
        }
        // The batch completes once its videos have, which removes it from [batches].
        batch.complete()
    }

    fun cancelAll() = batches.cancelChildren()

    private companion object {
        const val TAG = "CompressionBatches"
    }
}
