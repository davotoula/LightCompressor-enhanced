package com.davotoula.lightcompressor.hdr

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import androidx.annotation.VisibleForTesting

/** A specific encoder (by codec name) and the profile it will be configured with. */
internal data class HdrEncoder(
    val name: String,
    val profile: Int,
)

internal interface HdrCapabilities {
    fun hlgEncoder(mime: String): HdrEncoder?

    fun pqEncoder(mime: String): HdrEncoder?
}

/**
 * Finds the first encoder for a mime type that supports 10-bit HDR editing. The encoder that is
 * checked is the encoder that must be used, so its name is returned.
 */
internal class MediaCodecHdrCapabilities(
    private val sdkInt: Int = Build.VERSION.SDK_INT,
    private val codecInfos: () -> Array<MediaCodecInfo> = { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos },
) : HdrCapabilities {
    override fun hlgEncoder(mime: String): HdrEncoder? = lookup(mime, pq = false)

    override fun pqEncoder(mime: String): HdrEncoder? = lookup(mime, pq = true)

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    private fun lookup(
        mime: String,
        pq: Boolean,
    ): HdrEncoder? {
        if (sdkInt < API_HDR_EDITING) return null
        val key = CacheKey(sdkInt, mime.lowercase(), pq)
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
        }
        // Enumerating outside the lock; a benign duplicate walk under a race is harmless.
        val found =
            try {
                codecInfos().firstNotNullOfOrNull { encoderFor(it, mime, pq) }
            } catch (e: Exception) {
                // Never let a codec-list quirk fail the job: no encoder means tone map. Not cached,
                // so a transient failure doesn't stick for the life of the process.
                Log.w(TAG, "HDR encoder lookup for $mime failed, treating as unsupported: ${e.message}", e)
                return null
            }
        synchronized(cache) { cache[key] = found }
        return found
    }

    @Suppress("ReturnCount")
    private fun encoderFor(
        info: MediaCodecInfo,
        mime: String,
        pq: Boolean,
    ): HdrEncoder? {
        if (!info.isEncoder || info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return null
        val caps = runCatching { info.getCapabilitiesForType(mime) }.getOrNull() ?: return null
        val feature =
            caps.isFeatureSupported(CodecCapabilities.FEATURE_HdrEditing) ||
                (!pq && sdkInt >= API_HLG_EDITING && caps.isFeatureSupported(CodecCapabilities.FEATURE_HlgEditing))
        val profile = if (pq) CodecProfileLevel.HEVCProfileMain10HDR10 else CodecProfileLevel.HEVCProfileMain10
        val hasProfile = (caps.profileLevels ?: emptyArray()).any { it.profile == profile }
        return if (feature && hasProfile) HdrEncoder(info.name, profile) else null
    }

    private data class CacheKey(
        val sdkInt: Int,
        val mime: String,
        val pq: Boolean,
    )

    internal companion object {
        private const val API_HDR_EDITING = 33
        private const val API_HLG_EDITING = 35
        private const val TAG = "HdrCapabilities"
        private val cache = HashMap<CacheKey, HdrEncoder?>()

        @VisibleForTesting
        internal fun clearCache() {
            synchronized(cache) { cache.clear() }
        }
    }
}
