package com.abedelazizshe.lightcompressorlibrary.compressor

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import com.abedelazizshe.lightcompressorlibrary.CompressionProgressListener
import com.abedelazizshe.lightcompressorlibrary.VideoCodec
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.findTrack
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.hasQTI
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.printException
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.setOutputFileParameters
import com.abedelazizshe.lightcompressorlibrary.utils.StreamableVideo
import com.abedelazizshe.lightcompressorlibrary.video.InputSurface
import com.abedelazizshe.lightcompressorlibrary.video.OutputSurface
import com.abedelazizshe.lightcompressorlibrary.video.Result
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.min

/**
 * Unified video transcoder supporting both AVC/H.264 and HEVC/H.265 via [VideoCodec] parameter.
 * Uses native Android MediaCodec and MediaMuxer.
 */
@Suppress("LargeClass")
internal open class Transcoder(
    private val codec: VideoCodec,
    private val context: Context,
    private val srcUri: Uri,
    private val request: Request,
) {
    data class Request(
        val index: Int,
        val width: Int,
        val height: Int,
        val bitrate: Long,
        val destination: File,
        val streamablePath: String?,
        val disableAudio: Boolean,
        val rotation: Int,
        val durationUs: Long,
        val listener: CompressionProgressListener,
    )

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod", "TooGenericExceptionCaught")
    fun transcode(): Result {
        val streamableRequested = request.streamablePath != null
        val parentDir = request.destination.parentFile ?: context.cacheDir
        val muxerOutputFile =
            if (streamableRequested) {
                File.createTempFile("transcode_", ".mp4", parentDir)
            } else {
                request.destination
            }

        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        var decoder: MediaCodec? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null
        var videoExtractor: MediaExtractor? = null
        var audioTrackInfo: AudioTrackInfo? = null
        var encoderStarted = false
        var decoderStarted = false

        return try {
            videoExtractor =
                MediaExtractor().apply {
                    setDataSource(context, srcUri, null)
                }
            val videoTrackIndex = findTrack(videoExtractor, true)
            if (videoTrackIndex < 0) {
                return failure("No video track found in source")
            }
            videoExtractor.selectTrack(videoTrackIndex)
            videoExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inputFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val sourceMime =
                inputFormat.getString(MediaFormat.KEY_MIME)
                    ?: return failure("Source video mime type missing")

            if (!isDecoderAvailable(sourceMime)) {
                return failure(
                    "Unsupported video codec: $sourceMime. This device cannot decode this video format.",
                )
            }

            val encoderFormat =
                MediaFormat.createVideoFormat(
                    codec.mimeType,
                    request.width,
                    request.height,
                )
            setOutputFileParameters(inputFormat, encoderFormat, request.bitrate)
            tuneEncoderFormat(encoderFormat)

            val encoderOrNull =
                createEncoderGuarded(encoderFormat)
                    ?: return failure(
                        if (codec == VideoCodec.H265) MSG_ENCODER_FAILED_H265 else MSG_ENCODER_FAILED_H264,
                    )
            encoder = encoderOrNull
            try {
                inputSurface = InputSurface(encoder.createInputSurface())
                inputSurface.makeCurrent()
            } catch (e: Exception) {
                return failure("$MSG_EGL_SETUP_FAILED_PREFIX${e.message}")
            }
            encoder.start()
            encoderStarted = true

            outputSurface = OutputSurface()
            val decoderOrNull =
                createDecoderGuarded(sourceMime, inputFormat, outputSurface.getSurface())
                    ?: return failure("$MSG_DECODER_FAILED_PREFIX$sourceMime$MSG_DECODER_FAILED_SUFFIX")
            decoder = decoderOrNull
            decoder.start()
            decoderStarted = true

            muxer = MediaMuxer(muxerOutputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(request.rotation)

            audioTrackInfo = if (request.disableAudio) null else prepareAudioTrack(muxer)

            drivePipeline(
                videoExtractor = videoExtractor,
                decoder = decoder,
                outputSurface = outputSurface,
                encoder = encoder,
                inputSurface = inputSurface,
                muxer = muxer,
                audioTrack = audioTrackInfo,
                sourceMime = sourceMime,
            )

            try {
                muxer.stop()
            } catch (stopError: Exception) {
                Log.w(TAG, "Failed to stop muxer cleanly: ${stopError.message}")
            }
            try {
                muxer.release()
            } catch (releaseError: Exception) {
                Log.w(TAG, "Failed to release muxer cleanly: ${releaseError.message}")
            }
            muxer = null

            finalizeOutput(muxerOutputFile, streamableRequested)
        } catch (_: CancellationException) {
            request.listener.onProgressCancelled(request.index)
            failure("Compression cancelled")
        } catch (
            @Suppress("TooGenericExceptionCaught") throwable: Throwable,
        ) {
            printException(Exception(throwable))
            failure(throwable.message ?: "${codec.name} transcoding failed")
        } finally {
            try {
                videoExtractor?.release()
            } catch (_: Exception) {
            }
            audioTrackInfo?.let {
                try {
                    it.extractor.release()
                } catch (_: Exception) {
                }
            }
            if (decoderStarted) {
                try {
                    decoder?.stop()
                } catch (_: Exception) {
                }
            }
            try {
                decoder?.release()
            } catch (_: Exception) {
            }
            if (encoderStarted) {
                try {
                    encoder?.stop()
                } catch (_: Exception) {
                }
            }
            try {
                encoder?.release()
            } catch (_: Exception) {
            }
            try {
                inputSurface?.release()
            } catch (_: Exception) {
            }
            try {
                outputSurface?.release()
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
            @Suppress("ComplexCondition")
            if (streamableRequested &&
                muxerOutputFile != request.destination &&
                muxerOutputFile.exists() &&
                !muxerOutputFile.delete()
            ) {
                Log.w(TAG, "Failed to delete temporary muxer file: ${muxerOutputFile.absolutePath}")
            }
        }
    }

    private data class AudioTrackInfo(
        val extractor: MediaExtractor,
        val muxerTrackIndex: Int,
        val format: MediaFormat,
    )

    @Suppress("TooGenericExceptionCaught")
    private fun prepareAudioTrack(muxer: MediaMuxer): AudioTrackInfo? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, srcUri, null)
            val audioTrackIndex = findTrack(extractor, false)
            if (audioTrackIndex < 0) {
                extractor.release()
                null
            } else {
                extractor.selectTrack(audioTrackIndex)
                val audioFormat = extractor.getTrackFormat(audioTrackIndex)
                val muxerTrackIndex = muxer.addTrack(audioFormat)
                AudioTrackInfo(
                    extractor = extractor,
                    muxerTrackIndex = muxerTrackIndex,
                    format = audioFormat,
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio track preparation failed: ${e.message}")
            try {
                extractor.release()
            } catch (_: Exception) {
            }
            null
        }
    }

    @Suppress(
        "ThrowsCount",
        "CyclomaticComplexMethod",
        "LongMethod",
        "NestedBlockDepth",
        "LongParameterList",
        "TooGenericExceptionThrown",
    )
    private fun drivePipeline(
        videoExtractor: MediaExtractor,
        decoder: MediaCodec,
        outputSurface: OutputSurface,
        encoder: MediaCodec,
        inputSurface: InputSurface,
        muxer: MediaMuxer,
        audioTrack: AudioTrackInfo?,
        sourceMime: String,
    ) {
        val timeoutUs = TIMEOUT_US
        val bufferInfo = MediaCodec.BufferInfo()
        val encoderBufferInfo = MediaCodec.BufferInfo()

        var videoMuxerTrackIndex = -1
        var muxerStarted = false

        var inputDone = false
        var decoderDone = false
        var encoderDone = false

        while (!encoderDone) {
            if (!inputDone) {
                val inputBufferIndex = decoder.dequeueInputBuffer(timeoutUs)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                    if (inputBuffer == null) {
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                        )
                    } else {
                        val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val sampleTimeUs = videoExtractor.sampleTime
                            val sampleFlags = videoExtractor.sampleFlags
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                sampleTimeUs,
                                sampleFlags,
                            )
                            videoExtractor.advance()
                        }
                    }
                }
            }

            var encoderOutputAvailable = true
            var decoderOutputAvailable = !decoderDone

            @Suppress("LoopWithTooManyJumpStatements")
            loop@ while (encoderOutputAvailable || decoderOutputAvailable) {
                var encoderStatus = MediaCodec.INFO_TRY_AGAIN_LATER

                if (encoderOutputAvailable) {
                    encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, timeoutUs)
                    when {
                        encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            encoderOutputAvailable = false
                        }

                        encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val newFormat = encoder.outputFormat
                            videoMuxerTrackIndex = muxer.addTrack(newFormat)
                            if (!muxerStarted) {
                                muxer.start()
                                muxerStarted = true
                                audioTrack?.extractor?.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                            }
                        }

                        encoderStatus >= 0 -> {
                            val encodedData = encoder.getOutputBuffer(encoderStatus)
                            if (encodedData == null) {
                                throw RuntimeException(
                                    "Encoder produced null output buffer " +
                                        "(index=$encoderStatus). " +
                                        "The device encoder may be in a bad state.",
                                )
                            }

                            if (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                encoderBufferInfo.size = 0
                            }

                            if (encoderBufferInfo.size > 0 && muxerStarted) {
                                encodedData.position(encoderBufferInfo.offset)
                                encodedData.limit(encoderBufferInfo.offset + encoderBufferInfo.size)
                                muxer.writeSampleData(videoMuxerTrackIndex, encodedData, encoderBufferInfo)
                            }

                            encoder.releaseOutputBuffer(encoderStatus, false)

                            if (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encoderDone = true
                                break@loop
                            }
                        }

                        else -> {
                            throw RuntimeException(
                                "Unexpected encoder status $encoderStatus during ${codec.name} transcoding",
                            )
                        }
                    }
                }
                if (encoderStatus != MediaCodec.INFO_TRY_AGAIN_LATER) continue@loop

                if (decoderOutputAvailable) {
                    val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            decoderOutputAvailable = false
                        }

                        decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            // No-op for surface decoding.
                        }

                        decoderStatus < 0 -> {
                            throw RuntimeException(
                                "Unexpected decoder status $decoderStatus while decoding $sourceMime",
                            )
                        }

                        else -> {
                            val doRender = bufferInfo.size > 0
                            decoder.releaseOutputBuffer(decoderStatus, doRender)
                            if (doRender) {
                                if (!Compressor.isRunning) {
                                    throw CancellationException()
                                }
                                outputSurface.awaitNewImage()
                                outputSurface.drawImage()

                                val presentationTimeUs = bufferInfo.presentationTimeUs
                                inputSurface.setPresentationTime(presentationTimeUs * NS_PER_US)
                                inputSurface.swapBuffers()

                                reportProgress(presentationTimeUs)
                            }

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                decoderDone = true
                                encoder.signalEndOfInputStream()
                            }
                        }
                    }
                }
            }
        }

        if (audioTrack != null && muxerStarted) {
            copyAudioTrack(audioTrack, muxer)
        }
    }

    private fun copyAudioTrack(
        trackInfo: AudioTrackInfo,
        muxer: MediaMuxer,
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        val extractor = trackInfo.extractor
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val initialCapacity =
            if (trackInfo.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                trackInfo.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                AUDIO_BUFFER_SIZE
            }
        var buffer = ByteBuffer.allocateDirect(initialCapacity.coerceAtLeast(AUDIO_BUFFER_SIZE))

        @Suppress("LoopWithTooManyJumpStatements")
        while (true) {
            buffer.clear()
            var sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                break
            }
            if (sampleSize > buffer.capacity()) {
                val requiredCapacity = sampleSize.coerceAtLeast(AUDIO_BUFFER_SIZE)
                buffer = ByteBuffer.allocateDirect(requiredCapacity)
                buffer.clear()
                sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) {
                    break
                }
            }
            val sampleTime = extractor.sampleTime
            if (sampleTime < 0) {
                break
            }
            bufferInfo.apply {
                offset = 0
                size = sampleSize
                presentationTimeUs = sampleTime
                flags = convertExtractorFlagsToCodecFlags(extractor.sampleFlags)
            }
            buffer.position(0)
            buffer.limit(sampleSize)
            muxer.writeSampleData(trackInfo.muxerTrackIndex, buffer, bufferInfo)
            extractor.advance()
        }
    }

    @Suppress("ReturnCount")
    internal open fun finalizeOutput(
        muxerFile: File,
        streamableRequested: Boolean,
    ): Result {
        val destination = request.destination
        if (!streamableRequested) {
            if (muxerFile != destination) {
                if (destination.exists() && !destination.delete()) {
                    Log.w(TAG, "Unable to delete existing destination file ${destination.absolutePath}")
                }
                if (!muxerFile.renameTo(destination)) {
                    muxerFile.copyTo(destination, overwrite = true)
                }
            }
            return Result(
                request.index,
                success = true,
                failureMessage = null,
                size = destination.length(),
                path = destination.path,
            )
        }

        if (destination.exists() && !destination.delete()) {
            Log.w(TAG, "Unable to delete existing destination file ${destination.absolutePath}")
        }

        val fastStartApplied =
            try {
                StreamableVideo.start(muxerFile, destination)
            } catch (ioe: IOException) {
                Log.w(TAG, "Fast-start conversion failed: ${ioe.message}")
                false
            }

        if (!fastStartApplied) {
            muxerFile.copyTo(destination, overwrite = true)
        } else if (muxerFile.exists() && !muxerFile.delete()) {
            Log.w(TAG, "Failed to delete muxer file: ${muxerFile.absolutePath}")
        }

        val streamablePath = request.streamablePath
        if (streamablePath != null) {
            val streamableFile = File(streamablePath)
            val converted =
                try {
                    StreamableVideo.start(destination, streamableFile)
                } catch (ioe: IOException) {
                    Log.w(TAG, "Secondary fast-start copy failed: ${ioe.message}")
                    false
                }
            if (converted && destination.exists()) {
                if (!destination.delete()) {
                    Log.w(TAG, "Failed to delete destination file: ${destination.absolutePath}")
                }
                return Result(
                    request.index,
                    success = true,
                    failureMessage = null,
                    size = streamableFile.length(),
                    path = streamableFile.path,
                )
            }
        }

        return Result(
            request.index,
            success = true,
            failureMessage = null,
            size = destination.length(),
            path = destination.path,
        )
    }

    protected open fun reportProgress(presentationTimeUs: Long) {
        val listener = request.listener
        val duration = request.durationUs
        val progressNumerator = min(presentationTimeUs, duration)
        val progressDenominator = if (duration > 0) duration.toFloat() else 1f
        val progress = progressNumerator.toFloat() / progressDenominator * PERCENT_MULTIPLIER
        listener.onProgressChanged(request.index, progress)
    }

    /**
     * Wraps decoder creation and configuration in a try-catch, returning null on failure.
     * Override in tests to simulate decoder creation failure.
     */
    @Suppress("TooGenericExceptionCaught")
    internal open fun createDecoderGuarded(
        mime: String,
        format: MediaFormat,
        surface: Surface?,
    ): MediaCodec? =
        try {
            val dec = MediaCodec.createDecoderByType(mime)
            dec.configure(format, surface, null, 0)
            dec
        } catch (e: Exception) {
            Log.w(TAG, "Decoder creation/configuration failed for $mime: ${e.message}", e)
            null
        }

    /**
     * Wraps [createEncoder] in a try-catch, returning null on failure.
     * Override in tests to simulate encoder creation failure.
     */
    @Suppress("TooGenericExceptionCaught")
    internal open fun createEncoderGuarded(outputFormat: MediaFormat): MediaCodec? =
        try {
            createEncoder(outputFormat)
        } catch (e: Exception) {
            Log.w(TAG, "Encoder creation failed: ${e.message}", e)
            null
        }

    /**
     * Creates and configures the encoder for the given format.
     * H264: uses QTI-specific fallback logic.
     * H265: uses simple createEncoderByType.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun createEncoder(outputFormat: MediaFormat): MediaCodec =
        when (codec) {
            VideoCodec.H264 -> {
                val hasQTI = hasQTI()

                // On QTI devices, use c2.android.avc.encoder to avoid compatibility issues
                var encoder =
                    if (hasQTI) {
                        try {
                            MediaCodec.createByCodecName("c2.android.avc.encoder")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to create c2.android.avc.encoder, falling back to generic", e)
                            MediaCodec.createEncoderByType(VideoCodec.H264.mimeType)
                        }
                    } else {
                        MediaCodec.createEncoderByType(VideoCodec.H264.mimeType)
                    }

                try {
                    encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to configure encoder, falling back to generic encoder", e)
                    encoder = MediaCodec.createEncoderByType(VideoCodec.H264.mimeType)
                    encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }

                encoder
            }

            VideoCodec.H265 -> {
                val encoder = MediaCodec.createEncoderByType(VideoCodec.H265.mimeType)
                encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                encoder
            }
        }

    /**
     * Tunes the encoder format with codec-specific profile/level and vendor hints.
     * H264: sets AVC High Profile.
     * H265: sets HEVC Main Profile + vendor B-frame hints.
     */
    internal open fun tuneEncoderFormat(encoderFormat: MediaFormat) {
        when (codec) {
            VideoCodec.H264 -> {
                // Set AVC High Profile if supported
                if (codecSupportsProfile(VideoCodec.H264.mimeType, AVC_PROFILE_HIGH, AVC_LEVEL_4)) {
                    encoderFormat.setInteger(MediaFormat.KEY_PROFILE, AVC_PROFILE_HIGH)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        encoderFormat.setInteger(MediaFormat.KEY_LEVEL, AVC_LEVEL_4)
                    }
                }
            }

            VideoCodec.H265 -> {
                if (codecSupportsProfile(VideoCodec.H265.mimeType, HEVC_PROFILE_MAIN, HEVC_LEVEL_4)) {
                    encoderFormat.setInteger(MediaFormat.KEY_PROFILE, HEVC_PROFILE_MAIN)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        encoderFormat.setInteger(MediaFormat.KEY_LEVEL, HEVC_LEVEL_4)
                    }
                }

                trySetVendorKey(encoderFormat, "vendor.qti-ext-enc-bframes.num-bframes", 2)
                trySetVendorKey(encoderFormat, "video-encoder.max-bframes", 2)
            }
        }
    }

    private val codecInfos by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    }

    internal open fun isDecoderAvailable(mime: String): Boolean =
        codecInfos.any { codecInfo ->
            !codecInfo.isEncoder &&
                codecInfo.supportedTypes.any {
                    it.equals(mime, ignoreCase = true)
                }
        }

    internal open fun codecSupportsProfile(
        mime: String,
        profile: Int,
        level: Int,
    ): Boolean {
        codecInfos.filter { it.isEncoder }.forEach { codecInfo ->
            val type = codecInfo.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: return@forEach
            val profileLevel = codecInfo.getCapabilitiesForType(type).profileLevels
            if (profileLevel.any { it.profile == profile && it.level >= level }) {
                return true
            }
        }
        return false
    }

    internal open fun trySetVendorKey(
        format: MediaFormat,
        key: String,
        value: Int,
    ) {
        try {
            format.setInteger(key, value)
            Log.d(TAG, "Applied vendor key $key=$value")
        } catch (_: Throwable) {
            Log.d(TAG, "Vendor key $key not supported")
        }
    }

    private fun convertExtractorFlagsToCodecFlags(extractorFlags: Int): Int {
        var codecFlags = 0
        if (extractorFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0
        ) {
            codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
        }
        return codecFlags
    }

    private fun failure(message: String): Result =
        Result(
            request.index,
            success = false,
            failureMessage = message,
        )

    private class CancellationException : RuntimeException()

    @Suppress("ktlint:standard:property-naming", "VariableNaming")
    private val TAG = "Transcoder-${codec.name}"

    internal companion object {
        private const val TIMEOUT_US = 100L
        private const val NS_PER_US = 1000L
        private const val AUDIO_BUFFER_SIZE = 64 * 1024
        private const val PERCENT_MULTIPLIER = 100f
        private const val AVC_PROFILE_HIGH = MediaCodecInfo.CodecProfileLevel.AVCProfileHigh
        private const val AVC_LEVEL_4 = MediaCodecInfo.CodecProfileLevel.AVCLevel4
        private const val HEVC_PROFILE_MAIN = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        private const val HEVC_LEVEL_4 = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4

        internal const val MSG_ENCODER_FAILED_H265 = "Failed to create H265 encoder. Try using H.264 instead."
        internal const val MSG_ENCODER_FAILED_H264 =
            "Failed to create H264 encoder. The device may not support this encoding format."
        internal const val MSG_DECODER_FAILED_PREFIX = "Failed to create decoder for "
        internal const val MSG_DECODER_FAILED_SUFFIX = ". The device may not have a free decoder instance."
        internal const val MSG_EGL_SETUP_FAILED_PREFIX = "EGL/OpenGL setup failed: "
    }
}
