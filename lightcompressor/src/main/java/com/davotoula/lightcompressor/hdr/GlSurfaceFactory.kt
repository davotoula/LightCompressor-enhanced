package com.davotoula.lightcompressor.hdr

import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import com.davotoula.lightcompressor.video.InputSurface
import com.davotoula.lightcompressor.video.OutputSurface

/** The EGL input surface (current) and the decoder output surface built for one [ColorPlan]. */
internal class GlSurfaces(
    val input: InputSurface,
    val output: OutputSurface,
    val sampling: Sampling,
)

/**
 * Builds the GL pipeline for a [ColorPlan] on the encoder's input [Surface]:
 * - [ColorPlan.Sdr]: ES2 / 8-bit, [Sampling.EXTERNAL_RGB], passthrough (today's behaviour).
 * - [ColorPlan.KeepHdr]: ES3 / 10-bit, [Sampling.YUV_TARGET]; any failure becomes [KeepHdrRejected].
 * - [ColorPlan.ToneMapToSdr]: ES3 / 8-bit with [Sampling.YUV_TARGET], falling back to ES2 with
 *   [Sampling.EXTERNAL_RGB] when ES3 setup fails or `GL_EXT_YUV_target` is missing.
 *
 * The [OutputSurface] is created only once the final context is current, because it binds GL
 * objects to that context. On a terminal failure everything created so far is released, including
 * the encoder [Surface], because the caller never receives the [GlSurfaces] to release.
 */
@Suppress("TooGenericExceptionCaught")
internal object GlSurfaceFactory {
    private const val TAG = "HdrPolicy"
    private const val YUV_TARGET_EXTENSION = "GL_EXT_YUV_target"

    /** Creates InputSurface (made current) + OutputSurface for [plan] on the encoder's input surface. */
    fun create(
        encoderSurface: Surface,
        plan: ColorPlan,
    ): GlSurfaces {
        val surfaces =
            when (plan) {
                is ColorPlan.Sdr -> createEs2(encoderSurface, RenderShader.Passthrough, 1f, true)
                is ColorPlan.KeepHdr -> createKeepHdr(encoderSurface, plan)
                is ColorPlan.ToneMapToSdr -> createToneMap(encoderSurface, plan)
            }
        Log.i(TAG, "GL sampling ${surfaces.sampling} for ${plan.javaClass.simpleName} (${plan.render.shader})")
        return surfaces
    }

    private fun createKeepHdr(
        encoderSurface: Surface,
        plan: ColorPlan.KeepHdr,
    ): GlSurfaces {
        var input: InputSurface? = null
        try {
            val es3 = InputSurface(encoderSurface, gles3 = true, tenBit = true)
            input = es3
            es3.makeCurrent()
            if (!es3.hasExtension(YUV_TARGET_EXTENSION)) {
                throw KeepHdrRejected("$YUV_TARGET_EXTENSION not supported")
            }
            val output =
                OutputSurface(plan.render.shader, Sampling.YUV_TARGET, 1f, isLimitedRange(plan.sourceRange))
            return GlSurfaces(es3, output, Sampling.YUV_TARGET)
        } catch (t: Throwable) {
            releaseAll(input, encoderSurface)
            throw when (t) {
                is KeepHdrRejected -> t
                is Exception -> KeepHdrRejected("keep-HDR GL setup failed: ${t.javaClass.simpleName}: ${t.message}", t)
                else -> t
            }
        }
    }

    private fun createToneMap(
        encoderSurface: Surface,
        plan: ColorPlan.ToneMapToSdr,
    ): GlSurfaces {
        val limitedRange = isLimitedRange(plan.sourceRange)
        return tryYuvTargetToneMap(encoderSurface, plan, limitedRange)
            ?: createEs2(encoderSurface, plan.render.shader, plan.lw, limitedRange)
    }

    /**
     * The preferred ES3 tone-map setup. Returns null, with the encoder [Surface] intact and the ES3
     * EGL objects destroyed, when ES3 setup, the extension check or the shader setup fails.
     */
    @Suppress("ReturnCount")
    private fun tryYuvTargetToneMap(
        encoderSurface: Surface,
        plan: ColorPlan.ToneMapToSdr,
        limitedRange: Boolean,
    ): GlSurfaces? {
        val es3 =
            try {
                // On failure InputSurface has already freed its own EGL objects and kept the Surface.
                InputSurface(encoderSurface, gles3 = true, tenBit = false)
            } catch (e: Exception) {
                Log.w(TAG, "ES3 setup failed; tone mapping with ES2 external sampling", e)
                return null
            } catch (t: Throwable) {
                releaseAll(null, encoderSurface)
                throw t
            }
        try {
            es3.makeCurrent()
            if (HdrDebug.forceExternalSampling || !es3.hasExtension(YUV_TARGET_EXTENSION)) {
                Log.i(
                    TAG,
                    "$YUV_TARGET_EXTENSION unavailable (forced=${HdrDebug.forceExternalSampling}); " +
                        "tone mapping with ES2 external sampling",
                )
                releaseKeepingSurface(es3)
                return null
            }
            val output = OutputSurface(plan.render.shader, Sampling.YUV_TARGET, plan.lw, limitedRange)
            return GlSurfaces(es3, output, Sampling.YUV_TARGET)
        } catch (e: Exception) {
            Log.w(TAG, "ES3 YUV_TARGET tone-map setup failed; tone mapping with ES2 external sampling", e)
            releaseKeepingSurface(es3)
            return null
        } catch (t: Throwable) {
            releaseAll(es3, encoderSurface)
            throw t
        }
    }

    /** ES2 / 8-bit with external sampling. On failure releases everything, including [encoderSurface]. */
    private fun createEs2(
        encoderSurface: Surface,
        shader: RenderShader,
        lw: Float,
        limitedRange: Boolean,
    ): GlSurfaces {
        var input: InputSurface? = null
        try {
            val es2 = InputSurface(encoderSurface)
            input = es2
            es2.makeCurrent()
            val output = OutputSurface(shader, Sampling.EXTERNAL_RGB, lw, limitedRange)
            return GlSurfaces(es2, output, Sampling.EXTERNAL_RGB)
        } catch (t: Throwable) {
            releaseAll(input, encoderSurface)
            throw t
        }
    }

    /** Destroys [input]'s EGL objects but keeps the encoder Surface for the ES2 attempt. */
    private fun releaseKeepingSurface(input: InputSurface) {
        try {
            input.release(releaseSurface = false)
        } catch (e: Exception) {
            // The ES2 attempt will fail on a still-connected Surface and release it then.
            Log.w(TAG, "releasing the ES3 surface failed", e)
        }
    }

    /**
     * Releases [input] (EGL objects and the encoder Surface), or just [encoderSurface] if no
     * InputSurface exists. Cleanup errors are logged so they don't replace the original failure.
     * An [OutputSurface] never needs releasing here: it is the last object created.
     */
    private fun releaseAll(
        input: InputSurface?,
        encoderSurface: Surface,
    ) {
        try {
            input?.release()
        } catch (e: Exception) {
            Log.w(TAG, "cleanup after GL setup failure failed", e)
        }
        // Surface.release() is idempotent, so this is safe after InputSurface.release() too.
        encoderSurface.release()
    }

    private fun isLimitedRange(sourceRange: Int?): Boolean = sourceRange != MediaFormat.COLOR_RANGE_FULL
}
