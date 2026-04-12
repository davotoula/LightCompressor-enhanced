package com.davotoula.lightcompressor.utils

private const val CODEC_ALIGNMENT = 16

fun uInt32ToLong(int32: Int): Long = int32.toLong()

@Suppress("TooGenericExceptionThrown")
fun uInt32ToInt(uInt32: Long): Int {
    if (uInt32 > Int.MAX_VALUE || uInt32 < 0) {
        throw Exception("uInt32 value is too large")
    }
    return uInt32.toInt()
}

@Suppress("TooGenericExceptionThrown")
fun uInt64ToLong(uInt64: Long): Long {
    if (uInt64 < 0) throw Exception("uInt64 value is too large")
    return uInt64
}

@Suppress("TooGenericExceptionThrown")
fun uInt32ToInt(uInt32: Int): Int {
    if (uInt32 < 0) {
        throw Exception("uInt32 value is too large")
    }
    return uInt32
}

fun roundDimension(value: Double): Int = value.toInt() and 1.inv()

/**
 * Returns 16-aligned fallback dimensions, or null if already 16-aligned
 * or if the fallback would produce zero dimensions.
 */
@Suppress("ReturnCount")
fun fallbackTo16Aligned(
    width: Int,
    height: Int,
): Pair<Int, Int>? {
    val w16 = width / CODEC_ALIGNMENT * CODEC_ALIGNMENT
    val h16 = height / CODEC_ALIGNMENT * CODEC_ALIGNMENT
    if (w16 == width && h16 == height) return null
    if (w16 == 0 || h16 == 0) return null
    return Pair(w16, h16)
}
