package com.davotoula.lightcompressor.utils

import android.util.Log
import com.davotoula.lightcompressor.data.MDAT_ATOM
import com.davotoula.lightcompressor.data.MOOV_ATOM
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

private const val TAG = "FastStart"
private const val ATOM_HEADER_SIZE = 8L
private const val LARGE_ATOM_HEADER_SIZE = 16L
private const val UINT32_MASK = 0xFFFFFFFFL

/**
 * Writes [muxerFile] to [destination] with `moov` moved ahead of `mdat`, and deletes [muxerFile].
 *
 * When the conversion does not apply (the input is already fast-start) or fails with an
 * [IOException], [muxerFile] is moved to [destination] unchanged.
 *
 * @return true when [destination] is fast-start, by [isFastStart]'s definition.
 */
internal fun finishFastStart(
    muxerFile: File,
    destination: File,
): Boolean {
    // start truncates an existing destination, and deletes it again when it returns false or throws.
    val converted =
        try {
            StreamableVideo.start(muxerFile, destination)
        } catch (ioe: IOException) {
            Log.w(TAG, "Fast-start conversion of ${destination.path} failed: ${ioe.message}")
            false
        }
    if (converted) {
        muxerFile.delete()
    } else {
        moveReplacing(muxerFile, destination)
    }
    return isFastStart(destination)
}

/** Moves [source] to [destination], replacing it; copies when a rename is not possible (another file system). */
internal fun moveReplacing(
    source: File,
    destination: File,
) {
    if (destination.exists() && !destination.delete()) {
        Log.w(TAG, "Unable to delete existing destination file ${destination.absolutePath}")
    }
    if (!source.renameTo(destination)) {
        source.copyTo(destination, overwrite = true)
        source.delete()
    }
}

/**
 * True when [file]'s top-level `moov` atom precedes its first `mdat`. Reads atom headers only.
 * False when either atom is missing, an atom size is corrupt, or the file cannot be read.
 */
internal fun isFastStart(file: File): Boolean =
    try {
        RandomAccessFile(file, "r").use { moovPrecedesMdat(it) }
    } catch (_: IOException) {
        false
    }

private fun moovPrecedesMdat(raf: RandomAccessFile): Boolean {
    val length = raf.length()
    var sawMoov = false
    var offset = 0L
    while (true) {
        val atom = atomAt(raf, offset, length) ?: return false
        if (atom.type == MDAT_ATOM) return sawMoov
        if (atom.type == MOOV_ATOM) sawMoov = true
        offset += atom.size
    }
}

private class Atom(
    val type: Int,
    val size: Long,
)

/** The atom at [offset] in a file of [length] bytes, or null at the end of the file or when its size is corrupt. */
private fun atomAt(
    raf: RandomAccessFile,
    offset: Long,
    length: Long,
): Atom? {
    if (offset + ATOM_HEADER_SIZE > length) return null
    raf.seek(offset)
    var size = raf.readInt().toLong() and UINT32_MASK
    val type = raf.readInt()
    var headerSize = ATOM_HEADER_SIZE
    when (size) {
        0L -> size = length - offset
        1L -> {
            headerSize = LARGE_ATOM_HEADER_SIZE
            // A truncated 64-bit size reads as 0, which the check below rejects.
            size = if (offset + headerSize <= length) raf.readLong() else 0L
        }
    }
    // A corrupt size ends the walk instead of seeking outside the file.
    return if (size < headerSize || size > length - offset) null else Atom(type, size)
}
