package com.davotoula.lightcompressor

import com.davotoula.lightcompressor.data.MDAT_ATOM
import com.davotoula.lightcompressor.data.MOOV_ATOM
import java.io.File
import java.io.RandomAccessFile

private const val ATOM_HEADER_SIZE = 8L
private const val LARGE_ATOM_HEADER_SIZE = 16L
private const val UINT32_MASK = 0xFFFFFFFFL

/** True when the file's top-level `moov` atom precedes its first `mdat`, so playback can start before download ends. */
fun isFastStart(file: File): Boolean {
    var moovOffset = -1L
    var mdatOffset = -1L
    RandomAccessFile(file, "r").use { raf ->
        val length = raf.length()
        var offset = 0L
        while (offset + ATOM_HEADER_SIZE <= length && (moovOffset < 0 || mdatOffset < 0)) {
            raf.seek(offset)
            var size = raf.readInt().toLong() and UINT32_MASK
            val type = raf.readInt()
            var headerSize = ATOM_HEADER_SIZE
            when (size) {
                0L -> size = length - offset
                1L -> {
                    if (offset + LARGE_ATOM_HEADER_SIZE > length) break
                    size = raf.readLong()
                    headerSize = LARGE_ATOM_HEADER_SIZE
                }
            }
            // A corrupt size ends the walk instead of seeking outside the file.
            if (size < headerSize || size > length - offset) break
            if (type == MOOV_ATOM && moovOffset < 0) moovOffset = offset
            if (type == MDAT_ATOM && mdatOffset < 0) mdatOffset = offset
            offset += size
        }
    }
    return moovOffset >= 0 && mdatOffset >= 0 && moovOffset < mdatOffset
}
