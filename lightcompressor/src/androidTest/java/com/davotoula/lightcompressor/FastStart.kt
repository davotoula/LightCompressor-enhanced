package com.davotoula.lightcompressor

import java.io.File
import java.io.RandomAccessFile

private const val ATOM_HEADER_SIZE = 8L
private const val ATOM_TYPE_SIZE = 4
private const val UINT32_MASK = 0xFFFFFFFFL

/** True when the file's top-level `moov` atom precedes its first `mdat`, so playback can start before download ends. */
fun isFastStart(file: File): Boolean {
    var moovOffset = -1L
    var mdatOffset = -1L
    RandomAccessFile(file, "r").use { raf ->
        var offset = 0L
        while (offset + ATOM_HEADER_SIZE <= raf.length() && (moovOffset < 0 || mdatOffset < 0)) {
            raf.seek(offset)
            var size = raf.readInt().toLong() and UINT32_MASK
            val type = ByteArray(ATOM_TYPE_SIZE).also { raf.readFully(it) }.toString(Charsets.US_ASCII)
            when (size) {
                0L -> size = raf.length() - offset
                1L -> size = raf.readLong()
            }
            if (size < ATOM_HEADER_SIZE) break
            if (type == "moov" && moovOffset < 0) moovOffset = offset
            if (type == "mdat" && mdatOffset < 0) mdatOffset = offset
            offset += size
        }
    }
    return moovOffset >= 0 && mdatOffset >= 0 && moovOffset < mdatOffset
}
