package com.davotoula.lightcompressor.sanitizer

import java.util.concurrent.TimeUnit

/**
 * The part of a source [Mp4Sanitizer.sanitize] keeps when trimming: [startMs] to [endMs] of the source's
 * presentation clock (the times a player shows, as `MediaExtractor` reports them), both inclusive. An [endMs]
 * past the source's end keeps everything from [startMs] on; `Long.MAX_VALUE` is fine.
 *
 * @throws IllegalArgumentException when [startMs] is negative or [endMs] is not after it.
 */
data class TrimRange(
    val startMs: Long,
    val endMs: Long,
) {
    init {
        require(startMs >= 0) { "startMs must not be negative: $startMs" }
        require(endMs > startMs) { "endMs ($endMs) must be after startMs ($startMs)" }
    }

    // TimeUnit saturates, so Long.MAX_VALUE as "to the end" doesn't overflow.
    internal val startUs: Long get() = TimeUnit.MILLISECONDS.toMicros(startMs)

    internal val endUs: Long get() = TimeUnit.MILLISECONDS.toMicros(endMs)
}
