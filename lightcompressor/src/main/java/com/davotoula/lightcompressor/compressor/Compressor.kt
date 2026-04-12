package com.davotoula.lightcompressor.compressor

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.davotoula.lightcompressor.CompressionProgressListener
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.config.Configuration
import com.davotoula.lightcompressor.utils.CompressorUtils.getBitrate
import com.davotoula.lightcompressor.utils.CompressorUtils.isHevcEncodingSupported
import com.davotoula.lightcompressor.utils.CompressorUtils.prepareVideoHeight
import com.davotoula.lightcompressor.utils.CompressorUtils.prepareVideoWidth
import com.davotoula.lightcompressor.utils.CompressorUtils.printException
import com.davotoula.lightcompressor.utils.fallbackTo16Aligned
import com.davotoula.lightcompressor.utils.roundDimension
import com.davotoula.lightcompressor.video.Result
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Created by AbedElaziz Shehadeh on 27 Jan, 2020
 * elaziz.shehadeh@gmail.com
 */
object Compressor {
    // 2Mbps
    private const val MIN_BITRATE = 2_000_000

    private const val ROTATION_90 = 90
    private const val ROTATION_180 = 180
    private const val ROTATION_270 = 270
    private const val MS_PER_US = 1000

    private const val INVALID_BITRATE =
        "The provided bitrate is smaller than what is needed for compression " +
            "try to set isMinBitRateEnabled to false"

    var isRunning = true

    suspend fun compressVideo(
        index: Int,
        context: Context,
        srcUri: Uri,
        destination: String,
        streamableFile: String?,
        configuration: Configuration,
        listener: CompressionProgressListener,
    ): Result =
        withContext(Dispatchers.Default) {
            val extractor = MediaExtractor()
            // Retrieve the source's metadata to be used as input to generate new values for compression
            val mediaMetadataRetriever = MediaMetadataRetriever()

            try {
                mediaMetadataRetriever.setDataSource(context, srcUri)
            } catch (
                @Suppress("TooGenericExceptionCaught") exception: Exception,
            ) {
                printException(exception)
                return@withContext Result(
                    index,
                    success = false,
                    failureMessage = "${exception.message}",
                )
            }

            runCatching {
                extractor.setDataSource(context, srcUri, null)
            }

            val height: Double = prepareVideoHeight(mediaMetadataRetriever)

            val width: Double = prepareVideoWidth(mediaMetadataRetriever)

            val rotationData =
                mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)

            val bitrateData =
                mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)

            val durationData =
                mediaMetadataRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)

            if (rotationData.isNullOrEmpty() || bitrateData.isNullOrEmpty() || durationData.isNullOrEmpty()) {
                // Exit execution
                return@withContext Result(
                    index,
                    success = false,
                    failureMessage = "Failed to extract video meta-data, please try again",
                )
            }

            var (rotation, bitrate, duration) =
                try {
                    Triple(rotationData.toInt(), bitrateData.toInt(), durationData.toLong() * MS_PER_US)
                } catch (_: java.lang.Exception) {
                    return@withContext Result(
                        index,
                        success = false,
                        failureMessage = "Failed to extract video meta-data, please try again",
                    )
                }

            // Check for a min video bitrate before compression
            // Note: this is an experimental value
            if (configuration.isMinBitrateCheckEnabled && bitrate <= MIN_BITRATE) {
                return@withContext Result(index, success = false, failureMessage = INVALID_BITRATE)
            }

            // Check if H.265 encoding is supported when H.265 codec is selected
            if (configuration.videoCodec == VideoCodec.H265 && !isHevcEncodingSupported()) {
                Log.w("Compressor", "H.265 encoding requested but not supported on this device")
                return@withContext Result(
                    index,
                    success = false,
                    failureMessage =
                        "H.265 (HEVC) encoding is not supported on this device. " +
                            "Please use VideoCodec.H264 instead.",
                )
            }

            // Handle new bitrate value
            val effectiveBitrateFromConfig = configuration.getEffectiveBitrateInBps()
            val qualityBasedBitrate = getBitrate(bitrate, configuration.quality).toLong()
            val newBitrate: Long = effectiveBitrateFromConfig ?: qualityBasedBitrate

            Log.i("Compressor", "Original bitrate: $bitrate bps")
            Log.i("Compressor", "Effective bitrate from config: $effectiveBitrateFromConfig")
            Log.i("Compressor", "Quality-based bitrate: $qualityBasedBitrate")
            Log.i("Compressor", "Final bitrate used: $newBitrate bps")

            // Handle new width and height values
            val resizer = configuration.resizer
            val target = resizer?.resize(width, height) ?: Pair(width, height)
            var newWidth = roundDimension(target.first)
            var newHeight = roundDimension(target.second)

            // Handle rotation values and swapping height and width if needed
            rotation =
                when (rotation) {
                    ROTATION_90, ROTATION_270 -> {
                        val tempHeight = newHeight
                        newHeight = newWidth
                        newWidth = tempHeight
                        0
                    }

                    ROTATION_180 -> 0
                    else -> rotation
                }

            return@withContext start(
                index,
                newWidth,
                newHeight,
                destination,
                newBitrate,
                streamableFile,
                configuration.disableAudio,
                context,
                srcUri,
                extractor,
                listener,
                duration,
                rotation,
                configuration.videoCodec,
            )
        }

    @Suppress("DEPRECATION", "LongParameterList")
    private fun start(
        id: Int,
        newWidth: Int,
        newHeight: Int,
        destination: String,
        newBitrate: Long,
        streamableFile: String?,
        disableAudio: Boolean,
        context: Context,
        srcUri: Uri,
        extractor: MediaExtractor,
        compressionProgressListener: CompressionProgressListener,
        duration: Long,
        rotation: Int,
        videoCodec: VideoCodec,
    ): Result {
        // Use native MediaMuxer-based transcoders for better performance
        extractor.release()
        if (newWidth == 0 || newHeight == 0) {
            return Result(
                id,
                success = false,
                failureMessage = "Invalid output dimensions for transcode",
            )
        }

        return try {
            transcode(
                id,
                newWidth,
                newHeight,
                destination,
                newBitrate,
                streamableFile,
                disableAudio,
                context,
                srcUri,
                compressionProgressListener,
                duration,
                rotation,
                videoCodec,
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            val fallback =
                fallbackTo16Aligned(newWidth, newHeight)
                    ?: throw e // already 16-aligned or too small, nothing to retry
            Log.w(
                "Compressor",
                "Encoder failed with ${newWidth}x$newHeight, " +
                    "retrying with 16-aligned ${fallback.first}x${fallback.second}",
                e,
            )
            transcode(
                id,
                fallback.first,
                fallback.second,
                destination,
                newBitrate,
                streamableFile,
                disableAudio,
                context,
                srcUri,
                compressionProgressListener,
                duration,
                rotation,
                videoCodec,
            )
        }
    }

    @Suppress("LongParameterList")
    private fun transcode(
        id: Int,
        width: Int,
        height: Int,
        destination: String,
        bitrate: Long,
        streamableFile: String?,
        disableAudio: Boolean,
        context: Context,
        srcUri: Uri,
        listener: CompressionProgressListener,
        duration: Long,
        rotation: Int,
        videoCodec: VideoCodec,
    ): Result =
        Transcoder(
            codec = videoCodec,
            context = context,
            srcUri = srcUri,
            request =
                Transcoder.Request(
                    index = id,
                    width = width,
                    height = height,
                    bitrate = bitrate,
                    destination = File(destination),
                    streamablePath = streamableFile,
                    disableAudio = disableAudio,
                    rotation = rotation,
                    durationUs = duration,
                    listener = listener,
                ),
        ).transcode()
}
