package com.davotoula.lightcompressor.hdr

import com.davotoula.lightcompressor.hdr.ToneMapMath.BT2020_TO_BT709
import com.davotoula.lightcompressor.hdr.ToneMapMath.CODE_MAX
import com.davotoula.lightcompressor.hdr.ToneMapMath.C_MAX
import com.davotoula.lightcompressor.hdr.ToneMapMath.C_MIN
import com.davotoula.lightcompressor.hdr.ToneMapMath.DISPLAY_GAMMA
import com.davotoula.lightcompressor.hdr.ToneMapMath.HABLE_A
import com.davotoula.lightcompressor.hdr.ToneMapMath.HABLE_B
import com.davotoula.lightcompressor.hdr.ToneMapMath.HABLE_C
import com.davotoula.lightcompressor.hdr.ToneMapMath.HABLE_D
import com.davotoula.lightcompressor.hdr.ToneMapMath.HABLE_E
import com.davotoula.lightcompressor.hdr.ToneMapMath.HABLE_F
import com.davotoula.lightcompressor.hdr.ToneMapMath.HLG_A
import com.davotoula.lightcompressor.hdr.ToneMapMath.HLG_B
import com.davotoula.lightcompressor.hdr.ToneMapMath.HLG_C
import com.davotoula.lightcompressor.hdr.ToneMapMath.HLG_GAMMA
import com.davotoula.lightcompressor.hdr.ToneMapMath.HLG_PEAK_NITS
import com.davotoula.lightcompressor.hdr.ToneMapMath.KB_2020
import com.davotoula.lightcompressor.hdr.ToneMapMath.KG_2020
import com.davotoula.lightcompressor.hdr.ToneMapMath.KR_2020
import com.davotoula.lightcompressor.hdr.ToneMapMath.PQ_C1
import com.davotoula.lightcompressor.hdr.ToneMapMath.PQ_C2
import com.davotoula.lightcompressor.hdr.ToneMapMath.PQ_C3
import com.davotoula.lightcompressor.hdr.ToneMapMath.PQ_M1
import com.davotoula.lightcompressor.hdr.ToneMapMath.PQ_M2
import com.davotoula.lightcompressor.hdr.ToneMapMath.PQ_MAX_NITS
import com.davotoula.lightcompressor.hdr.ToneMapMath.REF_WHITE_NITS
import com.davotoula.lightcompressor.hdr.ToneMapMath.Y_MAX
import com.davotoula.lightcompressor.hdr.ToneMapMath.Y_MIN

/** How the fragment shader reads the decoder's frame. */
internal enum class Sampling {
    /** `samplerExternalOES` (ES2): the driver converts Y'CbCr to R'G'B'. Today's SDR path. */
    EXTERNAL_RGB,

    /** `__samplerExternal2DY2YEXT` (ES3 + `GL_EXT_YUV_target`): raw Y'CbCr, converted in the shader. */
    YUV_TARGET,
}

/** Debug switches for the on-device HDR check. Not public API. */
internal object HdrDebug {
    /** Treats `GL_EXT_YUV_target` as missing after a real ES3 setup, forcing the ES2 tone-map path. */
    @Volatile var forceExternalSampling: Boolean = false
}

/**
 * GLSL sources for every [RenderShader] / [Sampling] combination. Tone-map constants are interpolated
 * from [ToneMapMath], and the tone-map functions port [ToneMapMath.toneMap] step for step.
 */
internal object ShaderSources {
    const val UNIFORM_LW = "uLw"
    const val UNIFORM_LIMITED_RANGE = "uLimitedRange"

    private const val GAMUT_DIM = 3

    // Today's SDR shaders, byte for byte.
    private const val PASSTHROUGH_VERTEX = """uniform mat4 uMVPMatrix;
uniform mat4 uSTMatrix;
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;
void main() {
  gl_Position = uMVPMatrix * aPosition;
  vTextureCoord = (uSTMatrix * aTextureCoord).xy;
}
"""

    private const val PASSTHROUGH_FRAGMENT = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTextureCoord;
uniform samplerExternalOES sTexture;
void main() {
  gl_FragColor = texture2D(sTexture, vTextureCoord);
}
"""

    private const val ES3_VERTEX = """#version 300 es
uniform mat4 uMVPMatrix;
uniform mat4 uSTMatrix;
in vec4 aPosition;
in vec4 aTextureCoord;
out vec2 vTextureCoord;
void main() {
  gl_Position = uMVPMatrix * aPosition;
  vTextureCoord = (uSTMatrix * aTextureCoord).xy;
}
"""

    private const val YUV_TARGET_HEADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT sTexture;
uniform float uLimitedRange;
in vec2 vTextureCoord;
out vec4 outColor;
"""

    private const val EXTERNAL_TONE_MAP_HEADER = """#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vTextureCoord;
uniform samplerExternalOES sTexture;
"""

    fun vertex(sampling: Sampling): String =
        when (sampling) {
            Sampling.EXTERNAL_RGB -> PASSTHROUGH_VERTEX
            Sampling.YUV_TARGET -> ES3_VERTEX
        }

    fun fragment(
        shader: RenderShader,
        sampling: Sampling,
    ): String =
        when (shader) {
            RenderShader.Passthrough -> {
                require(sampling == Sampling.EXTERNAL_RGB) { "Passthrough supports EXTERNAL_RGB only" }
                PASSTHROUGH_FRAGMENT
            }

            RenderShader.YuvToRgb2020 -> {
                // The keep path has no fallback: without GL_EXT_YUV_target it is rejected instead.
                require(sampling == Sampling.YUV_TARGET) { "YuvToRgb2020 supports YUV_TARGET only" }
                YUV_TARGET_HEADER + yuvToRgb2020Function() +
                    """void main() {
  outColor = vec4(sampleRgb2020(), 1.0);
}
"""
            }

            RenderShader.ToneMapHlg -> toneMapFragment(sampling, hlgToNitsFunctions())
            RenderShader.ToneMapPq -> toneMapFragment(sampling, pqToNitsFunctions())
        }

    private fun toneMapFragment(
        sampling: Sampling,
        toNits: String,
    ): String =
        when (sampling) {
            Sampling.YUV_TARGET ->
                YUV_TARGET_HEADER + "uniform float $UNIFORM_LW;\n" + yuvToRgb2020Function() + toNits +
                    toneMapFunction() +
                    """void main() {
  outColor = vec4(toneMap(sampleRgb2020()), 1.0);
}
"""

            // The driver returns the HLG/PQ-encoded BT.2020 R'G'B' as is: no matrix or range expansion.
            Sampling.EXTERNAL_RGB ->
                EXTERNAL_TONE_MAP_HEADER + "uniform float $UNIFORM_LW;\n" + toNits + toneMapFunction() +
                    """void main() {
  vec3 rgb = clamp(texture2D(sTexture, vTextureCoord).rgb, 0.0, 1.0);
  gl_FragColor = vec4(toneMap(rgb), 1.0);
}
"""
        }

    /**
     * Port of [ToneMapMath.yuvToRgb2020]: optional 10-bit limited-range expansion, then the BT.2020
     * non-constant-luminance matrix. Clamps the non-linear R'G'B' to [0, 1] (limited-range undershoot
     * and overshoot) before any transfer curve sees it.
     */
    private fun yuvToRgb2020Function(): String {
        val cMid = (C_MIN + C_MAX) / 2.0
        val cSpan = (C_MAX - C_MIN).toDouble()
        val rCr = 2.0 * (1.0 - KR_2020)
        val bCb = 2.0 * (1.0 - KB_2020)
        val gCb = KB_2020 / KG_2020 * bCb
        val gCr = KR_2020 / KG_2020 * rCr
        return """vec3 sampleRgb2020() {
  vec3 yuv = texture(sTexture, vTextureCoord).xyz;
  float y;
  float cb;
  float cr;
  if (uLimitedRange > 0.5) {
    vec3 code = yuv * ${glsl(CODE_MAX)};
    y = (code.x - ${glsl(Y_MIN)}) / ${glsl(Y_MAX - Y_MIN)};
    cb = (code.y - ${glsl(cMid)}) / ${glsl(cSpan)};
    cr = (code.z - ${glsl(cMid)}) / ${glsl(cSpan)};
  } else {
    y = yuv.x;
    cb = yuv.y - 0.5;
    cr = yuv.z - 0.5;
  }
  vec3 rgb = vec3(
    y + ${glsl(rCr)} * cr,
    y - ${glsl(gCb)} * cb - ${glsl(gCr)} * cr,
    y + ${glsl(bCb)} * cb);
  return clamp(rgb, 0.0, 1.0);
}
"""
    }

    /** Step 1 for HLG: [ToneMapMath.hlgInverseOetf] then [ToneMapMath.hlgToDisplayNits]. */
    private fun hlgToNitsFunctions(): String =
        """float hlgInverseOetf(float e) {
  if (e <= 0.5) {
    return e * e / 3.0;
  }
  return (exp((e - ${glsl(HLG_C)}) / ${glsl(HLG_A)}) + ${glsl(HLG_B)}) / 12.0;
}
vec3 toNits(vec3 e) {
  vec3 scene = vec3(hlgInverseOetf(e.r), hlgInverseOetf(e.g), hlgInverseOetf(e.b));
  float ys = ${glsl(KR_2020)} * scene.r + ${glsl(KG_2020)} * scene.g + ${glsl(KB_2020)} * scene.b;
  float gain = ys > 0.0 ? ${glsl(HLG_PEAK_NITS)} * pow(ys, ${glsl(HLG_GAMMA)} - 1.0) : 0.0;
  return gain * scene;
}
"""

    /** Step 1 for PQ: [ToneMapMath.pqEotfNits] per channel. */
    private fun pqToNitsFunctions(): String =
        """float pqEotfNits(float e) {
  float p = pow(clamp(e, 0.0, 1.0), 1.0 / ${glsl(PQ_M2)});
  float num = max(p - ${glsl(PQ_C1)}, 0.0);
  float den = ${glsl(PQ_C2)} - ${glsl(PQ_C3)} * p;
  return ${glsl(PQ_MAX_NITS)} * pow(num / den, 1.0 / ${glsl(PQ_M1)});
}
vec3 toNits(vec3 e) {
  return vec3(pqEotfNits(e.r), pqEotfNits(e.g), pqEotfNits(e.b));
}
"""

    /** Steps 2 to 5 of [ToneMapMath.toneMap], applied to the output of `toNits`. */
    private fun toneMapFunction(): String {
        // Row r of the row-major BT.2020 -> BT.709 matrix as a GLSL vec3, dotted with the input.
        fun gamutRow(r: Int): String =
            BT2020_TO_BT709
                .copyOfRange(r * GAMUT_DIM, (r + 1) * GAMUT_DIM)
                .joinToString(", ", "vec3(", ")") { glsl(it) }
        return """float hable(float x) {
  return (x * (${glsl(HABLE_A)} * x + ${glsl(HABLE_C)} * ${glsl(HABLE_B)}) + ${glsl(HABLE_D)} * ${glsl(HABLE_E)}) /
    (x * (${glsl(HABLE_A)} * x + ${glsl(HABLE_B)}) + ${glsl(HABLE_D)} * ${glsl(HABLE_F)}) -
    ${glsl(HABLE_E)} / ${glsl(HABLE_F)};
}
float toneCurve(float m, float lw) {
  return hable(2.0 * m) / hable(2.0 * lw);
}
vec3 toneMap(vec3 e) {
  vec3 linear2020 = toNits(e) / ${glsl(REF_WHITE_NITS)};
  vec3 linear709 = max(vec3(
    dot(${gamutRow(0)}, linear2020),
    dot(${gamutRow(1)}, linear2020),
    dot(${gamutRow(2)}, linear2020)), 0.0);
  float m = max(linear709.r, max(linear709.g, linear709.b));
  float scale = m > 0.0 ? toneCurve(m, $UNIFORM_LW) / m : 0.0;
  return pow(clamp(linear709 * scale, 0.0, 1.0), vec3(1.0 / ${glsl(DISPLAY_GAMMA)}));
}
"""
    }

    /**
     * A GLSL ES float literal. `Double.toString()` always has a decimal point or exponent (`64.0`,
     * `1.0E-4`), both valid GLSL; integers go through it so they never appear as `int` literals.
     */
    private fun glsl(value: Double): String {
        require(value.isFinite()) { "non-finite GLSL constant: $value" }
        return value.toString()
    }

    private fun glsl(value: Int): String = glsl(value.toDouble())
}
