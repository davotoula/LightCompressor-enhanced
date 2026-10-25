package com.davotoula.lightcompressor.hdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShaderSourcesTest {
    // Copied verbatim from TextureRenderer.kt (lines 29-47) before the shaders moved to ShaderSources.
    private val todaysVertexShader = """uniform mat4 uMVPMatrix;
uniform mat4 uSTMatrix;
attribute vec4 aPosition;
attribute vec4 aTextureCoord;
varying vec2 vTextureCoord;
void main() {
  gl_Position = uMVPMatrix * aPosition;
  vTextureCoord = (uSTMatrix * aTextureCoord).xy;
}
"""

    private val todaysFragmentShader = """#extension GL_OES_EGL_image_external : require
precision mediump float;
varying vec2 vTextureCoord;
uniform samplerExternalOES sTexture;
void main() {
  gl_FragColor = texture2D(sTexture, vTextureCoord);
}
"""

    private fun assertContainsAll(
        source: String,
        vararg needles: String,
    ) {
        for (needle in needles) {
            assertTrue("expected \"$needle\" in:\n$source", source.contains(needle))
        }
    }

    @Test
    fun passthroughExternal_isTodaysShaderVerbatim() {
        assertEquals(todaysVertexShader, ShaderSources.vertex(Sampling.EXTERNAL_RGB))
        assertEquals(
            todaysFragmentShader,
            ShaderSources.fragment(RenderShader.Passthrough, Sampling.EXTERNAL_RGB),
        )
    }

    @Test
    fun toneMapHlg_containsHlgAndGamutConstants() {
        for (sampling in Sampling.values()) {
            assertContainsAll(
                ShaderSources.fragment(RenderShader.ToneMapHlg, sampling),
                "0.17883277",
                "0.55991073",
                "1.660491",
                "203.0",
                "uLw",
            )
        }
    }

    @Test
    fun toneMapPq_containsPqConstants() {
        for (sampling in Sampling.values()) {
            assertContainsAll(
                ShaderSources.fragment(RenderShader.ToneMapPq, sampling),
                "78.84375",
                "18.6875",
                "uLw",
            )
        }
    }

    @Test
    fun yuvTarget_usesEs3AndYuvExtension() {
        assertTrue(ShaderSources.vertex(Sampling.YUV_TARGET).startsWith("#version 300 es\n"))
        for (shader in listOf(RenderShader.YuvToRgb2020, RenderShader.ToneMapHlg, RenderShader.ToneMapPq)) {
            val source = ShaderSources.fragment(shader, Sampling.YUV_TARGET)
            assertTrue(source.startsWith("#version 300 es\n"))
            assertContainsAll(
                source,
                "#extension GL_OES_EGL_image_external_essl3 : require",
                "#extension GL_EXT_YUV_target : require",
                "__samplerExternal2DY2YEXT",
                "uLimitedRange",
            )
        }
    }

    @Test
    fun externalToneMap_hasPrecisionFallback() {
        for (shader in listOf(RenderShader.ToneMapHlg, RenderShader.ToneMapPq)) {
            assertContainsAll(
                ShaderSources.fragment(shader, Sampling.EXTERNAL_RGB),
                "GL_FRAGMENT_PRECISION_HIGH",
                "mediump",
                "samplerExternalOES",
            )
        }
    }
}
