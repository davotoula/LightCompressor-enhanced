package com.davotoula.lightcompressor.compressor

import android.media.MediaCodec
import android.util.Log
import java.io.Closeable
import java.util.concurrent.TimeUnit

/**
 * Unblocks a pipeline whose video encoder has stopped producing output.
 *
 * Some vendor encoders (seen on Exynos HEVC under concurrent hardware codec load) occasionally keep
 * their queued input frames and never emit output again, not even when asked for a sync frame. The
 * pipeline thread then blocks inside GL waiting for a free encoder input buffer, and neither a
 * cancel nor anything on that thread can wake it. Releasing the encoder from another thread does.
 *
 * The pipeline reports [frameSubmitted], [inputEnded] and [outputReceived]. A background thread
 * calls [check]: it trips [Verdict.STALLED] once frames are awaiting output and the encoder has made
 * no progress for [stallTimeoutNs], or [Verdict.CANCELLED] once [isCancelled] has held and the
 * encoder has made no progress for [cancelGraceNs] (a pipeline that isn't stuck notices the cancel
 * itself well within that, and one still draining output must not lose its buffers mid-write). On a
 * trip it calls [onTrip], which releases the encoder, so the pipeline thread's next codec or GL call
 * fails and the caller maps [verdict] to the job's outcome. The encoder's end-of-stream output retires
 * the watchdog, so work after encoding (audio copy, segment flush) is never judged.
 */
internal class EncoderWatchdog(
    private val isCancelled: () -> Boolean,
    private val stallTimeoutNs: Long = STALL_TIMEOUT_NS,
    private val cancelGraceNs: Long = CANCEL_GRACE_NS,
    private val clock: () -> Long = System::nanoTime,
    private val onTrip: (Verdict) -> Unit,
) : Closeable {
    enum class Verdict { STALLED, CANCELLED }

    /** Why the watchdog tripped, or null. Set before [onTrip] runs. */
    @Volatile
    var verdict: Verdict? = null
        private set

    private val lock = Any()
    private var framesAwaitingOutput = 0
    private var inputHasEnded = false
    private var lastProgressNs = clock()
    private var cancelSeenNs: Long? = null
    private var closed = false
    private var thread: Thread? = null

    /** Starts polling [check] on a daemon thread. */
    fun start(): EncoderWatchdog {
        thread =
            Thread({
                try {
                    while (check() == null) Thread.sleep(POLL_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    // close() stops the poller.
                }
            }, "EncoderWatchdog").apply {
                isDaemon = true
                start()
            }
        return this
    }

    /** A frame was rendered to the encoder's input surface. */
    fun frameSubmitted() =
        synchronized(lock) {
            // The clock starts when the encoder is handed work, not at its last output.
            if (!isAwaitingOutput()) lastProgressNs = clock()
            framesAwaitingOutput++
        }

    /** End of stream was signalled to the encoder; it still owes its final output. */
    fun inputEnded() =
        synchronized(lock) {
            if (!isAwaitingOutput()) lastProgressNs = clock()
            inputHasEnded = true
        }

    /** The encoder reported a new output format. */
    fun formatChanged() = synchronized(lock) { lastProgressNs = clock() }

    /** The encoder emitted an output buffer with these [flags] and [size]. */
    fun outputReceived(
        flags: Int,
        size: Int,
    ) = synchronized(lock) {
        lastProgressNs = clock()
        val isFrame = size > 0 && flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
        if (isFrame && framesAwaitingOutput > 0) framesAwaitingOutput--
        // The encoder has finished; nothing after this is its to stall.
        if (flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) closed = true
    }

    /** Evaluates the trip conditions once. Returns the verdict only from the call that trips. */
    fun check(): Verdict? {
        val tripped = synchronized(lock) { if (closed) null else evaluate(clock()) }
        // Outside the lock: the pipeline thread may still be reporting while the encoder is released.
        tripped?.let(onTrip)
        return tripped
    }

    private fun evaluate(now: Long): Verdict? {
        val newVerdict =
            when {
                cancelExpired(now) && now - lastProgressNs >= cancelGraceNs -> Verdict.CANCELLED
                isAwaitingOutput() && now - lastProgressNs >= stallTimeoutNs -> Verdict.STALLED
                else -> null
            }
        if (newVerdict != null) {
            verdict = newVerdict
            closed = true
        }
        return newVerdict
    }

    /**
     * Stops the watchdog. Joins the poller, so a trip in progress has finished releasing the encoder
     * before the caller's own cleanup touches it.
     */
    override fun close() {
        synchronized(lock) { closed = true }
        val poller = thread ?: return
        if (poller !== Thread.currentThread()) {
            poller.interrupt()
            try {
                poller.join(JOIN_TIMEOUT_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun isAwaitingOutput() = framesAwaitingOutput > 0 || inputHasEnded

    private fun cancelExpired(now: Long): Boolean {
        if (!isCancelled()) {
            cancelSeenNs = null
            return false
        }
        val seen = cancelSeenNs ?: now.also { cancelSeenNs = it }
        return now - seen >= cancelGraceNs
    }

    internal companion object {
        /** Starts a watchdog that releases [encoder] when it trips. */
        fun startFor(
            encoder: MediaCodec,
            tag: String,
            isCancelled: () -> Boolean,
        ): EncoderWatchdog =
            EncoderWatchdog(isCancelled) { verdict ->
                Log.w(tag, "Encoder watchdog tripped ($verdict), releasing the encoder")
                runCatching { encoder.release() }
                    .onFailure { Log.w(tag, "Watchdog encoder release failed: ${it.message}") }
            }.start()

        /** Normal gaps between encoder outputs are milliseconds, even under heavy codec load. */
        val STALL_TIMEOUT_NS = TimeUnit.SECONDS.toNanos(10)
        val CANCEL_GRACE_NS = TimeUnit.SECONDS.toNanos(1)
        private const val POLL_INTERVAL_MS = 250L
        private const val JOIN_TIMEOUT_MS = 5_000L
    }
}
