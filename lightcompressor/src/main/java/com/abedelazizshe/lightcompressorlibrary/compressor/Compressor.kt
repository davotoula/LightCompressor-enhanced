package com.abedelazizshe.lightcompressorlibrary.compressor

import android.content.Context
import android.media.*
import android.net.Uri
import android.util.Log
import com.abedelazizshe.lightcompressorlibrary.CompressionProgressListener
import com.abedelazizshe.lightcompressorlibrary.VideoCodec
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.getBitrate
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.isHevcEncodingSupported
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.prepareVideoHeight
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.prepareVideoWidth
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.printException
import com.abedelazizshe.lightcompressorlibrary.utils.roundDimension
import com.abedelazizshe.lightcompressorlibrary.video.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Created by AbedElaziz Shehadeh on 27 Jan, 2020
 * elaziz.shehadeh@gmail.com
 */
object Compressor {

    // 2Mbps
    private const val MIN_BITRATE = 2000000

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
    ): Result = withContext(Dispatchers.Default) {

        val extractor = MediaExtractor()
        // Retrieve the source's metadata to be used as input to generate new values for compression
        val mediaMetadataRetriever = MediaMetadataRetriever()

        try {
            mediaMetadataRetriever.setDataSource(context, srcUri)
        } catch (exception: Exception) {
            printException(exception)
            return@withContext Result(
                index,
                success = false,
                failureMessage = "${exception.message}"
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
                failureMessage = "Failed to extract video meta-data, please try again"
            )
        }

        var (rotation, bitrate, duration) = try {
            Triple(rotationData.toInt(), bitrateData.toInt(), durationData.toLong() * 1000)
        } catch (e: java.lang.Exception) {
            return@withContext Result(
                index,
                success = false,
                failureMessage = "Failed to extract video meta-data, please try again"
            )
        }

        // Check for a min video bitrate before compression
        // Note: this is an experimental value
        if (configuration.isMinBitrateCheckEnabled && bitrate <= MIN_BITRATE)
            return@withContext Result(index, success = false, failureMessage = INVALID_BITRATE)

        // Check if H.265 encoding is supported when H.265 codec is selected
        if (configuration.videoCodec == VideoCodec.H265 && !isHevcEncodingSupported()) {
            Log.w("Compressor", "H.265 encoding requested but not supported on this device")
            return@withContext Result(
                index,
                success = false,
                failureMessage = "H.265 (HEVC) encoding is not supported on this device. Please use VideoCodec.H264 instead."
            )
        }

        //Handle new bitrate value
        val effectiveBitrateFromConfig = configuration.getEffectiveBitrateInBps()
        val qualityBasedBitrate = getBitrate(bitrate, configuration.quality).toLong()
        val newBitrate: Long = effectiveBitrateFromConfig ?: qualityBasedBitrate

        Log.i("Compressor", "Original bitrate: $bitrate bps")
        Log.i("Compressor", "Effective bitrate from config: $effectiveBitrateFromConfig")
        Log.i("Compressor", "Quality-based bitrate: $qualityBasedBitrate")
        Log.i("Compressor", "Final bitrate used: $newBitrate bps")

        //Handle new width and height values
        val resizer = configuration.resizer
        val target = resizer?.resize(width, height) ?: Pair(width, height)
        var newWidth = roundDimension(target.first)
        var newHeight = roundDimension(target.second)

        //Handle rotation values and swapping height and width if needed
        rotation = when (rotation) {
            90, 270 -> {
                val tempHeight = newHeight
                newHeight = newWidth
                newWidth = tempHeight
                0
            }

            180 -> 0
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
            configuration.videoCodec
        )
    }

    @Suppress("DEPRECATION")
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
        videoCodec: VideoCodec
    ): Result {

        // Use native MediaMuxer-based transcoders for better performance
        extractor.release()
        if (newWidth == 0 || newHeight == 0) {
            return Result(
                id,
                success = false,
                failureMessage = "Invalid output dimensions for transcode"
            )
        }

        return when (videoCodec) {
            VideoCodec.H265 -> HevcTranscoder(
                context = context,
                srcUri = srcUri,
                request = HevcTranscoder.Request(
                    index = id,
                    width = newWidth,
                    height = newHeight,
                    bitrate = newBitrate,
                    destination = File(destination),
                    streamablePath = streamableFile,
                    disableAudio = disableAudio,
                    rotation = rotation,
                    durationUs = duration,
                    listener = compressionProgressListener
                )
            ).transcode()

            VideoCodec.H264 -> AVCTranscoder(
                context = context,
                srcUri = srcUri,
                request = AVCTranscoder.Request(
                    index = id,
                    width = newWidth,
                    height = newHeight,
                    bitrate = newBitrate,
                    destination = File(destination),
                    streamablePath = streamableFile,
                    disableAudio = disableAudio,
                    rotation = rotation,
                    durationUs = duration,
                    listener = compressionProgressListener
                )
            ).transcode()
        }
    }
}
