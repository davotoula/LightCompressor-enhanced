package com.davotoula.lightcompressor

import kotlinx.coroutines.Job

/**
 * One [VideoCompressor.start] call. [cancel] stops every video of that call still running,
 * including videos that haven't started yet; each of them gets [CompressionListener.onCancelled]
 * unless it has already finished. Other [VideoCompressor.start] calls are unaffected. Cancelling
 * a finished batch does nothing.
 */
class CompressionHandle internal constructor(
    private val batch: Job,
) {
    fun cancel() = batch.cancel()
}
