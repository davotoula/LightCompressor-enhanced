package com.davotoula.lightcompressor.video

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface

/**
 * Thrown when `eglChooseConfig` fails or returns zero configs for the requested surface format.
 */
internal class EglConfigUnavailableException(
    message: String,
) : RuntimeException(message)

/**
 * EGL context and window surface on an encoder's input [Surface].
 *
 * @param gles3 request an OpenGL ES 3 context (renderable type ES3, client version 3) instead of ES 2.
 * @param tenBit request an RGBA_1010102 config instead of RGB888.
 */
@Suppress("TooGenericExceptionThrown")
class InputSurface
    internal constructor(
        surface: Surface?,
        gles3: Boolean,
        tenBit: Boolean,
    ) {
        /** 8-bit RGB888 ES2 recordable surface (the pre-HDR behaviour). */
        constructor(surface: Surface?) : this(surface, false, false)

        companion object {
            private const val TAG = "InputSurface"
            private const val EGL_RECORDABLE_ANDROID = 0x3142
            private const val EGL_OPENGL_ES2_BIT = 4
            private const val EGL_COLOR_SIZE = 8
            private const val EGL_COLOR_SIZE_10_BIT = 10
            private const val EGL_ALPHA_SIZE_10_BIT = 2
            private const val GLES2_CLIENT_VERSION = 2
            private const val GLES3_CLIENT_VERSION = 3
        }

        private var mEGLDisplay: EGLDisplay? = null
        private var mEGLContext: EGLContext? = null
        private var mEGLSurface: EGLSurface? = null
        private var mSurface: Surface? = null
        private var extensions: Set<String>? = null

        init {
            if (surface == null) {
                throw NullPointerException("surface must not be null")
            }
            mSurface = surface
            var setUp = false
            try {
                eglSetup(gles3, tenBit)
                setUp = true
            } finally {
                if (!setUp) {
                    // Free any EGL objects created before the failure, but keep the Surface: the
                    // caller may build another InputSurface on it (ES3 -> ES2 fallback).
                    destroyEglObjects()
                }
            }
        }

        @Suppress("ThrowsCount")
        private fun eglSetup(
            gles3: Boolean,
            tenBit: Boolean,
        ) {
            mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (mEGLDisplay === EGL14.EGL_NO_DISPLAY) {
                throw RuntimeException("unable to get EGL14 display")
            }
            val version = IntArray(2)
            if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
                mEGLDisplay = null
                throw RuntimeException("unable to initialize EGL14")
            }
            val config = chooseConfig(gles3, tenBit)
            val attrs =
                intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION,
                    if (gles3) GLES3_CLIENT_VERSION else GLES2_CLIENT_VERSION,
                    EGL14.EGL_NONE,
                )
            mEGLContext =
                EGL14.eglCreateContext(mEGLDisplay, config, EGL14.EGL_NO_CONTEXT, attrs, 0)
            checkEglError()
            if (mEGLContext == null || mEGLContext == EGL14.EGL_NO_CONTEXT) {
                throw RuntimeException("null context")
            }
            val surfaceAttrs =
                intArrayOf(
                    EGL14.EGL_NONE,
                )
            mEGLSurface =
                EGL14.eglCreateWindowSurface(
                    mEGLDisplay,
                    config,
                    mSurface,
                    surfaceAttrs,
                    0,
                )
            checkEglError()
            if (mEGLSurface == null || mEGLSurface == EGL14.EGL_NO_SURFACE) {
                throw RuntimeException("surface was null")
            }
        }

        /**
         * Picks an EGL config. 10-bit requests try with `EGL_RECORDABLE_ANDROID` first and, if no config
         * matches, once more without it (some drivers, e.g. Pixel 9a, expose no recordable 1010102 config).
         */
        private fun chooseConfig(
            gles3: Boolean,
            tenBit: Boolean,
        ): EGLConfig {
            val description = configDescription(gles3, tenBit)
            chooseConfigOrNull(configAttribs(gles3, tenBit, recordable = true))?.let { config ->
                if (tenBit) Log.i(TAG, "EGL config for $description found with EGL_RECORDABLE_ANDROID")
                return config
            }
            if (!tenBit) {
                throw EglConfigUnavailableException("unable to find $description+recordable EGL config")
            }
            chooseConfigOrNull(configAttribs(gles3, tenBit, recordable = false))?.let { config ->
                Log.i(TAG, "EGL config for $description found without EGL_RECORDABLE_ANDROID")
                return config
            }
            throw EglConfigUnavailableException(
                "no EGL config for $description (with and without EGL_RECORDABLE_ANDROID)",
            )
        }

        /** Returns the first matching config, or null when `eglChooseConfig` fails or matches nothing. */
        private fun chooseConfigOrNull(attribList: IntArray): EGLConfig? {
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            val ok =
                EGL14.eglChooseConfig(
                    mEGLDisplay,
                    attribList,
                    0,
                    configs,
                    0,
                    configs.size,
                    numConfigs,
                    0,
                )
            if (!ok) {
                // Clear the pending error so it doesn't fail a later checkEglError() after a retry.
                val error = EGL14.eglGetError()
                Log.w(TAG, "eglChooseConfig failed: 0x${Integer.toHexString(error)}")
                return null
            }
            return if (numConfigs[0] > 0) configs[0] else null
        }

        /**
         * The 8-bit ES2 recordable list is exactly the pre-HDR one:
         * R8 G8 B8, renderable ES2, EGL_RECORDABLE_ANDROID 1.
         */
        private fun configAttribs(
            gles3: Boolean,
            tenBit: Boolean,
            recordable: Boolean,
        ): IntArray {
            val colorSize = if (tenBit) EGL_COLOR_SIZE_10_BIT else EGL_COLOR_SIZE
            val attribs =
                mutableListOf(
                    EGL14.EGL_RED_SIZE,
                    colorSize,
                    EGL14.EGL_GREEN_SIZE,
                    colorSize,
                    EGL14.EGL_BLUE_SIZE,
                    colorSize,
                )
            if (tenBit) {
                attribs += listOf(EGL14.EGL_ALPHA_SIZE, EGL_ALPHA_SIZE_10_BIT)
            }
            attribs +=
                listOf(
                    EGL14.EGL_RENDERABLE_TYPE,
                    if (gles3) EGLExt.EGL_OPENGL_ES3_BIT_KHR else EGL_OPENGL_ES2_BIT,
                )
            if (recordable) {
                attribs += listOf(EGL_RECORDABLE_ANDROID, 1)
            }
            attribs += EGL14.EGL_NONE
            return attribs.toIntArray()
        }

        private fun configDescription(
            gles3: Boolean,
            tenBit: Boolean,
        ): String = "${if (tenBit) "10-bit RGBA1010102" else "RGB888"} ${if (gles3) "ES3" else "ES2"}"

        /** Releases the EGL surface and context, and the encoder's input [Surface]. */
        fun release() = release(releaseSurface = true)

        /**
         * Releases the EGL surface and context. With [releaseSurface] false the encoder's input [Surface]
         * stays alive (disconnected from EGL), so another [InputSurface] can be built on it.
         */
        internal fun release(releaseSurface: Boolean) {
            destroyEglObjects()

            if (releaseSurface) {
                mSurface?.release()
            }
            mSurface = null
        }

        /** Destroys the window surface, then the context. The display is shared and is not terminated. */
        private fun destroyEglObjects() {
            val display = mEGLDisplay
            if (display != null && display != EGL14.EGL_NO_DISPLAY) {
                if (mEGLContext != null && EGL14.eglGetCurrentContext() == mEGLContext) {
                    EGL14.eglMakeCurrent(
                        display,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT,
                    )
                }
                // Surface first: destroying it disconnects the encoder Surface from EGL.
                mEGLSurface?.takeIf { it != EGL14.EGL_NO_SURFACE }?.let {
                    EGL14.eglDestroySurface(display, it)
                }
                mEGLContext?.takeIf { it != EGL14.EGL_NO_CONTEXT }?.let {
                    EGL14.eglDestroyContext(display, it)
                }
            }

            mEGLDisplay = null
            mEGLContext = null
            mEGLSurface = null
        }

        fun makeCurrent() {
            if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
                throw RuntimeException("eglMakeCurrent failed")
            }
        }

        fun swapBuffers(): Boolean = EGL14.eglSwapBuffers(mEGLDisplay, mEGLSurface)

        fun setPresentationTime(nsecs: Long) {
            EGLExt.eglPresentationTimeANDROID(mEGLDisplay, mEGLSurface, nsecs)
        }

        /**
         * Whether the current GL context advertises extension [name] (matched as a whole token).
         * Must be called after [makeCurrent]; the extension string is read once and cached.
         */
        internal fun hasExtension(name: String): Boolean {
            val cached =
                extensions ?: run {
                    val raw = GLES20.glGetString(GLES20.GL_EXTENSIONS)
                    if (raw == null) {
                        // No current context: don't cache, so a call after makeCurrent() still works.
                        Log.w(TAG, "glGetString(GL_EXTENSIONS) returned null; is the context current?")
                        return false
                    }
                    raw
                        .split(Regex("\\s+"))
                        .filter { it.isNotEmpty() }
                        .toSet()
                        .also { extensions = it }
                }
            return name in cached
        }

        private fun checkEglError() {
            var failed = false
            while (EGL14.eglGetError() != EGL14.EGL_SUCCESS) {
                failed = true
            }
            if (failed) {
                throw RuntimeException("EGL error encountered (see log)")
            }
        }
    }
