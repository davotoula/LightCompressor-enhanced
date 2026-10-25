package com.davotoula.lightcompressor.hdr

import android.media.MediaCodecInfo
import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class KeepHdrRejectionTest {
    private val keep: ColorPlan =
        ColorPlan.KeepHdr(
            MediaFormat.COLOR_TRANSFER_HLG,
            MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
            "c2.test",
            null,
            null,
        )
    private val toneMap: ColorPlan = ColorPlan.ToneMapToSdr(MediaFormat.COLOR_TRANSFER_HLG, null, 4.93f)

    @Test
    fun rejection_retriesOnceWithFallback() {
        val attempts = mutableListOf<ColorPlan>()
        var rejected = 0
        val result =
            runWithFallback(keep, { toneMap }, { rejected++ }) { plan ->
                attempts += plan
                if (plan === keep) throw KeepHdrRejected("no")
                "ok"
            }
        assertEquals("ok", result)
        assertEquals(listOf(keep, toneMap), attempts)
        assertEquals(1, rejected)
    }

    @Test
    fun secondRejection_propagates() {
        var attempts = 0
        val second = KeepHdrRejected("again")
        try {
            runWithFallback(keep, { toneMap }, {}) {
                attempts++
                if (attempts == 1) throw KeepHdrRejected("first") else throw second
            }
            fail("expected throw")
        } catch (e: KeepHdrRejected) {
            assertSame(second, e)
        }
        assertEquals(2, attempts)
    }

    @Test
    fun otherException_isNotRetried() {
        var attempts = 0
        try {
            runWithFallback(keep, { toneMap }, {}) {
                attempts++
                error("boom")
            }
            fail("expected throw")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun nonKeepPlan_isNeverRetried() {
        var attempts = 0
        try {
            runWithFallback(toneMap, { toneMap }, {}) {
                attempts++
                throw KeepHdrRejected("x")
            }
            fail("expected throw")
        } catch (_: KeepHdrRejected) {
            // expected
        }
        assertEquals(1, attempts)
    }

    @Test
    fun afterMuxerStart_isNotARejection() {
        assertNull(rejectionFor(keep, muxerStarted = true, isCancellation = false, error = RuntimeException("x")))
    }

    @Test
    fun cancellation_isNeverARejection() {
        assertNull(rejectionFor(keep, muxerStarted = false, isCancellation = true, error = RuntimeException("x")))
    }

    @Test
    fun codecErrorBeforeMuxer_isARejection() {
        val error = RuntimeException("x")
        val rejection = rejectionFor(keep, muxerStarted = false, isCancellation = false, error = error)
        assertNotNull(rejection)
        assertSame(error, rejection!!.cause)
    }
}
