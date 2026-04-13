package com.davotoula.lightcompressor.muxer

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Low-level ISO 14496-12 (ISOBMFF) box writer.
 *
 * Writes nested boxes to an [OutputStream]. Box sizes are patched after
 * the content is written using an internal [ByteArrayOutputStream] buffer.
 */
@Suppress("MagicNumber")
internal class BoxWriter(
    private val output: OutputStream,
) {
    /**
     * Scope object providing write methods inside a box body.
     * Delegates to a [ByteArrayOutputStream] buffer; the enclosing
     * [box]/[fullBox] call patches the size header after the lambda returns.
     */
    inner class BoxScope(
        private val buffer: ByteArrayOutputStream,
    ) {
        fun writeUInt8(value: Int) {
            buffer.write(value and 0xFF)
        }

        fun writeUInt16(value: Int) {
            buffer.write((value shr 8) and 0xFF)
            buffer.write(value and 0xFF)
        }

        fun writeUInt32(value: Long) {
            buffer.write(((value shr 24) and 0xFF).toInt())
            buffer.write(((value shr 16) and 0xFF).toInt())
            buffer.write(((value shr 8) and 0xFF).toInt())
            buffer.write((value and 0xFF).toInt())
        }

        fun writeUInt64(value: Long) {
            buffer.write(((value shr 56) and 0xFF).toInt())
            buffer.write(((value shr 48) and 0xFF).toInt())
            buffer.write(((value shr 40) and 0xFF).toInt())
            buffer.write(((value shr 32) and 0xFF).toInt())
            buffer.write(((value shr 24) and 0xFF).toInt())
            buffer.write(((value shr 16) and 0xFF).toInt())
            buffer.write(((value shr 8) and 0xFF).toInt())
            buffer.write((value and 0xFF).toInt())
        }

        fun writeBytes(data: ByteArray) {
            buffer.write(data)
        }

        fun writeFourCC(code: String) {
            require(code.length == 4) { "FourCC must be exactly 4 characters: $code" }
            buffer.write(code.toByteArray(Charsets.US_ASCII))
        }

        fun writeZeros(count: Int) {
            repeat(count) { buffer.write(0) }
        }

        /** Write a nested box. */
        fun box(
            type: String,
            body: BoxScope.() -> Unit,
        ) {
            this@BoxWriter.writeBox(type, null, null, buffer, body)
        }

        /** Write a nested full box (with version and flags). */
        fun fullBox(
            type: String,
            version: Int,
            flags: Int,
            body: BoxScope.() -> Unit,
        ) {
            this@BoxWriter.writeBox(type, version, flags, buffer, body)
        }
    }

    /** Write a box to the top-level output stream. */
    fun box(
        type: String,
        body: BoxScope.() -> Unit,
    ) {
        writeBox(type, null, null, output, body)
    }

    /** Write a full box to the top-level output stream. */
    fun fullBox(
        type: String,
        version: Int,
        flags: Int,
        body: BoxScope.() -> Unit,
    ) {
        writeBox(type, version, flags, output, body)
    }

    private fun writeBox(
        type: String,
        version: Int?,
        flags: Int?,
        target: OutputStream,
        body: BoxScope.() -> Unit,
    ) {
        require(type.length == 4) { "Box type must be exactly 4 characters: $type" }
        val bodyBuffer = ByteArrayOutputStream()

        if (version != null && flags != null) {
            bodyBuffer.write(version and 0xFF)
            bodyBuffer.write((flags shr 16) and 0xFF)
            bodyBuffer.write((flags shr 8) and 0xFF)
            bodyBuffer.write(flags and 0xFF)
        }

        BoxScope(bodyBuffer).body()

        val bodyBytes = bodyBuffer.toByteArray()
        val totalSize = HEADER_SIZE + bodyBytes.size

        // Write 4-byte size (big-endian)
        target.write((totalSize shr 24) and 0xFF)
        target.write((totalSize shr 16) and 0xFF)
        target.write((totalSize shr 8) and 0xFF)
        target.write(totalSize and 0xFF)

        // Write 4-byte type
        target.write(type.toByteArray(Charsets.US_ASCII))

        // Write body
        target.write(bodyBytes)
    }

    companion object {
        private const val HEADER_SIZE = 8
    }
}
