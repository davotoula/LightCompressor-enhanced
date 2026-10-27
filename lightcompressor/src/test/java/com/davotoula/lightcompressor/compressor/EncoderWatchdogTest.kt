package com.davotoula.lightcompressor.compressor

import android.media.MediaCodec
import com.davotoula.lightcompressor.compressor.EncoderWatchdog.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EncoderWatchdogTest {
    private var nowNs = 0L
    private var cancelled = false
    private val trips = mutableListOf<Verdict>()

    private fun watchdog() =
        EncoderWatchdog(
            isCancelled = { cancelled },
            stallTimeoutNs = TIMEOUT,
            cancelGraceNs = GRACE,
            clock = { nowNs },
            onTrip = { trips += it },
        )

    private fun advance(ns: Long) {
        nowNs += ns
    }

    @Test
    fun idleBeforeFirstFrame_neverTrips() {
        val w = watchdog()
        advance(TIMEOUT * 10)
        assertNull(w.check())
        assertTrue(trips.isEmpty())
    }

    @Test
    fun noOutputWhileFramesPending_tripsStalledOnce() {
        val w = watchdog()
        repeat(8) { w.frameSubmitted() }
        advance(TIMEOUT - 1)
        assertNull(w.check())
        advance(1)
        assertEquals(Verdict.STALLED, w.check())
        assertEquals(Verdict.STALLED, w.verdict)
        advance(TIMEOUT)
        w.check()
        assertEquals(listOf(Verdict.STALLED), trips)
    }

    @Test
    fun slowButProgressing_neverTrips() {
        val w = watchdog()
        repeat(20) {
            w.frameSubmitted()
            w.frameSubmitted()
            advance(TIMEOUT - 1)
            w.outputReceived(flags = 0, size = FRAME_SIZE)
            assertNull(w.check())
        }
        assertTrue(trips.isEmpty())
    }

    @Test
    fun codecConfigOutput_countsAsProgressButLeavesFramesPending() {
        val w = watchdog()
        w.frameSubmitted()
        advance(TIMEOUT - 1)
        w.outputReceived(MediaCodec.BUFFER_FLAG_CODEC_CONFIG, CONFIG_SIZE)
        advance(TIMEOUT - 1)
        assertNull(w.check())
        advance(1)
        assertEquals(Verdict.STALLED, w.check())
    }

    @Test
    fun drainedEncoder_waitingOnDecoder_doesNotTrip() {
        val w = watchdog()
        w.frameSubmitted()
        w.outputReceived(flags = 0, size = FRAME_SIZE)
        advance(TIMEOUT * 3)
        assertNull(w.check())
    }

    @Test
    fun frameAfterLongIdle_startsTimerAtSubmission() {
        val w = watchdog()
        w.frameSubmitted()
        w.outputReceived(flags = 0, size = FRAME_SIZE)
        advance(TIMEOUT * 3)
        w.frameSubmitted()
        advance(TIMEOUT - 1)
        assertNull(w.check())
        advance(1)
        assertEquals(Verdict.STALLED, w.check())
    }

    @Test
    fun noFinalOutputAfterEndOfStream_tripsStalled() {
        val w = watchdog()
        w.frameSubmitted()
        w.outputReceived(flags = 0, size = FRAME_SIZE)
        advance(TIMEOUT * 2)
        w.inputEnded()
        advance(TIMEOUT - 1)
        assertNull(w.check())
        advance(1)
        assertEquals(Verdict.STALLED, w.check())
    }

    @Test
    fun formatChange_countsAsProgress() {
        val w = watchdog()
        w.frameSubmitted()
        advance(TIMEOUT - 1)
        w.formatChanged()
        advance(TIMEOUT - 1)
        assertNull(w.check())
    }

    @Test
    fun endOfStreamOutput_retiresWatchdog() {
        val w = watchdog()
        w.frameSubmitted()
        w.inputEnded()
        w.outputReceived(MediaCodec.BUFFER_FLAG_END_OF_STREAM, 0)
        cancelled = true
        advance(TIMEOUT * 2)
        assertNull(w.check())
        assertTrue(trips.isEmpty())
    }

    @Test
    fun cancel_tripsCancelledAfterGrace() {
        val w = watchdog()
        w.frameSubmitted()
        cancelled = true
        assertNull(w.check())
        advance(GRACE - 1)
        assertNull(w.check())
        advance(1)
        assertEquals(Verdict.CANCELLED, w.check())
        assertEquals(listOf(Verdict.CANCELLED), trips)
    }

    @Test
    fun cancelDuringStall_reportsCancelled() {
        val w = watchdog()
        w.frameSubmitted()
        advance(TIMEOUT - GRACE)
        cancelled = true
        assertNull(w.check())
        advance(GRACE)
        assertEquals(Verdict.CANCELLED, w.check())
    }

    @Test
    fun closed_neverTrips() {
        val w = watchdog()
        w.frameSubmitted()
        w.close()
        advance(TIMEOUT * 2)
        assertNull(w.check())
        assertTrue(trips.isEmpty())
    }

    @Test
    fun poller_tripsOnRealClock() {
        val tripped = CountDownLatch(1)
        val w =
            EncoderWatchdog(
                isCancelled = { false },
                stallTimeoutNs = TimeUnit.MILLISECONDS.toNanos(50),
                onTrip = { tripped.countDown() },
            ).start()
        w.frameSubmitted()
        assertTrue(tripped.await(5, TimeUnit.SECONDS))
        w.close()
        assertEquals(Verdict.STALLED, w.verdict)
    }

    private companion object {
        const val TIMEOUT = 10_000_000_000L
        const val GRACE = 1_000_000_000L
        const val FRAME_SIZE = 4096
        const val CONFIG_SIZE = 32
    }
}
