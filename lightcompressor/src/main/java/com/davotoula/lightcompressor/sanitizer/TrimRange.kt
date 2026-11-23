package com.davotoula.lightcompressor.sanitizer

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

    internal val startUs: Long get() = startMs.toUs()

    internal val endUs: Long get() = endMs.toUs()

    // Saturates, so Long.MAX_VALUE as "to the end" doesn't overflow.
    private fun Long.toUs(): Long = coerceAtMost(Long.MAX_VALUE / US_PER_MS) * US_PER_MS
}

// Top level, not in a private companion: a companion const compiles to a public static field of TrimRange.
private const val US_PER_MS = 1000L
