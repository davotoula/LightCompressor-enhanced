package com.davotoula.lightcompressor

import android.util.Log
import kotlinx.coroutines.CancellationException
import java.io.File

/**
 * Runs [block] and, if it ends with the coroutine cancelled, deletes this file before rethrowing.
 * Covers the transcode that finished its output while the cancel was landing (the result is lost
 * at withContext's exit check, and onCancelled carries no path) and a non-streamable encode
 * cancelled halfway through its destination file.
 */
internal suspend fun <T> File.deletedIfCancelled(
    log: (String) -> Unit = { Log.w("VideoCompressor", it) },
    block: suspend () -> T,
): T =
    try {
        block()
    } catch (e: CancellationException) {
        if (exists() && !delete()) log("Could not delete the cancelled output $path")
        throw e
    }
