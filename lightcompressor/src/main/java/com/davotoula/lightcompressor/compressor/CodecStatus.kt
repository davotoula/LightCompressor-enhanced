package com.davotoula.lightcompressor.compressor

import android.media.MediaCodec

/**
 * True for [MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED]. Deprecated since API 21 and meaningless when output
 * buffers are fetched via `getOutputBuffer(index)`, but OMX-based codecs on older devices (e.g. Pixel 1)
 * still return it from `dequeueOutputBuffer`. Callers must ignore it and dequeue again.
 */
internal fun isOutputBuffersChanged(status: Int): Boolean = status == OUTPUT_BUFFERS_CHANGED

@Suppress("DEPRECATION")
private const val OUTPUT_BUFFERS_CHANGED = MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED
