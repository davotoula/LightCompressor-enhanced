package com.davotoula.lightcompressor.hdr

import android.content.Context
import android.media.MediaExtractor
import android.net.Uri
import android.util.Log
import com.davotoula.lightcompressor.utils.CompressorUtils.findVideoTrack

/** Reads a video's [ColorInfo] for compression and for [com.davotoula.lightcompressor.VideoCompressor.probeHdr]. */
internal object ColorInfoReader {
    private const val TAG = "HdrPolicy"

    /**
     * Reads the colour description of the video track compression would use. Never throws: Compressor
     * treats an exception escaping [com.davotoula.lightcompressor.compressor.Transcoder.transcode] as a
     * reason for its 16-aligned retry, so any failure here yields an untagged [ColorInfo] (an Sdr plan)
     * and the transcode attempt reports the real error.
     */
    @Suppress("TooGenericExceptionCaught")
    fun read(
        context: Context,
        uri: Uri,
    ): ColorInfo {
        var extractor: MediaExtractor? = null
        return try {
            extractor = MediaExtractor().apply { setDataSource(context, uri, null) }
            val trackIndex = findVideoTrack(extractor)
            if (trackIndex < 0) untagged() else ColorInfo.forVideoTrack(extractor, trackIndex)
        } catch (e: Exception) {
            Log.w(TAG, "Colour info pre-read failed, treating input as SDR: ${e.message}")
            untagged()
        } finally {
            try {
                extractor?.release()
            } catch (releaseError: Exception) {
                Log.w(TAG, "Failed to release colour info extractor: ${releaseError.message}")
            }
        }
    }

    private fun untagged() = ColorInfo(null, null, null, null)
}
