package com.abedelazizshe.lightcompressorlibrary.compressor

import android.content.Context
import android.media.*
import android.net.Uri
import android.os.Build
import android.util.Log
import com.abedelazizshe.lightcompressorlibrary.CompressionProgressListener
import com.abedelazizshe.lightcompressorlibrary.VideoCodec
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.findTrack
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.getBitrate
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.hasQTI
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.isHevcEncodingSupported
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.prepareVideoHeight
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.prepareVideoWidth
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.printException
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.setOutputFileParameters
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.setUpMP4Movie
import com.abedelazizshe.lightcompressorlibrary.utils.StreamableVideo
import com.abedelazizshe.lightcompressorlibrary.utils.roundDimension
import com.abedelazizshe.lightcompressorlibrary.video.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Created by AbedElaziz Shehadeh on 27 Jan, 2020
 * elaziz.shehadeh@gmail.com
 */
object Compressor {

    // 2Mbps
    private const val MIN_BITRATE = 2000000

    private const val MEDIACODEC_TIMEOUT_DEFAULT = 100L

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
        extractor: MediaExtractor,
        compressionProgressListener: CompressionProgressListener,
        duration: Long,
        rotation: Int,
        videoCodec: VideoCodec
    ): Result {

        if (newWidth != 0 && newHeight != 0) {

            val cacheFile = File(destination)

            try {
                // MediaCodec accesses encoder and decoder components and processes the new video
                //input to generate a compressed/smaller size video
                val bufferInfo = MediaCodec.BufferInfo()

                // Setup mp4 movie
                val movie = setUpMP4Movie(rotation, cacheFile)

                // MediaMuxer outputs MP4 in this app
                val mediaMuxer = MP4Builder().createMovie(movie)

                // Start with video track
                val videoIndex = findTrack(extractor, isVideo = true)

                extractor.selectTrack(videoIndex)
                extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val inputFormat = extractor.getTrackFormat(videoIndex)

                val mimeType = videoCodec.mimeType

                val outputFormat: MediaFormat =
                    MediaFormat.createVideoFormat(mimeType, newWidth, newHeight)
                //set output format
                setOutputFileParameters(
                    inputFormat,
                    outputFormat,
                    newBitrate,
                )
                val resolvedFrameRate = if (outputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                    outputFormat.getInteger(MediaFormat.KEY_FRAME_RATE)
                } else {
                    Log.w(
                        "Compressor",
                        "Output format missing frame rate; defaulting to 30fps"
                    )
                    30
                }
                val sanitizedFrameRate = if (resolvedFrameRate > 0) resolvedFrameRate else 30
                if (sanitizedFrameRate != resolvedFrameRate) {
                    Log.w(
                        "Compressor",
                        "Non-positive frame rate ($resolvedFrameRate) replaced with 30fps"
                    )
                }
                Log.d(
                    "Compressor",
                    "Using constant frame rate $sanitizedFrameRate fps for encoder presentation timestamps"
                )
                var frameIndex = 0L

                val decoder: MediaCodec

                val hasQTI = hasQTI()

                val encoder = prepareEncoder(outputFormat, hasQTI, mimeType)

                val inputSurface: InputSurface
                val outputSurface: OutputSurface

                try {
                    var inputDone = false
                    var outputDone = false

                    var videoTrackIndex = -5

                    inputSurface = InputSurface(encoder.createInputSurface())
                    inputSurface.makeCurrent()
                    //Move to executing state
                    encoder.start()

                    outputSurface = OutputSurface()

                    decoder = prepareDecoder(inputFormat, outputSurface)

                    //Move to executing state
                    decoder.start()

                    while (!outputDone) {
                        if (!inputDone) {

                            val index = extractor.sampleTrackIndex

                            if (index == videoIndex) {
                                val inputBufferIndex =
                                    decoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT)
                                if (inputBufferIndex >= 0) {
                                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                                    val chunkSize = extractor.readSampleData(inputBuffer!!, 0)
                                    when {
                                        chunkSize < 0 -> {

                                            decoder.queueInputBuffer(
                                                inputBufferIndex,
                                                0,
                                                0,
                                                0L,
                                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                            )
                                            inputDone = true
                                        }

                                        else -> {

                                            decoder.queueInputBuffer(
                                                inputBufferIndex,
                                                0,
                                                chunkSize,
                                                extractor.sampleTime,
                                                0
                                            )
                                            extractor.advance()

                                        }
                                    }
                                }

                            } else if (index == -1) { //end of file
                                val inputBufferIndex =
                                    decoder.dequeueInputBuffer(MEDIACODEC_TIMEOUT_DEFAULT)
                                if (inputBufferIndex >= 0) {
                                    decoder.queueInputBuffer(
                                        inputBufferIndex,
                                        0,
                                        0,
                                        0L,
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    inputDone = true
                                }
                            }
                        }

                        var decoderOutputAvailable = true
                        var encoderOutputAvailable = true

                        loop@ while (decoderOutputAvailable || encoderOutputAvailable) {

                            if (!isRunning) {
                                dispose(
                                    videoIndex,
                                    decoder,
                                    encoder,
                                    inputSurface,
                                    outputSurface,
                                    extractor
                                )

                                compressionProgressListener.onProgressCancelled(id)
                                return Result(
                                    id,
                                    success = false,
                                    failureMessage = "The compression has stopped!"
                                )
                            }

                            //Encoder
                            val encoderStatus =
                                encoder.dequeueOutputBuffer(bufferInfo, MEDIACODEC_TIMEOUT_DEFAULT)

                            when {
                                encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderOutputAvailable =
                                    false

                                encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    val newFormat = encoder.outputFormat

                                    // ✅ FIX: Ensure VPS/SPS/PPS are properly parsed and stored in hvcC box
                                    if (newFormat.getString(MediaFormat.KEY_MIME)?.startsWith("video/hevc") == true) {
                                        val csd0 = newFormat.getByteBuffer("csd-0")
                                        if (csd0 != null) {
                                            try {
                                                val data = ByteArray(csd0.remaining())
                                                csd0.get(data)

                                                val vpsList = mutableListOf<ByteArray>()
                                                val spsList = mutableListOf<ByteArray>()
                                                val ppsList = mutableListOf<ByteArray>()

                                                var i = 0
                                                while (i + 4 < data.size) {
                                                    val startCodeLen = when {
                                                        i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                                                                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                                                        i + 2 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                                                                data[i + 2] == 1.toByte() -> 3
                                                        else -> { i++; continue }
                                                    }

                                                    val start = i + startCodeLen
                                                    var next = start
                                                    while (next + 3 < data.size && !(data[next] == 0.toByte() &&
                                                                data[next + 1] == 0.toByte() &&
                                                                ((data[next + 2] == 1.toByte()) ||
                                                                        (next + 3 < data.size && data[next + 2] == 0.toByte() && data[next + 3] == 1.toByte())))
                                                    ) next++

                                                    val nal = data.copyOfRange(start, next)
                                                    val nalType = (nal[0].toInt() shr 1) and 0x3F
                                                    when (nalType) {
                                                        32 -> vpsList.add(nal) // VPS_NUT
                                                        33 -> spsList.add(nal) // SPS_NUT
                                                        34 -> ppsList.add(nal) // PPS_NUT
                                                    }

                                                    i = next
                                                }

                                                if (vpsList.isNotEmpty() || spsList.isNotEmpty() || ppsList.isNotEmpty()) {
                                                    Log.d(
                                                        "Compressor",
                                                        "✅ Extracted VPS/SPS/PPS from csd-0: VPS=${vpsList.size}, SPS=${spsList.size}, PPS=${ppsList.size}"
                                                    )
                                                } else {
                                                    Log.w("Compressor", "⚠️ No VPS/SPS/PPS found in csd-0!")
                                                }

                                            } catch (e: Exception) {
                                                Log.e("Compressor", "Failed to parse HEVC csd-0", e)
                                            }
                                        } else {
                                            Log.w("Compressor", "⚠️ No csd-0 buffer found for HEVC format.")
                                        }
                                    }

                                    if (videoTrackIndex == -5) {
                                        videoTrackIndex = mediaMuxer.addTrack(newFormat, false)
                                    }
                                }

                                encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                                    // ignore this status
                                }

                                encoderStatus < 0 -> throw RuntimeException("unexpected result from encoder.dequeueOutputBuffer: $encoderStatus")
                                else -> {
                                    val encodedData = encoder.getOutputBuffer(encoderStatus)
                                        ?: throw RuntimeException("encoderOutputBuffer $encoderStatus was null")

                                    if (bufferInfo.size > 1) {
                                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                            mediaMuxer.writeSampleData(
                                                videoTrackIndex,
                                                encodedData, bufferInfo, false
                                            )
                                        }

                                    }

                                    outputDone =
                                        bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                                    encoder.releaseOutputBuffer(encoderStatus, false)
                                }
                            }
                            if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) continue@loop

                            //Decoder
                            val decoderStatus =
                                decoder.dequeueOutputBuffer(bufferInfo, MEDIACODEC_TIMEOUT_DEFAULT)
                            when {
                                decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> decoderOutputAvailable =
                                    false

                                decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                                    // ignore this status
                                }

                                decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                    // ignore this status
                                }

                                decoderStatus < 0 -> throw RuntimeException("unexpected result from decoder.dequeueOutputBuffer: $decoderStatus")
                                else -> {
                                    val doRender = bufferInfo.size != 0

                                    decoder.releaseOutputBuffer(decoderStatus, doRender)
                                    if (doRender) {
                                        var errorWait = false
                                        try {
                                            outputSurface.awaitNewImage()
                                        } catch (e: Exception) {
                                            errorWait = true
                                            Log.e(
                                                "Compressor",
                                                e.message ?: "Compression failed at swapping buffer"
                                            )
                                        }

                                        if (!errorWait) {
                                            outputSurface.drawImage()

                                            val presentationTimeUs =
                                                frameIndex * 1_000_000L / sanitizedFrameRate
                                            bufferInfo.presentationTimeUs = presentationTimeUs
                                            inputSurface.setPresentationTime(presentationTimeUs * 1000)

                                            val progressNumerator = min(presentationTimeUs, duration)
                                            val progressDenominator = if (duration > 0) {
                                                duration.toFloat()
                                            } else {
                                                1f
                                            }
                                            compressionProgressListener.onProgressChanged(
                                                id,
                                                progressNumerator.toFloat() / progressDenominator * 100
                                            )

                                            inputSurface.swapBuffers()
                                            frameIndex++
                                        }
                                    }
                                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                                        decoderOutputAvailable = false
                                        encoder.signalEndOfInputStream()
                                    }
                                }
                            }
                        }
                    }

                } catch (exception: Exception) {
                    printException(exception)
                    return Result(id, success = false, failureMessage = exception.message)
                }

                dispose(
                    videoIndex,
                    decoder,
                    encoder,
                    inputSurface,
                    outputSurface,
                    extractor
                )

                processAudio(
                    mediaMuxer = mediaMuxer,
                    bufferInfo = bufferInfo,
                    disableAudio = disableAudio,
                    extractor
                )

                extractor.release()
                try {
                    mediaMuxer.finishMovie()
                } catch (e: Exception) {
                    printException(e)
                }

            } catch (exception: Exception) {
                printException(exception)
            }

            var resultFile = cacheFile

            streamableFile?.let {
                try {
                    val result = StreamableVideo.start(`in` = cacheFile, out = File(it))
                    resultFile = File(it)
                    if (result && cacheFile.exists()) {
                        cacheFile.delete()
                    }

                } catch (e: Exception) {
                    printException(e)
                }
            }
            return Result(
                id,
                success = true,
                failureMessage = null,
                size = resultFile.length(),
                resultFile.path
            )
        }

        return Result(
            id,
            success = false,
            failureMessage = "Something went wrong, please try again"
        )
    }

    private fun processAudio(
        mediaMuxer: MP4Builder,
        bufferInfo: MediaCodec.BufferInfo,
        disableAudio: Boolean,
        extractor: MediaExtractor
    ) {
        val audioIndex = findTrack(extractor, isVideo = false)
        if (audioIndex >= 0 && !disableAudio) {
            extractor.selectTrack(audioIndex)
            val audioFormat = extractor.getTrackFormat(audioIndex)
            val muxerTrackIndex = mediaMuxer.addTrack(audioFormat, true)
            var maxBufferSize = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)

            if (maxBufferSize <= 0) {
                maxBufferSize = 64 * 1024
            }

            var buffer: ByteBuffer = ByteBuffer.allocateDirect(maxBufferSize)
            if (Build.VERSION.SDK_INT >= 28) {
                val size = extractor.sampleSize
                if (size > maxBufferSize) {
                    maxBufferSize = (size + 1024).toInt()
                    buffer = ByteBuffer.allocateDirect(maxBufferSize)
                }
            }
            var inputDone = false
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            while (!inputDone) {
                val index = extractor.sampleTrackIndex
                if (index == audioIndex) {
                    bufferInfo.size = extractor.readSampleData(buffer, 0)

                    if (bufferInfo.size >= 0) {
                        bufferInfo.apply {
                            presentationTimeUs = extractor.sampleTime
                            offset = 0
                            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }
                        mediaMuxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo, true)
                        extractor.advance()

                    } else {
                        bufferInfo.size = 0
                        inputDone = true
                    }
                } else if (index == -1) {
                    inputDone = true
                }
            }
            extractor.unselectTrack(audioIndex)
        }
    }

    private fun prepareEncoder(outputFormat: MediaFormat, hasQTI: Boolean, mimeType: String): MediaCodec {
        // This seems to cause an issue with certain phones
        // val encoderName = MediaCodecList(REGULAR_CODECS).findEncoderForFormat(outputFormat)
        // val encoder: MediaCodec = MediaCodec.createByCodecName(encoderName)
        // Log.i("encoderName", encoder.name)
        // c2.qti.avc.encoder results in a corrupted .mp4 video that does not play in
        // Mac and iphones

        val useSpecificH264Encoder = hasQTI && mimeType == VideoCodec.H264.mimeType
        var encoder = if (useSpecificH264Encoder) {
            MediaCodec.createByCodecName("c2.android.avc.encoder")
        } else {
            MediaCodec.createEncoderByType(mimeType)
        }

        try {
            encoder.configure(
                outputFormat, null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
        } catch (e: Exception) {
            Log.w("Compressor", "Failed to configure encoder with specific codec, falling back to generic encoder", e)
            encoder = MediaCodec.createEncoderByType(mimeType)
            encoder.configure(
                outputFormat, null, null,
                MediaCodec.CONFIGURE_FLAG_ENCODE
            )
        }

        return encoder
    }

    private fun prepareDecoder(
        inputFormat: MediaFormat,
        outputSurface: OutputSurface,
    ): MediaCodec {
        // This seems to cause an issue with certain phones
        // val decoderName =
        //    MediaCodecList(REGULAR_CODECS).findDecoderForFormat(inputFormat)
        // val decoder = MediaCodec.createByCodecName(decoderName)
        // Log.i("decoderName", decoder.name)

        // val decoder = if (hasQTI) {
        // MediaCodec.createByCodecName("c2.android.avc.decoder")
        //} else {

        val decoder = MediaCodec.createDecoderByType(inputFormat.getString(MediaFormat.KEY_MIME)!!)
        //}

        decoder.configure(inputFormat, outputSurface.getSurface(), null, 0)

        return decoder
    }

    private fun dispose(
        videoIndex: Int,
        decoder: MediaCodec,
        encoder: MediaCodec,
        inputSurface: InputSurface,
        outputSurface: OutputSurface,
        extractor: MediaExtractor
    ) {
        extractor.unselectTrack(videoIndex)

        decoder.stop()
        decoder.release()

        encoder.stop()
        encoder.release()

        inputSurface.release()
        outputSurface.release()
    }
}
