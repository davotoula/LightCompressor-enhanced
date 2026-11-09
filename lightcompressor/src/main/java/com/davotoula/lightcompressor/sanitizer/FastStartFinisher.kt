package com.davotoula.lightcompressor.sanitizer

import android.util.Log
import com.davotoula.lightcompressor.utils.StreamableVideo
import java.io.File
import java.io.IOException

private const val TAG = "FastStartFinisher"

/**
 * Writes [muxerFile] to [destination] with `moov` moved ahead of `mdat`, and deletes [muxerFile].
 *
 * When the conversion does not apply (the input is already fast-start) or fails with an
 * [IOException], [muxerFile] is moved to [destination] unchanged.
 *
 * @return true when [destination] is fast-start: converted here, or already fast-start.
 */
internal fun finishFastStart(
    muxerFile: File,
    destination: File,
): Boolean {
    deleteExisting(destination)
    // False when the input is already fast-start or can't be converted; start then leaves no destination behind.
    val converted =
        try {
            StreamableVideo.start(muxerFile, destination)
        } catch (ioe: IOException) {
            Log.w(TAG, "Fast-start conversion of ${destination.path} failed: ${ioe.message}")
            false
        }
    if (converted) {
        muxerFile.delete()
        return true
    }
    moveFile(muxerFile, destination)
    return Mp4Sanitizer.isFastStart(destination)
}

/** Moves [source] to [destination], replacing it; copies when a rename is not possible (another file system). */
internal fun moveReplacing(
    source: File,
    destination: File,
) {
    deleteExisting(destination)
    moveFile(source, destination)
}

private fun deleteExisting(destination: File) {
    if (destination.exists() && !destination.delete()) {
        Log.w(TAG, "Unable to delete existing destination file ${destination.absolutePath}")
    }
}

private fun moveFile(
    source: File,
    destination: File,
) {
    if (!source.renameTo(destination)) {
        source.copyTo(destination, overwrite = true)
        source.delete()
    }
}
