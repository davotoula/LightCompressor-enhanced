package com.abedelazizshe.lightcompressorlibrary.utils

import android.util.Log
import com.abedelazizshe.lightcompressorlibrary.data.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

object StreamableVideo {

    private const val TAG = "StreamableVideo"
    private const val ATOM_PREAMBLE_SIZE = 8

    /**
     * @param in  Input file.
     * @param out Output file.
     * @return false if input file is already fast start.
     * @throws IOException
     */
    @Throws(IOException::class)
    fun start(`in`: File?, out: File): Boolean {
        var ret = false
        var inStream: FileInputStream? = null
        var outStream: FileOutputStream? = null
        return try {
            inStream = FileInputStream(`in`)
            val infile = inStream.channel
            outStream = FileOutputStream(out)
            val outfile = outStream.channel
            convert(infile, outfile).also { ret = it }
        } finally {
            safeClose(inStream)
            safeClose(outStream)
            if (!ret) {
                out.delete()
            }
        }
    }

    @Throws(IOException::class)
    private fun convert(infile: FileChannel, outfile: FileChannel): Boolean {
        val atomBytes = ByteBuffer.allocate(ATOM_PREAMBLE_SIZE).order(ByteOrder.BIG_ENDIAN)
        var atomType: Int
        var atomSize: Long
        var atomOffset: Long
        var moovOffset: Long = -1
        var moovSize: Long = -1
        var firstMdatOffset: Long = Long.MAX_VALUE
        var ftypAtom: ByteBuffer? = null
        var startOffset: Long = 0

        infile.position(0)
        while (true) {
            atomOffset = infile.position()
            if (!readAndFill(infile, atomBytes)) {
                break
            }
            atomSize = uInt32ToLong(atomBytes.int)
            atomType = atomBytes.int

            var headerSize = ATOM_PREAMBLE_SIZE.toLong()
            if (atomSize == 1L) {
                // 64-bit extended size
                atomBytes.clear()
                if (!readAndFill(infile, atomBytes)) {
                    break
                }
                atomSize = uInt64ToLong(atomBytes.long)
                headerSize += ATOM_PREAMBLE_SIZE
            } else if (atomSize == 0L) {
                atomSize = infile.size() - atomOffset
            }

            if (atomSize < headerSize) {
                Log.wtf(TAG, "invalid atom size for type=$atomType")
                return false
            }

            when (atomType) {
                FTYP_ATOM -> {
                    val ftypSize = uInt32ToInt(atomSize)
                    val buffer = ByteBuffer.allocate(ftypSize).order(ByteOrder.BIG_ENDIAN)
                    infile.position(atomOffset)
                    if (infile.read(buffer) != ftypSize) {
                        throw IOException("failed to read ftyp atom")
                    }
                    buffer.flip()
                    ftypAtom = buffer
                    startOffset = atomOffset + atomSize
                }

                MOOV_ATOM -> {
                    moovOffset = atomOffset
                    moovSize = atomSize
                }

                MDAT_ATOM -> {
                    if (firstMdatOffset == Long.MAX_VALUE) {
                        firstMdatOffset = atomOffset
                    }
                }
            }

            infile.position(atomOffset + atomSize)
        }

        if (moovOffset < 0 || moovSize <= 0) {
            Log.wtf(TAG, "moov atom not found")
            return false
        }

        if (firstMdatOffset != Long.MAX_VALUE && moovOffset < firstMdatOffset) {
            Log.i(TAG, "moov atom already precedes mdat; skipping fast-start conversion")
            return false
        }

        val moovAtomSizeInt = uInt32ToInt(moovSize)
        val moovAtomSizeLong = moovAtomSizeInt.toLong()
        val moovAtom = ByteBuffer.allocate(moovAtomSizeInt).order(ByteOrder.BIG_ENDIAN)
        if (!readAndFill(infile, moovAtom, moovOffset)) {
            throw Exception("failed to read moov atom")
        }

        if (moovAtom.getInt(12) == CMOV_ATOM) {
            throw Exception("this utility does not support compressed moov atoms yet")
        }

        while (moovAtom.remaining() >= 8) {
            val atomHead = moovAtom.position()
            atomType = moovAtom.getInt(atomHead + 4)
            if (atomType != STCO_ATOM && atomType != CO64_ATOM) {
                moovAtom.position(moovAtom.position() + 1)
                continue
            }
            atomSize = uInt32ToLong(moovAtom.getInt(atomHead))
            if (atomSize > moovAtom.remaining()) {
                throw Exception("bad atom size")
            }
            moovAtom.position(atomHead + 12)
            if (moovAtom.remaining() < 4) {
                throw Exception("malformed atom")
            }
            val offsetCount = uInt32ToInt(moovAtom.int)
            if (atomType == STCO_ATOM) {
                Log.i(TAG, "patching stco atom...")
                if (moovAtom.remaining() < offsetCount * 4) {
                    throw Exception("bad atom size/element count")
                }
                for (i in 0 until offsetCount) {
                    val entryPosition = moovAtom.position()
                    val currentOffset = moovAtom.getInt(entryPosition)
                    val currentOffsetUnsigned = currentOffset.toLong() and 0xFFFFFFFFL
                    val needsShift = currentOffsetUnsigned < moovOffset
                    val updatedOffset = currentOffsetUnsigned + if (needsShift) moovAtomSizeLong else 0L
                    if (updatedOffset > 0xFFFFFFFFL) {
                        throw Exception(
                            "This is bug in original qt-faststart.c: stco atom should be extended to co64 atom as new offset value exceeds uint32, but is not implemented."
                        )
                    }
                    moovAtom.putInt(updatedOffset.toInt())
                }
            } else if (atomType == CO64_ATOM) {
                Log.wtf(TAG, "patching co64 atom...")
                if (moovAtom.remaining() < offsetCount * 8) {
                    throw Exception("bad atom size/element count")
                }
                for (i in 0 until offsetCount) {
                    val currentOffset = moovAtom.getLong(moovAtom.position())
                    val needsShift = currentOffset < moovOffset
                    val updatedOffset = currentOffset + if (needsShift) moovAtomSizeLong else 0L
                    moovAtom.putLong(updatedOffset)
                }
            }
        }

        val prefixStart = if (ftypAtom != null) startOffset else 0L
        val prefixLength = moovOffset - prefixStart
        if (prefixLength < 0) {
            Log.i(TAG, "moov atom already at the front; skipping fast-start conversion")
            return false
        }

        ftypAtom?.let {
            Log.i(TAG, "writing ftyp atom...")
            it.rewind()
            outfile.write(it)
        }

        Log.i(TAG, "writing moov atom...")
        moovAtom.rewind()
        outfile.write(moovAtom)

        if (prefixLength > 0) {
            Log.i(TAG, "copying atoms before original moov...")
            infile.transferTo(prefixStart, prefixLength, outfile)
        }

        val suffixStart = moovOffset + moovSize
        val suffixLength = infile.size() - suffixStart
        if (suffixLength > 0) {
            Log.i(TAG, "copying atoms after original moov...")
            infile.transferTo(suffixStart, suffixLength, outfile)
        }

        return true
    }

    private fun safeClose(closeable: Closeable?) {
        if (closeable != null) {
            try {
                closeable.close()
            } catch (e: IOException) {
                Log.wtf(TAG, "Failed to close file: ")
            }
        }
    }

    @Throws(IOException::class)
    private fun readAndFill(infile: FileChannel, buffer: ByteBuffer): Boolean {
        buffer.clear()
        val size = infile.read(buffer)
        buffer.flip()
        return size == buffer.capacity()
    }

    @Throws(IOException::class)
    private fun readAndFill(infile: FileChannel, buffer: ByteBuffer, position: Long): Boolean {
        buffer.clear()
        val size = infile.read(buffer, position)
        buffer.flip()
        return size == buffer.capacity()
    }
}
