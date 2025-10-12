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
import com.abedelazizshe.lightcompressorlibrary.CompressionProgressListener
import com.abedelazizshe.lightcompressorlibrary.VideoCodec
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils.findTrack
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

internal open class HevcTranscoder(
    private val context: Context,
    private val srcUri: Uri,
    private val request: Request
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
        val listener: CompressionProgressListener
    )

    fun transcode(): Result {
        val streamableRequested = request.streamablePath != null
        val parentDir = request.destination.parentFile ?: context.cacheDir
        val muxerOutputFile = if (streamableRequested) {
            File.createTempFile("hevc_transcode_", ".mp4", parentDir)
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

        return try {
            videoExtractor = MediaExtractor().apply {
                setDataSource(context, srcUri, null)
            }
            val videoTrackIndex = findTrack(videoExtractor, true)
            if (videoTrackIndex < 0) {
                return failure("No video track found in source")
            }
            videoExtractor.selectTrack(videoTrackIndex)
            videoExtractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val inputFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            val sourceMime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return failure("Source video mime type missing")

            val encoderFormat = MediaFormat.createVideoFormat(
                VideoCodec.H265.mimeType,
                request.width,
                request.height
            )
            setOutputFileParameters(inputFormat, encoderFormat, request.bitrate)
            tuneHevcEncoderFormat(encoderFormat)

            encoder = MediaCodec.createEncoderByType(VideoCodec.H265.mimeType)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()

            outputSurface = OutputSurface()
            decoder = MediaCodec.createDecoderByType(sourceMime)
            decoder.configure(inputFormat, outputSurface.getSurface(), null, 0)
            decoder.start()

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
                audioTrack = audioTrackInfo
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
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) {
                request.listener.onProgressCancelled(request.index)
                return failure("Compression cancelled")
            }
            printException(Exception(throwable))
            failure(throwable.message ?: "HEVC transcoding failed")
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
            try {
                decoder?.stop()
            } catch (_: Exception) {
            }
            try {
                decoder?.release()
            } catch (_: Exception) {
            }
            try {
                encoder?.stop()
            } catch (_: Exception) {
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
            if (streamableRequested && muxerOutputFile != request.destination && muxerOutputFile.exists()) {
                muxerOutputFile.delete()
            }
        }
    }

    private data class AudioTrackInfo(
        val extractor: MediaExtractor,
        val muxerTrackIndex: Int,
        val format: MediaFormat
    )

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
                    format = audioFormat
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

    private fun drivePipeline(
        videoExtractor: MediaExtractor,
        decoder: MediaCodec,
        outputSurface: OutputSurface,
        encoder: MediaCodec,
        inputSurface: InputSurface,
        muxer: MediaMuxer,
        audioTrack: AudioTrackInfo?
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
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    } else {
                        val sampleSize = videoExtractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
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
                                sampleFlags
                            )
                            videoExtractor.advance()
                        }
                    }
                }
            }

            var encoderOutputAvailable = true
            var decoderOutputAvailable = !decoderDone

            loop@ while (encoderOutputAvailable || decoderOutputAvailable) {
                var outputHandled = false

                if (encoderOutputAvailable) {
                    val encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, timeoutUs)
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
                                throw RuntimeException("Encoder output buffer $encoderStatus was null")
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
                            throw RuntimeException("Unexpected encoder status: $encoderStatus")
                        }
                    }
                    outputHandled = true
                }

                if (decoderOutputAvailable) {
                    val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                    when {
                        decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            decoderOutputAvailable = false
                        }

                        decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ||
                            decoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> {
                            // No-op for surface decoding.
                        }

                        decoderStatus < 0 -> {
                            throw RuntimeException("Unexpected decoder status: $decoderStatus")
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
                                inputSurface.setPresentationTime(presentationTimeUs * 1000)
                                inputSurface.swapBuffers()

                                reportProgress(presentationTimeUs)
                            }

                            if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                decoderDone = true
                                encoder.signalEndOfInputStream()
                            }
                        }
                    }
                    outputHandled = true
                }

                if (!outputHandled) {
                    break@loop
                }
            }
        }

        if (audioTrack != null && muxerStarted) {
            copyAudioTrack(audioTrack, muxer)
        }
    }

    private fun copyAudioTrack(
        trackInfo: AudioTrackInfo,
        muxer: MediaMuxer
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        val extractor = trackInfo.extractor
        extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val initialCapacity = if (trackInfo.format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
            trackInfo.format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
        } else {
            64 * 1024
        }
        var buffer = ByteBuffer.allocateDirect(initialCapacity.coerceAtLeast(64 * 1024))

        while (true) {
            buffer.clear()
            var sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) {
                break
            }
            if (sampleSize > buffer.capacity()) {
                val requiredCapacity = sampleSize.coerceAtLeast(64 * 1024)
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

    internal open fun finalizeOutput(muxerFile: File, streamableRequested: Boolean): Result {
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
                path = destination.path
            )
        }

        if (destination.exists() && !destination.delete()) {
            Log.w(TAG, "Unable to delete existing destination file ${destination.absolutePath}")
        }

        val fastStartApplied = try {
            StreamableVideo.start(muxerFile, destination)
        } catch (ioe: IOException) {
            Log.w(TAG, "Fast-start conversion failed: ${ioe.message}")
            false
        }

        if (!fastStartApplied) {
            muxerFile.copyTo(destination, overwrite = true)
        } else if (muxerFile.exists()) {
            muxerFile.delete()
        }

        val streamablePath = request.streamablePath
        if (streamablePath != null) {
            val streamableFile = File(streamablePath)
            val converted = try {
                StreamableVideo.start(destination, streamableFile)
            } catch (ioe: IOException) {
                Log.w(TAG, "Secondary fast-start copy failed: ${ioe.message}")
                false
            }
            if (converted && destination.exists()) {
                destination.delete()
                return Result(
                    request.index,
                    success = true,
                    failureMessage = null,
                    size = streamableFile.length(),
                    path = streamableFile.path
                )
            }
        }

        return Result(
            request.index,
            success = true,
            failureMessage = null,
            size = destination.length(),
            path = destination.path
        )
    }

    protected open fun reportProgress(presentationTimeUs: Long) {
        val listener = request.listener
        val duration = request.durationUs
        val progressNumerator = min(presentationTimeUs, duration)
        val progressDenominator = if (duration > 0) duration.toFloat() else 1f
        val progress = progressNumerator.toFloat() / progressDenominator * 100f
        listener.onProgressChanged(request.index, progress)
    }

    internal open fun tuneHevcEncoderFormat(encoderFormat: MediaFormat) {
        if (codecSupportsProfile(VideoCodec.H265.mimeType, HEVC_PROFILE_MAIN, HEVC_LEVEL_4)) {
            encoderFormat.setInteger(MediaFormat.KEY_PROFILE, HEVC_PROFILE_MAIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                encoderFormat.setInteger(MediaFormat.KEY_LEVEL, HEVC_LEVEL_4)
            }
        }

        trySetVendorKey(encoderFormat, "vendor.qti-ext-enc-bframes.num-bframes", 2)
        trySetVendorKey(encoderFormat, "video-encoder.max-bframes", 2)
    }

    internal open fun codecSupportsProfile(mime: String, profile: Int, level: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return false
        }
        val codecInfos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        codecInfos.filter { it.isEncoder }.forEach { codecInfo ->
            val type = codecInfo.supportedTypes.firstOrNull { it.equals(mime, ignoreCase = true) } ?: return@forEach
            val profileLevel = codecInfo.getCapabilitiesForType(type).profileLevels
            if (profileLevel.any { it.profile == profile && it.level >= level }) {
                return true
            }
        }
        return false
    }

    internal open fun trySetVendorKey(format: MediaFormat, key: String, value: Int) {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (extractorFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
                codecFlags = codecFlags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
            }
        }
        return codecFlags
    }

    private fun failure(message: String): Result {
        return Result(
            request.index,
            success = false,
            failureMessage = message
        )
    }

    private class CancellationException : RuntimeException()

    companion object {
        private const val TAG = "HevcTranscoder"
        private const val TIMEOUT_US = 10000L
        private const val HEVC_PROFILE_MAIN = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain
        private const val HEVC_LEVEL_4 = MediaCodecInfo.CodecProfileLevel.HEVCMainTierLevel4
    }
}
