package com.davotoula.lightcompressor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
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
 * whose coroutine never started), or else `onFailure`.
 */
internal class CompressionBatches(
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
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
        // Failures are reported on completion below; the handler only keeps them from crashing.
        val scope = CoroutineScope(batch + dispatcher + CoroutineExceptionHandler { _, _ -> })
        repeat(count) { i ->
            val result = AtomicReference<T?>()
            scope
                .launch { result.set(video(i)) }
                .invokeOnCompletion { cause ->
                    val returned = result.get()
                    when {
                        returned != null -> onResult(i, returned)
                        batch.isCancelled || cause is CancellationException -> onCancelled(i)
                        else -> onFailure(i, cause?.message ?: "")
                    }
                }
        }
        // The batch completes once its videos have, which removes it from [batches].
        batch.complete()
    }

    fun cancelAll() = batches.cancelChildren()
}
