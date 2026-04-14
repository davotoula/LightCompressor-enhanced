package com.davotoula.lightcompressor.hls

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.davotoula.lightcompressor.muxer.AudioConfig
import com.davotoula.lightcompressor.muxer.EncodedSample
import com.davotoula.lightcompressor.utils.CompressorUtils
import java.nio.ByteBuffer

/**
 * All audio samples for a source video, extracted once and reused across renditions.
 *
 * Audio is AAC pass-through (not re-encoded), so extracting it once per HLS preparation
 * instead of per rendition eliminates redundant I/O and allocations.
 */
internal data class PreExtractedAudio(
    val config: AudioConfig,
    val samples: List<EncodedSample>,
)

/**
 * Extract every audio sample from [uri] into memory. Returns null if the source has no
 * audio track or the track is missing a codec-specific data header.
 */
@Suppress("TooGenericExceptionCaught", "ReturnCount", "MagicNumber")
internal fun extractAudioSamples(
    context: Context,
    uri: Uri,
): PreExtractedAudio? {
    val extractor =
        MediaExtractor().apply { setDataSource(context, uri, null) }
    try {
        val trackIndex = CompressorUtils.findTrack(extractor, false)
        if (trackIndex < 0) {
            Log.d(TAG, "No audio track found in source")
            return null
        }
        extractor.selectTrack(trackIndex)
        val format = extractor.getTrackFormat(trackIndex)
        val csd0 = format.getByteBuffer("csd-0")
        if (csd0 == null) {
            Log.d(TAG, "Audio track has no csd-0, skipping audio")
            return null
        }
        val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        val configBytes = ByteArray(csd0.remaining())
        csd0.get(configBytes)
        val audioConfig =
            AudioConfig(
                codecConfig = configBytes,
                sampleRate = sampleRate,
                channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                timescale = sampleRate,
            )
        val frameDurationUs = AAC_SAMPLES_PER_FRAME * 1_000_000L / sampleRate
        Log.d(TAG, "Audio track found: ${sampleRate}Hz, ${audioConfig.channelCount}ch")

        val samples = mutableListOf<EncodedSample>()
        val buffer = ByteBuffer.allocate(AUDIO_BUFFER_SIZE)
        var sampleTime = extractor.sampleTime
        while (sampleTime >= 0) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            val data = ByteArray(sampleSize)
            buffer.position(0)
            buffer.get(data, 0, sampleSize)
            samples.add(
                EncodedSample(
                    data = data,
                    presentationTimeUs = sampleTime,
                    durationUs = frameDurationUs,
                    flags = 0,
                ),
            )
            extractor.advance()
            sampleTime = extractor.sampleTime
        }
        return PreExtractedAudio(audioConfig, samples)
    } catch (e: Exception) {
        Log.w(TAG, "Failed to pre-extract audio samples: ${e.message}")
        return null
    } finally {
        runCatching { extractor.release() }
    }
}

private const val TAG = "AudioExtractor"
private const val AUDIO_BUFFER_SIZE = 64 * 1024
private const val AAC_SAMPLES_PER_FRAME = 1024L
