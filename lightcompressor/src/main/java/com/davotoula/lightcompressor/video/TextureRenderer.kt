package com.davotoula.lightcompressor.video

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import com.davotoula.lightcompressor.hdr.RenderShader
import com.davotoula.lightcompressor.hdr.Sampling
import com.davotoula.lightcompressor.hdr.ShaderSources
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Draws the decoder's [SurfaceTexture] frame with GLSL from [ShaderSources].
 *
 * @param shader the fragment shader; [RenderShader.Passthrough] is the SDR path.
 * @param sampling how the shader reads the frame; [Sampling.YUV_TARGET] needs a current ES3 context.
 * @param lw tone-map peak relative to reference white (`uLw`); only the tone-map shaders use it.
 * @param limitedRange whether the source is limited range (`uLimitedRange`); only YUV_TARGET shaders use it.
 */
@Suppress("TooGenericExceptionThrown")
class TextureRenderer internal constructor(
    shader: RenderShader,
    sampling: Sampling,
    private val lw: Float,
    private val limitedRange: Boolean,
) {
    /** Today's SDR passthrough renderer. */
    constructor() : this(RenderShader.Passthrough, Sampling.EXTERNAL_RGB, 1f, true)

    companion object {
        private const val FLOAT_SIZE_BYTES = 4
        private const val COMPONENTS_PER_VERTEX = 5
        private const val POSITION_COMPONENTS = 3
        private const val TEXCOORD_OFFSET = 3
        private const val MATRIX_SIZE = 16
        private const val QUAD_VERTEX_COUNT = 4
        private const val TEXCOORD_COMPONENTS = 2
        private const val INVALID_TEXTURE_ID = -12345
        private const val NOT_PRESENT = -1
    }

    private val triangleVerticesDataStrideBytes = COMPONENTS_PER_VERTEX * FLOAT_SIZE_BYTES
    private val triangleVerticesDataPosOffset = 0
    private val triangleVerticesDataUvOffset = TEXCOORD_OFFSET
    private var mTriangleVertices: FloatBuffer

    private val vertexShader = ShaderSources.vertex(sampling)

    private val fragmentShader = ShaderSources.fragment(shader, sampling)

    private val mMVPMatrix = FloatArray(MATRIX_SIZE)
    private val mSTMatrix = FloatArray(MATRIX_SIZE)

    private var mProgram = 0
    private var mTextureID = INVALID_TEXTURE_ID
    private var muMVPMatrixHandle = 0
    private var muSTMatrixHandle = 0
    private var maPositionHandle = 0
    private var maTextureHandle = 0
    private var muLwHandle = NOT_PRESENT
    private var muLimitedRangeHandle = NOT_PRESENT

    init {
        val mTriangleVerticesData =
            floatArrayOf( // X, Y, Z, U, V
                -1.0f,
                -1.0f,
                0f,
                0f,
                0f,
                1.0f,
                -1.0f,
                0f,
                1f,
                0f,
                -1.0f,
                1.0f,
                0f,
                0f,
                1f,
                1.0f,
                1.0f,
                0f,
                1f,
                1f,
            )
        mTriangleVertices =
            ByteBuffer
                .allocateDirect(
                    mTriangleVerticesData.size * FLOAT_SIZE_BYTES,
                ).order(ByteOrder.nativeOrder())
                .asFloatBuffer()
        mTriangleVertices.put(mTriangleVerticesData).position(0)

        Matrix.setIdentityM(mSTMatrix, 0)
    }

    fun getTextureId(): Int = mTextureID

    fun drawFrame(st: SurfaceTexture) {
        checkGlError("onDrawFrame start")
        st.getTransformMatrix(mSTMatrix)

        GLES20.glClearColor(0.0f, 1.0f, 0.0f, 1.0f)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT or GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(mProgram)
        checkGlError("glUseProgram")

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID)

        mTriangleVertices.position(triangleVerticesDataPosOffset)
        GLES20.glVertexAttribPointer(
            maPositionHandle,
            POSITION_COMPONENTS,
            GLES20.GL_FLOAT,
            false,
            triangleVerticesDataStrideBytes,
            mTriangleVertices,
        )
        checkGlError("glVertexAttribPointer maPosition")
        GLES20.glEnableVertexAttribArray(maPositionHandle)
        checkGlError("glEnableVertexAttribArray maPositionHandle")

        mTriangleVertices.position(triangleVerticesDataUvOffset)
        GLES20.glVertexAttribPointer(
            maTextureHandle,
            TEXCOORD_COMPONENTS,
            GLES20.GL_FLOAT,
            false,
            triangleVerticesDataStrideBytes,
            mTriangleVertices,
        )
        checkGlError("glVertexAttribPointer maTextureHandle")
        GLES20.glEnableVertexAttribArray(maTextureHandle)
        checkGlError("glEnableVertexAttribArray maTextureHandle")

        Matrix.setIdentityM(mMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(muMVPMatrixHandle, 1, false, mMVPMatrix, 0)
        GLES20.glUniformMatrix4fv(muSTMatrixHandle, 1, false, mSTMatrix, 0)
        // Optional uniforms: only the HDR shaders declare them (location -1 = not present).
        if (muLwHandle != NOT_PRESENT) {
            GLES20.glUniform1f(muLwHandle, lw)
        }
        if (muLimitedRangeHandle != NOT_PRESENT) {
            GLES20.glUniform1f(muLimitedRangeHandle, if (limitedRange) 1f else 0f)
        }

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, QUAD_VERTEX_COUNT)
        checkGlError("glDrawArrays")
        GLES20.glFinish()
    }

    /**
     * Initializes GL state.  Call this after the EGL surface has been created and made current.
     */
    @Suppress("ThrowsCount")
    fun surfaceCreated() {
        mProgram = createProgram()
        if (mProgram == 0) {
            throw RuntimeException("failed creating program")
        }
        maPositionHandle = GLES20.glGetAttribLocation(mProgram, "aPosition")
        checkGlError("glGetAttribLocation aPosition")
        if (maPositionHandle == -1) {
            throw RuntimeException("Could not get attrib location for aPosition")
        }
        maTextureHandle = GLES20.glGetAttribLocation(mProgram, "aTextureCoord")
        checkGlError("glGetAttribLocation aTextureCoord")
        if (maTextureHandle == -1) {
            throw RuntimeException("Could not get attrib location for aTextureCoord")
        }

        muMVPMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uMVPMatrix")
        checkGlError("glGetUniformLocation uMVPMatrix")
        if (muMVPMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uMVPMatrix")
        }

        muSTMatrixHandle = GLES20.glGetUniformLocation(mProgram, "uSTMatrix")
        checkGlError("glGetUniformLocation uSTMatrix")
        if (muSTMatrixHandle == -1) {
            throw RuntimeException("Could not get attrib location for uSTMatrix")
        }

        muLwHandle = GLES20.glGetUniformLocation(mProgram, ShaderSources.UNIFORM_LW)
        muLimitedRangeHandle = GLES20.glGetUniformLocation(mProgram, ShaderSources.UNIFORM_LIMITED_RANGE)
        checkGlError("glGetUniformLocation optional uniforms")

        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        mTextureID = textures[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, mTextureID)
        checkGlError("glBindTexture mTextureID")

        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_NEAREST.toFloat(),
        )
        GLES20.glTexParameterf(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR.toFloat(),
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        checkGlError("glTexParameter")
    }

    /** Compiles [source]; throws with the driver's info log on failure. */
    private fun loadShader(
        shaderType: Int,
        source: String,
    ): Int {
        val shader = GLES20.glCreateShader(shaderType)
        checkGlError("glCreateShader type=$shaderType")
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            val stage = if (shaderType == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
            throw RuntimeException("failed creating program: $stage shader compile failed: $log")
        }
        return shader
    }

    /** Returns 0 only if `glCreateProgram` does; compile and link failures throw with the info log. */
    private fun createProgram(): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShader)
        val pixelShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShader)

        val program = GLES20.glCreateProgram()
        checkGlError("glCreateProgram")
        if (program == 0) {
            return 0
        }
        GLES20.glAttachShader(program, vertexShader)
        checkGlError("glAttachShader")
        GLES20.glAttachShader(program, pixelShader)
        checkGlError("glAttachShader")
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("failed creating program: link failed: $log")
        }
        return program
    }

    fun checkGlError(op: String) {
        var error: Int
        if (GLES20.glGetError().also { error = it } != GLES20.GL_NO_ERROR) {
            throw RuntimeException("$op: glError $error")
        }
    }
}
