@file:Suppress("MatchingDeclarationName") // file also holds the rejection helpers, named for the feature

package com.davotoula.lightcompressor.hdr

/** Thrown when a keep-10-bit-HDR attempt cannot proceed and the job should retry as tone-mapped SDR. */
internal class KeepHdrRejected(
    reason: String,
    cause: Throwable? = null,
) : RuntimeException(reason, cause)

/** Non-null when [error] during a KeepHdr attempt should trigger the tone-map retry. */
internal fun rejectionFor(
    plan: ColorPlan,
    muxerStarted: Boolean,
    isCancellation: Boolean,
    error: Throwable,
): KeepHdrRejected? =
    when {
        plan !is ColorPlan.KeepHdr || muxerStarted || isCancellation -> null
        error is KeepHdrRejected -> error
        else ->
            KeepHdrRejected(
                "keep-HDR attempt failed before muxer start: ${error.javaClass.simpleName}: ${error.message}",
                error,
            )
    }

/**
 * Runs [attempt] with [plan]. If a KeepHdr attempt throws [KeepHdrRejected], calls [onRejected] and
 * retries exactly once with `fallback()`. Other exceptions propagate unchanged.
 */
internal fun <T> runWithFallback(
    plan: ColorPlan,
    fallback: () -> ColorPlan,
    onRejected: (KeepHdrRejected) -> Unit,
    attempt: (ColorPlan) -> T,
): T =
    try {
        attempt(plan)
    } catch (rejected: KeepHdrRejected) {
        if (plan !is ColorPlan.KeepHdr) throw rejected
        onRejected(rejected)
        attempt(fallback())
    }
