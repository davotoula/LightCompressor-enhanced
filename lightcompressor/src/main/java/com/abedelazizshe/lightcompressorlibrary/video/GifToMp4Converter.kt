/*
 * Copyright (c) 2025 Vitor Pamplona
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to use,
 * copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the
 * Software, and to permit persons to whom the Software is furnished to do so,
 * subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS
 * FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 *
 * Adapted from the amethyst project (vitorpamplona/amethyst#2189) for use in
 * LightCompressor-enhanced. The original code targeted amethyst's upload pipeline;
 * this version integrates with the LightCompressor library by using android.util.Log
 * and returning a [GifToMp4Result] instead of MediaCompressorResult.
 */
package com.abedelazizshe.lightcompressorlibrary.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Movie
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.UUID

/**
 * Result of a successful [GifToMp4Converter.convert] call.
 *
 * @property file the MP4 file written to the app cache directory.
 * @property mimeType always `"video/mp4"`.
 * @property size size of [file] in bytes.
 */
data class GifToMp4Result(
    val file: File,
    val mimeType: String,
    val size: Long,
)

/**
 * Converts an animated GIF to an H.264 MP4 file using Android's [MediaCodec], [MediaMuxer]
 * and OpenGL ES 2.0 via EGL. The output is written to [Context.getCacheDir] with a UUID name.
 *
 * The conversion yields a substantial size reduction — e.g. a 3 MB GIF commonly becomes
 * ~150 kB MP4 — because H.264 is far more efficient than GIF for temporal redundancy.
 *
 * Usage:
 * ```kotlin
 * val result = GifToMp4Converter.convert(gifUri, context)
 * if (result != null) {
 *     // result.file is the MP4 in cacheDir, result.size bytes, mime video/mp4
 * }
 * ```
 *
 * Adapted from vitorpamplona/amethyst PR #2189.
 */
object GifToMp4Converter {
    private const val LOG_TAG = "GifToMp4Converter"
    private const val I_FRAME_INTERVAL = 1
    private const val TIMEOUT_US = 10_000L
    private const val DEFAULT_BITRATE_BPS = 2_000_000
    private const val DEFAULT_FRAME_DELAY_MS = 100
    private const val US_PER_MS = 1000L
    private const val NS_PER_US = 1000L
    private const val MAX_GIF_SIZE_BYTES = 20 * 1024 * 1024
    private const val READ_CHUNK_BYTES = 8 * 1024
    private const val DRAIN_EOS_MAX_ITERATIONS = 500

    // Not exposed by android.opengl.EGL14; required so eglChooseConfig picks a
    // config whose pixel format is compatible with a MediaCodec input surface.
    private const val EGL_RECORDABLE_ANDROID = 0x3142

    // Fullscreen quad: 4 vertices x (2 position + 2 texcoord) floats
    // Texcoord Y is flipped because bitmap origin is top-left, GL is bottom-left
    private val QUAD_COORDS =
        floatArrayOf(
            -1f,
            -1f,
            0f,
            1f,
            1f,
            -1f,
            1f,
            1f,
            -1f,
            1f,
            0f,
            0f,
            1f,
            1f,
            1f,
            0f,
        )

    private const val VERTEX_SHADER =
        "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "  gl_Position = aPosition;\n" +
            "  vTexCoord = aTexCoord;\n" +
            "}\n"

    private const val FRAGMENT_SHADER =
        "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "void main() {\n" +
            "  gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n"

    /**
     * Convert the GIF referenced by [uri] to an MP4 file in [context]'s cache directory.
     *
     * Returns `null` if the input cannot be read, exceeds [MAX_GIF_SIZE_BYTES], is not a
     * valid GIF, or encoding fails. Cancellation is propagated.
     */
    suspend fun convert(
        uri: Uri,
        context: Context,
    ): GifToMp4Result? =
        // Dispatchers.Default: the bulk of this work is CPU/GPU bound
        // (Movie decode, GL rendering, MediaCodec encode). Running on IO
        // would occupy a thread from the large IO pool for several seconds
        // with no kernel wait, risking starvation of real IO coroutines.
        // The brief file read at the start and muxer writes are acceptable
        // on Default — they're short relative to the encoding loop.
        withContext(Dispatchers.Default) {
            try {
                convertInternal(uri, context)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(LOG_TAG, "GIF to MP4 conversion failed", e)
                null
            }
        }

    @Suppress("deprecation") // android.graphics.Movie is deprecated but still the simplest GIF decoder available
    private fun convertInternal(
        uri: Uri,
        context: Context,
    ): GifToMp4Result? {
        val gifBytes =
            context.contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(READ_CHUNK_BYTES)
                var total = 0
                while (true) {
                    val read = input.read(chunk)
                    if (read == -1) break
                    total += read
                    if (total > MAX_GIF_SIZE_BYTES) {
                        Log.w(LOG_TAG, "GIF exceeds max size of $MAX_GIF_SIZE_BYTES bytes")
                        return null
                    }
                    output.write(chunk, 0, read)
                }
                output.toByteArray()
            } ?: run {
                Log.w(LOG_TAG, "Failed to read GIF bytes")
                return null
            }

        val movie =
            Movie.decodeByteArray(gifBytes, 0, gifBytes.size)
                ?: run {
                    Log.w(LOG_TAG, "Failed to decode GIF")
                    return null
                }

        val gifWidth = movie.width()
        val gifHeight = movie.height()
        val durationMs = movie.duration()

        if (gifWidth <= 0 || gifHeight <= 0 || durationMs <= 0) {
            Log.w(LOG_TAG, "Invalid GIF dimensions (${gifWidth}x$gifHeight) or duration ($durationMs ms)")
            return null
        }

        val frameDelays = parseGifFrameDelays(gifBytes)
        if (frameDelays.isEmpty()) {
            Log.w(LOG_TAG, "No frames found in GIF")
            return null
        }

        val totalDelayMs = frameDelays.sum()
        val avgFps =
            if (totalDelayMs > 0) {
                (frameDelays.size * 1000.0 / totalDelayMs).toInt().coerceIn(1, 50)
            } else {
                10
            }

        val width = roundUpToEven(gifWidth)
        val height = roundUpToEven(gifHeight)

        Log.d(
            LOG_TAG,
            "Converting GIF: ${gifWidth}x$gifHeight, duration=${durationMs}ms, " +
                "frames=${frameDelays.size}, avgFps=$avgFps -> MP4 ${width}x$height",
        )

        val outputFile = File(context.cacheDir, "${UUID.randomUUID()}.mp4")
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var egl: EglHelper? = null
        var codecSurface: Surface? = null
        var bitmap: Bitmap? = null
        var program = 0
        var textureId = 0

        try {
            val mimeType = MediaFormat.MIMETYPE_VIDEO_AVC
            val format =
                MediaFormat.createVideoFormat(mimeType, width, height).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                    setInteger(MediaFormat.KEY_BIT_RATE, calculateBitrate(width, height))
                    setInteger(MediaFormat.KEY_FRAME_RATE, avgFps)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
                }

            codec = MediaCodec.createEncoderByType(mimeType)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codecSurface = codec.createInputSurface()
            codec.start()

            // Wrap the codec surface with EGL for presentation timestamp control
            egl = EglHelper(codecSurface)
            egl.makeCurrent()

            // Set up GL program and texture for drawing bitmaps
            program = createGlProgram()
            textureId = createGlTexture()
            val vertexBuffer = createVertexBuffer()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()

            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val bitmapCanvas = Canvas(bitmap)

            var presentationTimeUs = 0L
            var gifTimeMs = 0

            for (i in frameDelays.indices) {
                movie.setTime(gifTimeMs)

                bitmapCanvas.drawColor(Color.WHITE)
                movie.draw(bitmapCanvas, 0f, 0f)

                // Draw bitmap to EGL surface via GL texture
                drawBitmapFrame(bitmap, textureId, program, vertexBuffer, width, height)

                // Set the precise presentation timestamp and submit
                egl.setPresentationTime(presentationTimeUs * NS_PER_US)
                egl.swapBuffers()

                // Drain encoder output
                val drainResult = drainEncoder(codec, muxer, bufferInfo, trackIndex, muxerStarted, false)
                trackIndex = drainResult.trackIndex
                muxerStarted = drainResult.muxerStarted

                presentationTimeUs += frameDelays[i] * US_PER_MS
                gifTimeMs += frameDelays[i]
            }

            // Signal end of stream and drain
            codec.signalEndOfInputStream()
            drainEncoder(codec, muxer, bufferInfo, trackIndex, muxerStarted, true)

            Log.d(LOG_TAG, "GIF to MP4 conversion complete: ${outputFile.length()} bytes")

            return GifToMp4Result(outputFile, "video/mp4", outputFile.length())
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Encoding failed", e)
            if (outputFile.exists()) outputFile.delete()
            return null
        } finally {
            // GL resources must be released while the EGL context is still current
            // (i.e. before egl.release()), otherwise glDelete* calls operate on a
            // detached context and the handles leak in the driver.
            try {
                if (textureId != 0) GLES20.glDeleteTextures(1, intArrayOf(textureId), 0)
            } catch (_: Exception) {
            }
            try {
                if (program != 0) GLES20.glDeleteProgram(program)
            } catch (_: Exception) {
            }
            try {
                bitmap?.recycle()
            } catch (_: Exception) {
            }
            try {
                egl?.release()
            } catch (_: Exception) {
            }
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            try {
                codec?.release()
            } catch (_: Exception) {
            }
            try {
                codecSurface?.release()
            } catch (_: Exception) {
            }
            try {
                muxer?.stop()
            } catch (_: Exception) {
            }
            try {
                muxer?.release()
            } catch (_: Exception) {
            }
        }
    }

    // region EGL

    private class EglHelper(
        surface: Surface,
    ) {
        val display: EGLDisplay
        val context: EGLContext
        val eglSurface: EGLSurface

        init {
            // Request an EGL config compatible with a MediaCodec input surface.
            // Without EGL_RECORDABLE_ANDROID some GPUs (Adreno/MediaTek) pick a
            // window-surface-incompatible config and eglCreateWindowSurface fails
            // or the encoder produces corrupt output. Matches InputSurface.java
            // in the library's existing transcoder pipeline.
            var localDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var localContext: EGLContext = EGL14.EGL_NO_CONTEXT
            var localEglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

            try {
                localDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                check(localDisplay != EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }

                val version = IntArray(2)
                check(EGL14.eglInitialize(localDisplay, version, 0, version, 1)) { "eglInitialize failed" }

                val configAttribs =
                    intArrayOf(
                        EGL14.EGL_RED_SIZE,
                        8,
                        EGL14.EGL_GREEN_SIZE,
                        8,
                        EGL14.EGL_BLUE_SIZE,
                        8,
                        EGL14.EGL_ALPHA_SIZE,
                        8,
                        EGL14.EGL_RENDERABLE_TYPE,
                        EGL14.EGL_OPENGL_ES2_BIT,
                        EGL14.EGL_SURFACE_TYPE,
                        EGL14.EGL_WINDOW_BIT,
                        EGL_RECORDABLE_ANDROID,
                        1,
                        EGL14.EGL_NONE,
                    )
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                check(EGL14.eglChooseConfig(localDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)) {
                    "eglChooseConfig failed"
                }
                check(numConfigs[0] > 0) { "eglChooseConfig returned no matching configs" }
                val config = requireNotNull(configs[0]) { "eglChooseConfig returned null config" }

                val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
                localContext = EGL14.eglCreateContext(localDisplay, config, EGL14.EGL_NO_CONTEXT, contextAttribs, 0)
                check(localContext != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }

                val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
                localEglSurface = EGL14.eglCreateWindowSurface(localDisplay, config, surface, surfaceAttribs, 0)
                check(localEglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
            } catch (t: Throwable) {
                // Any step above may have partially initialized EGL state.
                // Tear down whatever succeeded so we do not leak driver handles
                // when the caller never gets a reference to this object.
                if (localEglSurface != EGL14.EGL_NO_SURFACE) {
                    try {
                        EGL14.eglDestroySurface(localDisplay, localEglSurface)
                    } catch (_: Exception) {
                    }
                }
                if (localContext != EGL14.EGL_NO_CONTEXT) {
                    try {
                        EGL14.eglDestroyContext(localDisplay, localContext)
                    } catch (_: Exception) {
                    }
                }
                if (localDisplay != EGL14.EGL_NO_DISPLAY) {
                    try {
                        EGL14.eglTerminate(localDisplay)
                    } catch (_: Exception) {
                    }
                }
                throw t
            }

            display = localDisplay
            context = localContext
            eglSurface = localEglSurface
        }

        fun makeCurrent() {
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
        }

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, nsecs)
        }

        fun swapBuffers() {
            EGL14.eglSwapBuffers(display, eglSurface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, eglSurface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }

    // endregion

    // region GL helpers

    private fun createGlProgram(): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        check(program != 0) { "glCreateProgram failed" }
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val linkStatus = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw RuntimeException("glLinkProgram failed: $info")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        return program
    }

    private fun compileShader(
        type: Int,
        source: String,
    ): Int {
        val shader = GLES20.glCreateShader(type)
        check(shader != 0) { "glCreateShader failed for type $type" }
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        if (compileStatus[0] != GLES20.GL_TRUE) {
            val info = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw RuntimeException("glCompileShader failed for type $type: $info")
        }
        return shader
    }

    private fun createGlTexture(): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return texIds[0]
    }

    private fun createVertexBuffer(): FloatBuffer =
        ByteBuffer
            .allocateDirect(QUAD_COORDS.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_COORDS)
                position(0)
            }

    private fun drawBitmapFrame(
        bitmap: Bitmap,
        textureId: Int,
        program: Int,
        vertexBuffer: FloatBuffer,
        width: Int,
        height: Int,
    ) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(1f, 1f, 1f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        GLES20.glUseProgram(program)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

        val posHandle = GLES20.glGetAttribLocation(program, "aPosition")
        val texHandle = GLES20.glGetAttribLocation(program, "aTexCoord")

        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)

        // stride = 4 floats per vertex (2 pos + 2 tex) * 4 bytes
        val stride = 4 * 4
        vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(posHandle)
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        vertexBuffer.position(2)
        GLES20.glEnableVertexAttribArray(texHandle)
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posHandle)
        GLES20.glDisableVertexAttribArray(texHandle)
    }

    // endregion

    // region GIF parsing

    /**
     * Parses per-frame delays from the GIF binary by reading Graphic Control Extension blocks.
     * Returns a list of delays in milliseconds, one per frame.
     *
     * GIF delay values are in centiseconds (1/100s). Per browser convention,
     * delays of 0 or 1 centisecond are treated as 100ms (10fps).
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun parseGifFrameDelays(bytes: ByteArray): List<Int> {
        if (bytes.size < 13) return emptyList()

        val delays = mutableListOf<Int>()

        var pos = 13

        // Skip Global Color Table if present
        val packed = bytes[10].toInt() and 0xFF
        if (packed and 0x80 != 0) {
            pos += 3 * (1 shl ((packed and 0x07) + 1))
        }

        while (pos < bytes.size) {
            when (bytes[pos].toInt() and 0xFF) {
                0x21 -> {
                    pos++
                    if (pos >= bytes.size) break
                    val label = bytes[pos].toInt() and 0xFF
                    pos++

                    if (label == 0xF9 && pos + 5 <= bytes.size) {
                        val blockSize = bytes[pos].toInt() and 0xFF
                        if (blockSize == 4) {
                            val delayLow = bytes[pos + 2].toInt() and 0xFF
                            val delayHigh = bytes[pos + 3].toInt() and 0xFF
                            val delayCentiseconds = delayLow or (delayHigh shl 8)
                            val delayMs =
                                if (delayCentiseconds <= 1) DEFAULT_FRAME_DELAY_MS else delayCentiseconds * 10
                            delays.add(delayMs)
                        }
                        pos = skipSubBlocks(bytes, pos)
                    } else {
                        pos = skipSubBlocks(bytes, pos)
                    }
                }

                0x2C -> {
                    // Image Descriptor: 1 (separator, current pos) + 8 (L/T/W/H) + 1 (packed) = 10 bytes
                    if (pos + 10 > bytes.size) break
                    val imgPacked = bytes[pos + 9].toInt() and 0xFF
                    pos += 10
                    if (imgPacked and 0x80 != 0) {
                        pos += 3 * (1 shl ((imgPacked and 0x07) + 1))
                        if (pos >= bytes.size) break
                    }
                    pos++
                    if (pos >= bytes.size) break
                    pos = skipSubBlocks(bytes, pos)
                }

                0x3B -> {
                    break
                }

                else -> {
                    pos++
                }
            }
        }

        return delays
    }

    private fun skipSubBlocks(
        bytes: ByteArray,
        startPos: Int,
    ): Int {
        var pos = startPos
        while (pos < bytes.size) {
            val blockSize = bytes[pos].toInt() and 0xFF
            pos++
            if (blockSize == 0) break
            pos = minOf(pos + blockSize, bytes.size)
        }
        return pos
    }

    // endregion

    // region Encoder helpers

    private data class DrainState(
        val trackIndex: Int,
        val muxerStarted: Boolean,
    )

    private fun drainEncoder(
        codec: MediaCodec,
        muxer: MediaMuxer,
        bufferInfo: MediaCodec.BufferInfo,
        trackIndex: Int,
        muxerStarted: Boolean,
        endOfStream: Boolean,
    ): DrainState {
        var currentTrackIndex = trackIndex
        var currentMuxerStarted = muxerStarted
        var eosDrainIterations = 0

        while (true) {
            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)

            when {
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return DrainState(currentTrackIndex, currentMuxerStarted)
                    if (++eosDrainIterations >= DRAIN_EOS_MAX_ITERATIONS) {
                        throw RuntimeException(
                            "Encoder failed to drain after EOS within $DRAIN_EOS_MAX_ITERATIONS iterations",
                        )
                    }
                }

                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    currentTrackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    currentMuxerStarted = true
                }

                outputIndex >= 0 -> {
                    val outputBuffer =
                        codec.getOutputBuffer(outputIndex)
                            ?: throw RuntimeException("Encoder output buffer $outputIndex was null")

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        bufferInfo.size = 0
                    }

                    if (bufferInfo.size > 0 && currentMuxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(currentTrackIndex, outputBuffer, bufferInfo)
                    }

                    codec.releaseOutputBuffer(outputIndex, false)

                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        return DrainState(currentTrackIndex, currentMuxerStarted)
                    }
                }
            }
        }
    }

    /**
     * Pick an H.264 target bitrate based on pixel area. The thresholds target
     * roughly 1080p/720p/480p/below and the numbers match amethyst's upstream
     * converter so GIF-to-MP4 output looks consistent across both projects.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun calculateBitrate(
        width: Int,
        height: Int,
    ): Int {
        val pixels = width * height
        return when {
            pixels >= 1920 * 1080 -> 4_000_000
            pixels >= 1280 * 720 -> DEFAULT_BITRATE_BPS
            pixels >= 640 * 480 -> 1_000_000
            else -> 500_000
        }
    }

    /**
     * H.264 requires even width/height. Rounds up odd non-negative values to
     * the next even integer; even values and non-positive values are returned
     * unchanged (non-positive inputs never reach here — validated upstream).
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal fun roundUpToEven(value: Int): Int = if (value > 0 && value % 2 != 0) value + 1 else value

    // endregion
}
