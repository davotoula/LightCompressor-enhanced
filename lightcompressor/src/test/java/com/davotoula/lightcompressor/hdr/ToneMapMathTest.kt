package com.davotoula.lightcompressor.hdr

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToneMapMathTest {
    private val lwHlg = 1000 / 203.0

    @Test
    fun hlg075_isReferenceWhite() =
        assertEquals(203.0, ToneMapMath.hlgToDisplayNits(doubleArrayOf(.75, .75, .75))[1], 1.0)

    @Test
    fun pq_0_5807_isReferenceWhite() = assertEquals(203.0, ToneMapMath.pqEotfNits(0.5807), 1.0)

    @Test
    fun pq_1_isMax() = assertEquals(10000.0, ToneMapMath.pqEotfNits(1.0), 0.5)

    @Test
    fun hable_zeroIsZero() = assertEquals(0.0, ToneMapMath.hable(0.0), 1e-9)

    @Test
    fun toneCurve_peakMapsToOne() = assertEquals(1.0, ToneMapMath.toneCurve(lwHlg, lwHlg), 1e-9)

    @Test
    fun toneCurve_isMonotonic() {
        var prev = ToneMapMath.toneCurve(0.0, lwHlg)
        var m = 0.01
        while (m <= lwHlg) {
            val v = ToneMapMath.toneCurve(m, lwHlg)
            assertTrue("not monotonic at m=$m", v >= prev)
            prev = v
            m += 0.01
        }
    }

    @Test
    fun toneCurve_referenceWhite_matchesSpecTable() = assertEquals(0.508, ToneMapMath.toneCurve(1.0, lwHlg), 0.002)

    @Test
    fun highlights_spreadAcrossCodes() {
        fun y8(m: Double) = 16 + 219 * Math.pow(ToneMapMath.toneCurve(m, lwHlg), 1 / 2.4)
        assertTrue(y8(lwHlg) - y8(2.0) >= 10)
    }

    @Test
    fun gamut_whiteStaysWhite() {
        val out = ToneMapMath.bt2020ToBt709(doubleArrayOf(1.0, 1.0, 1.0))
        out.forEach { assertEquals(1.0, it, 1e-3) }
    }

    @Test
    fun lwFor_defaultsAndFloors() {
        assertEquals(1000 / 203.0, ToneMapMath.lwFor(null), 1e-9)
        assertEquals(1.0, ToneMapMath.lwFor(100.0), 1e-9)
    }

    @Test
    fun yuv_limitedBlackAndWhite() {
        val black = ToneMapMath.yuvToRgb2020(64 / 1023.0, 512 / 1023.0, 512 / 1023.0, limited = true)
        val white = ToneMapMath.yuvToRgb2020(940 / 1023.0, 512 / 1023.0, 512 / 1023.0, limited = true)
        black.forEach { assertEquals(0.0, it, 1e-3) }
        white.forEach { assertEquals(1.0, it, 1e-3) }
    }

    @Test
    fun toneMap_hlgReferenceWhiteGrey_isNeutral() {
        val out = ToneMapMath.toneMap(doubleArrayOf(.75, .75, .75), MediaFormat.COLOR_TRANSFER_HLG, lwHlg)
        assertEquals(out[0], out[1], 1e-6)
        assertEquals(out[1], out[2], 1e-6)
    }
}
