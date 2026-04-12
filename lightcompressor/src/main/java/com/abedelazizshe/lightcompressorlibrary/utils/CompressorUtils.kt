package com.abedelazizshe.lightcompressorlibrary.utils

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Log
import com.abedelazizshe.lightcompressorlibrary.VideoQuality
import kotlin.math.roundToInt

object CompressorUtils {
    private const val MIN_HEIGHT = 640.0
    private const val MIN_WIDTH = 368.0

    // 1 second between I-frames
    private const val I_FRAME_INTERVAL = 1

    private const val LOG_TAG_OUTPUT_PARAMS = "Output file parameters"
    private const val MIME_HEVC = "video/hevc"

    // Cache for device codec capabilities (don't change at runtime)
    private var hevcSupportCache: Boolean? = null
    private var qtiSupportCache: Boolean? = null

    fun prepareVideoWidth(mediaMetadataRetriever: MediaMetadataRetriever): Double {
        val widthData =
            mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
        return if (widthData.isNullOrEmpty()) {
            MIN_WIDTH
        } else {
            widthData.toDouble()
        }
    }

    fun prepareVideoHeight(mediaMetadataRetriever: MediaMetadataRetriever): Double {
        val heightData =
            mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
        return if (heightData.isNullOrEmpty()) {
            MIN_HEIGHT
        } else {
            heightData.toDouble()
        }
    }

    /**
     * Set output parameters like bitrate and frame rate
     */
    fun setOutputFileParameters(
        inputFormat: MediaFormat,
        outputFormat: MediaFormat,
        newBitrate: Long,
    ) {
        val newFrameRate = getFrameRate(inputFormat)
        val iFrameInterval = getIFrameIntervalRate(inputFormat)
        outputFormat.apply {
            // according to https://developer.android.com/media/optimize/sharing#b-frames_and_encoding_profiles
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val type = outputFormat.getString(MediaFormat.KEY_MIME)
                val higherLevel = getHighestCodecProfileLevel(type)
                Log.i(LOG_TAG_OUTPUT_PARAMS, "Selected CodecProfileLevel: $higherLevel")
                setInteger(MediaFormat.KEY_PROFILE, higherLevel)
                if (type == MIME_HEVC) {
                    setInteger(
                        MediaFormat.KEY_LEVEL,
                        MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4,
                    )
                }
            } else {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            }

            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            )

            setInteger(MediaFormat.KEY_FRAME_RATE, newFrameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameInterval)
            // expected bps
            setInteger(MediaFormat.KEY_BIT_RATE, newBitrate.toInt())
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
            )

            // Only attempt changing colour range if device supports it
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                getColorStandard(inputFormat)?.let {
                    setInteger(MediaFormat.KEY_COLOR_STANDARD, it)
                }

                getColorTransfer(inputFormat)?.let {
                    setInteger(MediaFormat.KEY_COLOR_TRANSFER, it)
                }

                val targetColorRange = MediaFormat.COLOR_RANGE_LIMITED
                val inputColorRange = getColorRange(inputFormat)
                if (inputColorRange != null && inputColorRange != targetColorRange) {
                    Log.w(
                        LOG_TAG_OUTPUT_PARAMS,
                        "Overriding input color range $inputColorRange with limited range $targetColorRange",
                    )
                }
                setInteger(MediaFormat.KEY_COLOR_RANGE, targetColorRange)
            }

            Log.i(
                LOG_TAG_OUTPUT_PARAMS,
                "videoFormat: $this",
            )
        }
    }

    private const val DEFAULT_FRAME_RATE = 30

    private fun getFrameRate(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            format.getInteger(MediaFormat.KEY_FRAME_RATE)
        } else {
            DEFAULT_FRAME_RATE
        }

    private fun getIFrameIntervalRate(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_I_FRAME_INTERVAL)) {
            format.getInteger(
                MediaFormat.KEY_I_FRAME_INTERVAL,
            )
        } else {
            I_FRAME_INTERVAL
        }

    private fun getColorStandard(format: MediaFormat): Int? =
        if (format.containsKey(MediaFormat.KEY_COLOR_STANDARD)) {
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.N
            ) {
                format.getInteger(
                    MediaFormat.KEY_COLOR_STANDARD,
                )
            } else {
                // should not be used on older devices
                0
            }
        } else {
            null
        }

    private fun getColorTransfer(format: MediaFormat): Int? =
        if (format.containsKey(MediaFormat.KEY_COLOR_TRANSFER)) {
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.N
            ) {
                format.getInteger(
                    MediaFormat.KEY_COLOR_TRANSFER,
                )
            } else {
                // should not be used on older devices
                0
            }
        } else {
            null
        }

    private fun getColorRange(format: MediaFormat): Int? =
        if (format.containsKey(MediaFormat.KEY_COLOR_RANGE)) {
            if (Build.VERSION.SDK_INT >=
                Build.VERSION_CODES.N
            ) {
                format.getInteger(
                    MediaFormat.KEY_COLOR_RANGE,
                )
            } else {
                // should not be used on older devices
                0
            }
        } else {
            null
        }

    /**
     * Counts the number of tracks (video, audio) found in the file source provided
     * @param extractor what is used to extract the encoded data
     * @param isVideo to determine whether we are processing video or audio at time of call
     * @return index of the requested track
     */
    private const val TRACK_NOT_FOUND = -5

    @Suppress("ReturnCount")
    fun findTrack(
        extractor: MediaExtractor,
        isVideo: Boolean,
    ): Int {
        val numTracks = extractor.trackCount
        for (i in 0 until numTracks) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME)
            if (isVideo) {
                if (mime?.startsWith("video/")!!) return i
            } else {
                if (mime?.startsWith("audio/")!!) return i
            }
        }
        return TRACK_NOT_FOUND
    }

    fun printException(exception: Exception) {
        var message = "An error has occurred!"
        exception.localizedMessage?.let {
            message = it
        }
        Log.e("Compressor", message, exception)
    }

    /**
     * Get fixed bitrate value based on the file's current bitrate
     * @param bitrate file's current bitrate
     * @return new smaller bitrate value
     */
    private const val QUALITY_VERY_LOW = 0.1
    private const val QUALITY_LOW = 0.2
    private const val QUALITY_MEDIUM = 0.3
    private const val QUALITY_HIGH = 0.4
    private const val QUALITY_VERY_HIGH = 0.6

    fun getBitrate(
        bitrate: Int,
        quality: VideoQuality,
    ): Int =
        when (quality) {
            VideoQuality.VERY_LOW -> (bitrate * QUALITY_VERY_LOW).roundToInt()
            VideoQuality.LOW -> (bitrate * QUALITY_LOW).roundToInt()
            VideoQuality.MEDIUM -> (bitrate * QUALITY_MEDIUM).roundToInt()
            VideoQuality.HIGH -> (bitrate * QUALITY_HIGH).roundToInt()
            VideoQuality.VERY_HIGH -> (bitrate * QUALITY_VERY_HIGH).roundToInt()
        }

    /**
     * Generate new width and height for source file
     * @param width file's original width
     * @param height file's original height
     * @return the scale factor to apply to the video's resolution
     */
    private const val RESOLUTION_FULL_HD = 1920
    private const val RESOLUTION_HD = 1280
    private const val RESOLUTION_SD = 960
    private const val SCALE_FULL_HD = 0.5
    private const val SCALE_HD = 0.75
    private const val SCALE_SD = 0.95
    private const val SCALE_DEFAULT = 0.9

    fun autoResizePercentage(
        width: Double,
        height: Double,
    ): Double =
        when {
            width >= RESOLUTION_FULL_HD || height >= RESOLUTION_FULL_HD -> SCALE_FULL_HD
            width >= RESOLUTION_HD || height >= RESOLUTION_HD -> SCALE_HD
            width >= RESOLUTION_SD || height >= RESOLUTION_SD -> SCALE_SD
            else -> SCALE_DEFAULT
        }

    fun hasQTI(): Boolean =
        qtiSupportCache ?: run {
            val hasQti =
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .codecInfos
                    .any { it.name.contains("qti.avc") }
            Log.i("Codec Detection", "QTI codec support: $hasQti")
            qtiSupportCache = hasQti
            hasQti
        }

    /**
     * Check if the device supports HEVC (H.265) encoding.
     * Result is cached after first check for performance.
     * @return true if HEVC encoding is supported, false otherwise
     */
    fun isHevcEncodingSupported(): Boolean =
        hevcSupportCache ?: run {
            val isSupported =
                MediaCodecList(MediaCodecList.REGULAR_CODECS)
                    .codecInfos
                    .any { codec ->
                        codec.isEncoder &&
                            codec.supportedTypes.any {
                                it.equals(MIME_HEVC, ignoreCase = true)
                            }
                    }
            Log.i("HEVC Support", if (isSupported) "HEVC encoder found" else "No HEVC encoder found")
            hevcSupportCache = isSupported
            isSupported
        }

    /**
     * Get the highest profile level supported by the encoder
     * For AVC (H.264): High > Main > Baseline
     * For HEVC (H.265): Main > Main Still Picture
     */
    @Suppress("ReturnCount")
    private fun getHighestCodecProfileLevel(type: String?): Int {
        val defaultProfile = getDefaultProfileForType(type)

        if (type == null) return defaultProfile

        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        val supportedProfiles =
            codecInfos
                .filter { codec -> type in codec.supportedTypes && codec.name.contains("encoder") }
                .firstNotNullOfOrNull { codec -> codec.getCapabilitiesForType(type) }
                ?.profileLevels
                ?.map { it.profile }
                ?: return defaultProfile

        return selectBestProfile(type, supportedProfiles)
    }

    private fun getDefaultProfileForType(type: String?): Int =
        when (type) {
            MIME_HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            else -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        }

    private fun selectBestProfile(
        type: String,
        supportedProfiles: List<Int>,
    ): Int =
        when (type) {
            "video/avc" ->
                when {
                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh in supportedProfiles ->
                        MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
                    MediaCodecInfo.CodecProfileLevel.AVCProfileMain in supportedProfiles ->
                        MediaCodecInfo.CodecProfileLevel.AVCProfileMain
                    else -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
                }
            MIME_HEVC -> MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
            else -> MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline
        }
}

/*
            if (codec.name == "c2.qti.avc.encoder") {
                val capabilities = codec.getCapabilitiesForType("video/avc")


                for (c in capabilities.colorFormats) {
                    Log.wtf("color format", c.toString())
                }

                for (c in capabilities.profileLevels) {
                    Log.wtf(" level", c.level.toString())
                    Log.wtf("profile ", c.profile.toString())
                }

                Log.wtf(
                    "complexity range",
                    capabilities.encoderCapabilities.complexityRange.upper.toString()
                )

                Log.wtf(
                    "quality range", " ${ capabilities.encoderCapabilities.qualityRange}"
                )

                Log.wtf(
                    "frame rates range", " ${ capabilities.videoCapabilities.supportedFrameRates}"
                )

                Log.wtf(
                    "bitrate rates range", " ${ capabilities.videoCapabilities.bitrateRange}"
                )

                Log.wtf(
                    "mode supported", " ${ capabilities.encoderCapabilities.isBitrateModeSupported(1)}"
                )

                Log.wtf(
                    "height alignment", " ${ capabilities.videoCapabilities.heightAlignment}"
                )

                Log.wtf(
                    "supported heights", " ${ capabilities.videoCapabilities.supportedHeights}"
                )

                Log.wtf(
                    "supported points", " ${ capabilities.videoCapabilities.supportedPerformancePoints}"
                )

                Log.wtf(
                    "supported width", " ${ capabilities.videoCapabilities.supportedWidths}"
                )

                Log.wtf(
                    "width alignment", " ${ capabilities.videoCapabilities.widthAlignment}"
                )

                Log.wtf(
                    "default format", " ${ capabilities.defaultFormat}"
                )

                Log.wtf(
                    "mime", " ${ capabilities.mimeType}"
                )

            }
 */
