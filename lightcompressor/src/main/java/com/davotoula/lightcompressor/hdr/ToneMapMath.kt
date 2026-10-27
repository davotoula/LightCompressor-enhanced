package com.davotoula.lightcompressor.hdr

import android.media.MediaFormat
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * Kotlin reference implementation of the HDR to SDR colour maths. Its constants are the single
 * source for the tone-mapping GLSL, which is built from them by string interpolation.
 * [toneMap] mirrors the shader and is used by unit tests only.
 */
@Suppress("MagicNumber")
internal object ToneMapMath {
    // HLG (BT.2100)
    const val HLG_A = 0.17883277
    const val HLG_B = 0.28466892
    const val HLG_C = 0.55991073
    const val HLG_PEAK_NITS = 1000.0
    const val HLG_GAMMA = 1.2

    // PQ (SMPTE ST 2084)
    const val PQ_M1 = 0.1593017578125
    const val PQ_M2 = 78.84375
    const val PQ_C1 = 0.8359375
    const val PQ_C2 = 18.8515625
    const val PQ_C3 = 18.6875
    const val PQ_MAX_NITS = 10000.0

    // BT.2020 luma coefficients
    const val KR_2020 = 0.2627
    const val KG_2020 = 0.6780
    const val KB_2020 = 0.0593

    const val REF_WHITE_NITS = 203.0
    const val DISPLAY_GAMMA = 2.4

    // Hable filmic curve
    const val HABLE_A = 0.15
    const val HABLE_B = 0.50
    const val HABLE_C = 0.10
    const val HABLE_D = 0.20
    const val HABLE_E = 0.02
    const val HABLE_F = 0.30

    // 10-bit limited-range code values
    const val Y_MIN = 64
    const val Y_MAX = 940
    const val C_MIN = 64
    const val C_MAX = 960
    const val CODE_MAX = 1023

    /**
     * Linear BT.2020 to BT.709, row-major (ITU-R BT.2087 values). They agree with the spec's
     * 4-decimal matrix to rounding, but each row sums to exactly 1 so greys stay neutral.
     */
    val BT2020_TO_BT709: DoubleArray =
        doubleArrayOf(
            1.660491,
            -0.587641,
            -0.072850,
            -0.124550,
            1.132900,
            -0.008349,
            -0.018151,
            -0.100579,
            1.118730,
        )

    private const val HLG_LOW_SEGMENT_END = 0.5
    private const val HLG_LOW_DIVISOR = 3.0
    private const val HLG_HIGH_DIVISOR = 12.0

    private const val MID_CHROMA = 0.5
    private const val TWO = 2.0

    fun hlgInverseOetf(e: Double): Double =
        if (e <= HLG_LOW_SEGMENT_END) {
            e * e / HLG_LOW_DIVISOR
        } else {
            (exp((e - HLG_C) / HLG_A) + HLG_B) / HLG_HIGH_DIVISOR
        }

    fun hlgToDisplayNits(rgb: DoubleArray): DoubleArray {
        val scene = DoubleArray(3) { hlgInverseOetf(rgb[it]) }
        val ys = KR_2020 * scene[0] + KG_2020 * scene[1] + KB_2020 * scene[2]
        val gain = HLG_PEAK_NITS * ys.pow(HLG_GAMMA - 1.0)
        return DoubleArray(3) { gain * scene[it] }
    }

    fun pqEotfNits(e: Double): Double {
        val p = e.coerceIn(0.0, 1.0).pow(1.0 / PQ_M2)
        val num = max(p - PQ_C1, 0.0)
        val den = PQ_C2 - PQ_C3 * p
        return PQ_MAX_NITS * (num / den).pow(1.0 / PQ_M1)
    }

    fun bt2020ToBt709(rgb: DoubleArray): DoubleArray =
        DoubleArray(3) { row ->
            val m = BT2020_TO_BT709
            max(m[row * 3] * rgb[0] + m[row * 3 + 1] * rgb[1] + m[row * 3 + 2] * rgb[2], 0.0)
        }

    fun hable(x: Double): Double =
        (x * (HABLE_A * x + HABLE_C * HABLE_B) + HABLE_D * HABLE_E) /
            (x * (HABLE_A * x + HABLE_B) + HABLE_D * HABLE_F) - HABLE_E / HABLE_F

    fun toneCurve(
        m: Double,
        lw: Double,
    ): Double = hable(TWO * m) / hable(TWO * lw)

    fun lwFor(peakNits: Double?): Double = max((peakNits ?: HLG_PEAK_NITS) / REF_WHITE_NITS, 1.0)

    /** Inputs are normalised [0,1] code values; returns non-linear BT.2020 R'G'B'. */
    fun yuvToRgb2020(
        y: Double,
        cb: Double,
        cr: Double,
        limited: Boolean,
    ): DoubleArray {
        val yn: Double
        val cbn: Double
        val crn: Double
        if (limited) {
            val cSpan = (C_MAX - C_MIN).toDouble()
            val cMid = (C_MIN + C_MAX) / TWO
            yn = (y * CODE_MAX - Y_MIN) / (Y_MAX - Y_MIN)
            cbn = (cb * CODE_MAX - cMid) / cSpan
            crn = (cr * CODE_MAX - cMid) / cSpan
        } else {
            yn = y
            cbn = cb - MID_CHROMA
            crn = cr - MID_CHROMA
        }
        val rCr = TWO * (1.0 - KR_2020)
        val bCb = TWO * (1.0 - KB_2020)
        val gCb = KB_2020 / KG_2020 * bCb
        val gCr = KR_2020 / KG_2020 * rCr
        return doubleArrayOf(yn + rCr * crn, yn - gCb * cbn - gCr * crn, yn + bCb * cbn)
    }

    /** Full tone-map pipeline (spec section 3, steps 1 to 5); output is BT.1886-encoded. */
    fun toneMap(
        rgb: DoubleArray,
        transfer: Int,
        lw: Double,
    ): DoubleArray {
        val nits =
            if (transfer == MediaFormat.COLOR_TRANSFER_HLG) {
                hlgToDisplayNits(rgb)
            } else {
                DoubleArray(3) { pqEotfNits(rgb[it]) }
            }
        val linear2020 = DoubleArray(3) { nits[it] / REF_WHITE_NITS }
        val linear709 = bt2020ToBt709(linear2020)
        val m = max(linear709[0], max(linear709[1], linear709[2]))
        val scale = if (m > 0.0) toneCurve(m, lw) / m else 0.0
        return DoubleArray(3) { (linear709[it] * scale).coerceIn(0.0, 1.0).pow(1.0 / DISPLAY_GAMMA) }
    }
}
