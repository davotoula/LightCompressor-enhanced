package com.davotoula.lightcompressor

import java.io.File
import java.nio.ByteBuffer

/** A top-level MP4 atom: 32-bit size, four-character [type], then [payload]. */
internal fun atom(
    type: String,
    payload: ByteArray,
): ByteArray =
    ByteBuffer
        .allocate(8 + payload.size)
        .putInt(8 + payload.size)
        .put(type.toByteArray())
        .put(payload)
        .array()

/** An `stco` atom with a single chunk offset. */
internal fun stco(chunkOffset: Int): ByteArray =
    ByteBuffer
        .allocate(20)
        .putInt(20)
        .put("stco".toByteArray())
        .putInt(0)
        .putInt(1)
        .putInt(chunkOffset)
        .array()

/** The four-character types of [file]'s top-level atoms, in file order. */
internal fun topLevelAtomTypes(file: File): List<String> {
    val raw = file.readBytes()
    val bytes = ByteBuffer.wrap(raw)
    return generateSequence(0) { it + bytes.getInt(it) }
        .takeWhile { it < raw.size }
        .map { String(raw, it + 4, 4, Charsets.US_ASCII) }
        .toList()
}
