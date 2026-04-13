package com.davotoula.lightcompressor.hls

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.davotoula.lightcompressor.muxer.AudioConfig
import com.davotoula.lightcompressor.muxer.EncodedSample
import com.davotoula.lightcompressor.muxer.Mp4SegmentWriter
import com.davotoula.lightcompressor.utils.CompressorUtils
import com.davotoula.lightcompressor.video.InputSurface
import com.davotoula.lightcompressor.video.OutputSurface
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

/**
 * Internal transcoder for HLS preparation.
 * Encodes a single rendition, segmenting output into fMP4 chunks
 * and emitting them via [HlsListener.onSegmentReady].
 */
internal class HlsTranscoder(
    private val context: Context,
    private val srcUri: Uri,
    private val config: HlsConfig,
) {
    @Volatile
    var isCancelled = false

    private val audioReadBuffer = java.nio.ByteBuffer.allocate(AUDIO_BUFFER_SIZE)
    private var lastAudioLimitPtsUs = -1L

    /**
     * Encodes one rendition of the source video.
     *
     * @param rendition target resolution and bitrate
     * @param actualWidth target width (calculated from source aspect ratio)
     * @param actualHeight target height
     * @param listener callbacks for segment delivery and progress
     * @param tempDir directory for temporary segment files
     * @return [RenditionResult] on success, null on failure
     */
    @Suppress(
        "LongMethod",
        "CyclomaticComplexMethod",
        "TooGenericExceptionCaught",
        "NestedBlockDepth",
        "ReturnCount",
        "ThrowsCount",
        "LoopWithTooManyJumpStatements",
        "TooGenericExceptionThrown",
        "MagicNumber",
    )
    fun encodeRendition(
        rendition: Rendition,
        actualWidth: Int,
        actualHeight: Int,
        listener: HlsListener,
        tempDir: File,
    ): RenditionResult? {
        val segmentDurationUs = config.segmentDurationSeconds * 1_000_000L
        val segments = mutableListOf<SegmentInfo>()
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var inputSurface: InputSurface? = null
        var outputSurface: OutputSurface? = null

        try {
            // Setup video extractor
            videoExtractor = MediaExtractor().apply { setDataSource(context, srcUri, null) }
            val videoTrackIndex = CompressorUtils.findTrack(videoExtractor, true)
            if (videoTrackIndex < 0) return null
            val inputFormat = videoExtractor.getTrackFormat(videoTrackIndex)
            videoExtractor.selectTrack(videoTrackIndex)
            val sourceMime = inputFormat.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = inputFormat.getLong(MediaFormat.KEY_DURATION)

            // Setup audio extractor (if enabled)
            var audioConfig: AudioConfig? = null
            var audioFrameDurationUs = 0L
            if (!config.disableAudio) {
                audioExtractor = MediaExtractor().apply { setDataSource(context, srcUri, null) }
                val audioTrackIndex = CompressorUtils.findTrack(audioExtractor, false)
                if (audioTrackIndex >= 0) {
                    audioExtractor.selectTrack(audioTrackIndex)
                    val audioFormat = audioExtractor.getTrackFormat(audioTrackIndex)
                    val csd0 = audioFormat.getByteBuffer("csd-0")
                    if (csd0 != null) {
                        val sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val configBytes = ByteArray(csd0.remaining())
                        csd0.get(configBytes)
                        audioConfig =
                            AudioConfig(
                                codecConfig = configBytes,
                                sampleRate = sampleRate,
                                channelCount = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                                timescale = sampleRate,
                            )
                        audioFrameDurationUs = AAC_SAMPLES_PER_FRAME * 1_000_000L / sampleRate
                    }
                } else {
                    audioExtractor.release()
                    audioExtractor = null
                }
            }

            // Configure encoder
            val encoderFormat =
                MediaFormat
                    .createVideoFormat(
                        config.codec.mimeType,
                        actualWidth,
                        actualHeight,
                    ).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, rendition.bitrateKbps * 1000)
                        setInteger(
                            MediaFormat.KEY_FRAME_RATE,
                            getFrameRate(inputFormat),
                        )
                        setInteger(
                            MediaFormat.KEY_I_FRAME_INTERVAL,
                            config.segmentDurationSeconds,
                        )
                        setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
                        )
                        setInteger(
                            MediaFormat.KEY_BITRATE_MODE,
                            MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                        )
                    }

            encoder = MediaCodec.createEncoderByType(config.codec.mimeType)
            encoder.configure(encoderFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)

            inputSurface = InputSurface(encoder.createInputSurface())
            inputSurface.makeCurrent()
            encoder.start()

            // Configure decoder
            outputSurface = OutputSurface()
            decoder = MediaCodec.createDecoderByType(sourceMime)
            decoder.configure(inputFormat, outputSurface.getSurface(), null, 0)
            decoder.start()

            // Get codec config from encoder output format change
            var codecConfigBytes: ByteArray? = null
            var encoderOutputFormat: MediaFormat? = null

            // Create segment writer (deferred until we have codec config)
            var segmentWriter: Mp4SegmentWriter? = null
            val accumulator = SegmentAccumulator(segmentDurationUs)

            // Drive the encoding pipeline
            val timeoutUs = TIMEOUT_US
            val bufferInfo = MediaCodec.BufferInfo()
            val encoderBufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decoderDone = false
            var encoderDone = false

            while (!encoderDone) {
                if (isCancelled) throw CancellationException()

                // Feed decoder input
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
                                decoder.queueInputBuffer(
                                    inputBufferIndex,
                                    0,
                                    sampleSize,
                                    videoExtractor.sampleTime,
                                    videoExtractor.sampleFlags,
                                )
                                videoExtractor.advance()
                            }
                        }
                    }
                }

                // Collect encoder output and decoder output
                var encoderOutputAvailable = true
                var decoderOutputAvailable = !decoderDone

                while (encoderOutputAvailable || decoderOutputAvailable) {
                    // Encoder output
                    if (encoderOutputAvailable) {
                        val encoderStatus = encoder.dequeueOutputBuffer(encoderBufferInfo, timeoutUs)
                        when {
                            encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                encoderOutputAvailable = false
                            }
                            encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                encoderOutputFormat = encoder.outputFormat
                                // Pixel AVC encoders split SPS into csd-0 and PPS into csd-1.
                                // Both buffers must be merged so the avcC box gets a complete
                                // AVCDecoderConfigurationRecord — see mergeCsdBuffers.
                                val csd0 = encoderOutputFormat.getByteBuffer("csd-0")
                                val csd1 = encoderOutputFormat.getByteBuffer("csd-1")
                                codecConfigBytes = mergeCsdBuffers(csd0, csd1)
                                segmentWriter =
                                    Mp4SegmentWriter(
                                        videoCodecConfig = codecConfigBytes,
                                        videoMimeType = config.codec.mimeType,
                                        videoWidth = actualWidth,
                                        videoHeight = actualHeight,
                                        audioConfig = audioConfig,
                                    )
                                // Emit init segment
                                val initFile = File(tempDir, "init_${rendition.resolution.label}.mp4")
                                FileOutputStream(initFile).use { fos ->
                                    segmentWriter.writeInitSegment(fos)
                                }
                                listener.onSegmentReady(
                                    rendition,
                                    HlsSegment(initFile, 0, 0.0, isInitSegment = true),
                                )
                                if (!initFile.delete()) {
                                    Log.w(TAG, "Failed to delete init temp file: ${initFile.absolutePath}")
                                }
                            }
                            encoderStatus >= 0 -> {
                                val encodedData =
                                    encoder.getOutputBuffer(encoderStatus)
                                        ?: throw RuntimeException("Null encoder output buffer")

                                if (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                    encoder.releaseOutputBuffer(encoderStatus, false)
                                    continue
                                }

                                if (encoderBufferInfo.size > 0) {
                                    val data = ByteArray(encoderBufferInfo.size)
                                    encodedData.position(encoderBufferInfo.offset)
                                    encodedData.get(data)

                                    val frameDurationUs = 1_000_000L / getFrameRate(inputFormat)

                                    accumulator.addVideoSample(
                                        EncodedSample(
                                            data = data,
                                            presentationTimeUs = encoderBufferInfo.presentationTimeUs,
                                            durationUs = frameDurationUs,
                                            flags = encoderBufferInfo.flags,
                                        ),
                                    )

                                    // Check for segment boundary
                                    val flushed = accumulator.flushIfReady()
                                    if (flushed != null && segmentWriter != null) {
                                        emitSegment(
                                            segmentWriter,
                                            flushed,
                                            rendition,
                                            listener,
                                            segments,
                                            tempDir,
                                        )
                                    }

                                    // Report progress
                                    val progress =
                                        min(
                                            (encoderBufferInfo.presentationTimeUs.toFloat() / durationUs) *
                                                PERCENT_MULTIPLIER,
                                            PERCENT_MULTIPLIER,
                                        )
                                    listener.onProgress(rendition, progress)
                                }

                                encoder.releaseOutputBuffer(encoderStatus, false)

                                if (encoderBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    // Flush remaining samples as final segment
                                    val remaining = accumulator.flushRemaining()
                                    if (remaining != null && segmentWriter != null) {
                                        emitSegment(
                                            segmentWriter,
                                            remaining,
                                            rendition,
                                            listener,
                                            segments,
                                            tempDir,
                                        )
                                    }
                                    encoderDone = true
                                    break
                                }
                            }
                        }
                    }

                    // Decoder output
                    if (decoderOutputAvailable && !encoderDone) {
                        val decoderStatus = decoder.dequeueOutputBuffer(bufferInfo, timeoutUs)
                        when {
                            decoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                                decoderOutputAvailable = false
                            }
                            decoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { /* no-op */ }
                            decoderStatus >= 0 -> {
                                val doRender = bufferInfo.size > 0
                                decoder.releaseOutputBuffer(decoderStatus, doRender)
                                if (doRender) {
                                    if (isCancelled) throw CancellationException()
                                    outputSurface.awaitNewImage()
                                    outputSurface.drawImage()
                                    inputSurface.setPresentationTime(
                                        bufferInfo.presentationTimeUs * NS_PER_US,
                                    )
                                    inputSurface.swapBuffers()
                                }
                                if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                    decoderDone = true
                                    encoder.signalEndOfInputStream()
                                }
                            }
                        }
                    }
                }

                // Copy audio samples up to the current video encoding position.
                // Skip when the video PTS hasn't advanced — saves a JNI probe per drain iteration.
                val limitPtsUs = encoderBufferInfo.presentationTimeUs
                if (audioExtractor != null && audioConfig != null && limitPtsUs > lastAudioLimitPtsUs) {
                    copyAudioSamples(
                        audioExtractor,
                        accumulator,
                        limitPtsUs = limitPtsUs,
                        audioFrameDurationUs = audioFrameDurationUs,
                    )
                    lastAudioLimitPtsUs = limitPtsUs
                }
            }

            // Build codec string from the SPS NAL unit inside csd-0. We must not use
            // MediaFormat.KEY_PROFILE/KEY_LEVEL here — MediaCodec exposes those as bit flags
            // that do not match H.264 profile_idc/level_idc, and KEY_LEVEL is often absent.
            val codecString = buildCodecString(codecConfigBytes, config.codec)
            val targetDuration =
                segments.maxOfOrNull { it.durationSeconds }?.toInt()?.plus(1)
                    ?: config.segmentDurationSeconds

            val playlist =
                PlaylistGenerator().buildMediaPlaylist(
                    segments = segments,
                    targetDurationSeconds = targetDuration,
                )

            return RenditionResult(
                rendition = rendition,
                actualWidth = actualWidth,
                actualHeight = actualHeight,
                codecString = codecString,
                playlistFilename = "${rendition.resolution.label}/media.m3u8",
                mediaPlaylist = playlist,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Rendition ${rendition.resolution.label} failed", e)
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { inputSurface?.release() }
            runCatching { outputSurface?.release() }
            runCatching { videoExtractor?.release() }
            runCatching { audioExtractor?.release() }
        }
    }

    @Suppress("MagicNumber")
    private fun emitSegment(
        writer: Mp4SegmentWriter,
        flushed: FlushedSegment,
        rendition: Rendition,
        listener: HlsListener,
        segments: MutableList<SegmentInfo>,
        tempDir: File,
    ) {
        val filename = "segment_%03d.m4s".format(flushed.sequenceNumber - 1)
        val segmentFile = File(tempDir, "${rendition.resolution.label}_$filename")
        FileOutputStream(segmentFile).use { fos ->
            writer.writeMediaSegment(
                videoSamples = flushed.videoSamples,
                audioSamples = flushed.audioSamples,
                sequenceNumber = flushed.sequenceNumber,
                baseDecodeTimeUs = flushed.baseDecodeTimeUs,
                output = fos,
            )
        }
        val durationSeconds = flushed.durationUs / 1_000_000.0
        segments.add(SegmentInfo(filename, durationSeconds))
        listener.onSegmentReady(
            rendition,
            HlsSegment(segmentFile, flushed.sequenceNumber - 1, durationSeconds, isInitSegment = false),
        )
        if (!segmentFile.delete()) {
            Log.w(TAG, "Failed to delete segment temp file: ${segmentFile.absolutePath}")
        }
    }

    private fun copyAudioSamples(
        extractor: MediaExtractor,
        accumulator: SegmentAccumulator,
        limitPtsUs: Long,
        audioFrameDurationUs: Long,
    ) {
        val buffer = audioReadBuffer
        var sampleTime = extractor.sampleTime
        while (sampleTime in 0..limitPtsUs) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            val data = ByteArray(sampleSize)
            buffer.position(0)
            buffer.get(data, 0, sampleSize)
            accumulator.addAudioSample(
                EncodedSample(
                    data = data,
                    presentationTimeUs = sampleTime,
                    durationUs = audioFrameDurationUs,
                    flags = 0,
                ),
            )
            extractor.advance()
            sampleTime = extractor.sampleTime
        }
    }

    private fun getFrameRate(format: MediaFormat): Int =
        try {
            format.getInteger(MediaFormat.KEY_FRAME_RATE)
        } catch (_: Exception) {
            DEFAULT_FRAME_RATE
        }

    private class CancellationException : kotlinx.coroutines.CancellationException("HLS preparation cancelled")

    companion object {
        private const val TAG = "HlsTranscoder"
        private const val TIMEOUT_US = 10_000L
        private const val NS_PER_US = 1000L
        private const val DEFAULT_FRAME_RATE = 30
        private const val AUDIO_BUFFER_SIZE = 64 * 1024
        private const val PERCENT_MULTIPLIER = 100f
        private const val AAC_SAMPLES_PER_FRAME = 1024L
    }
}
