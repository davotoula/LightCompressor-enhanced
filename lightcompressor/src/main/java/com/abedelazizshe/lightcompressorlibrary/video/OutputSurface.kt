package com.abedelazizshe.lightcompressorlibrary.video

import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.view.Surface

@Suppress("TooGenericExceptionThrown")
class OutputSurface : OnFrameAvailableListener {
    private var mSurfaceTexture: SurfaceTexture? = null
    private var mSurface: Surface? = null
    private val mFrameSyncObject = Object()
    private var mFrameAvailable = false
    private var mTextureRender: TextureRenderer? = null

    companion object {
        private const val FRAME_WAIT_TIMEOUT_MS = 2500L
        private const val FRAME_WAIT_MAX_ATTEMPTS = 3
    }

    /**
     * Creates an OutputSurface using the current EGL context. This Surface will be
     * passed to MediaCodec.configure().
     */
    init {
        setup()
    }

    /**
     * Creates instances of TextureRender and SurfaceTexture, and a Surface associated
     * with the SurfaceTexture.
     */
    private fun setup() {
        mTextureRender = TextureRenderer()
        mTextureRender?.let {
            it.surfaceCreated()

            // Even if we don't access the SurfaceTexture after the constructor returns, we
            // still need to keep a reference to it. The Surface doesn't retain a reference
            // at the Java level, so if we don't either then the object can get GCed, which
            // causes the native finalizer to run.
            mSurfaceTexture = SurfaceTexture(it.getTextureId())
            mSurfaceTexture?.let { surfaceTexture ->
                surfaceTexture.setOnFrameAvailableListener(this)
                mSurface = Surface(mSurfaceTexture)
            }
        }
    }

    /**
     * Discards all resources held by this class, notably the EGL context.
     */
    fun release() {
        mSurface?.release()

        mTextureRender = null
        mSurface = null
        mSurfaceTexture = null
    }

    /**
     * Returns the Surface that we draw onto.
     */
    fun getSurface(): Surface? = mSurface

    /**
     * Latches the next buffer into the texture.  Must be called from the thread that created
     * the OutputSurface object, after the onFrameAvailable callback has signaled that new
     * data is available.
     */
    fun awaitNewImage() {
        val timeOutMs = FRAME_WAIT_TIMEOUT_MS
        val maxAttempts = FRAME_WAIT_MAX_ATTEMPTS
        synchronized(mFrameSyncObject) {
            var attempts = 0
            while (!mFrameAvailable) {
                try {
                    mFrameSyncObject.wait(timeOutMs)
                    if (!mFrameAvailable) {
                        attempts++
                        if (attempts >= maxAttempts) {
                            throw RuntimeException(
                                "Surface frame wait timed out after ${maxAttempts * timeOutMs}ms",
                            )
                        }
                    }
                } catch (ie: InterruptedException) {
                    throw RuntimeException(ie)
                }
            }
            mFrameAvailable = false
        }
        mTextureRender?.checkGlError("before updateTexImage")
        mSurfaceTexture?.updateTexImage()
    }

    /**
     * Draws the data from SurfaceTexture onto the current EGL surface.
     */
    fun drawImage() {
        mTextureRender?.drawFrame(mSurfaceTexture!!)
    }

    override fun onFrameAvailable(p0: SurfaceTexture?) {
        synchronized(mFrameSyncObject) {
            if (mFrameAvailable) {
                throw RuntimeException("mFrameAvailable already set, frame could be dropped")
            }
            mFrameAvailable = true
            mFrameSyncObject.notifyAll()
        }
    }
}
