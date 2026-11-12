package com.davotoula.lightcompressor.sanitizer

import android.content.ContentResolver
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import com.davotoula.lightcompressor.describe
import com.davotoula.lightcompressor.utils.NegativeTime
import com.davotoula.lightcompressor.utils.copySamples
import com.davotoula.lightcompressor.utils.deleteOrWarn
import com.davotoula.lightcompressor.utils.finishFastStart
import com.davotoula.lightcompressor.utils.sampleBufferSize
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import com.davotoula.lightcompressor.utils.isFastStart as fileIsFastStart

/** Outcome of [Mp4Sanitizer.sanitize]: [file] is [Mp4Sanitizer.sanitize]'s `output`. */
data class SanitizeResult(
    val file: File,
    /** `moov` precedes `mdat` in [file]. False means the fast-start pass failed and the plain remux was kept. */
    val fastStart: Boolean,
)

/** Strips container metadata from an MP4 or M4A by remuxing its samples, without decoding or re-encoding. */
object Mp4Sanitizer {
    private const val TAG = "Mp4Sanitizer"

    // Large enough for 4K keyframes on API < 28, where copySamples can't grow the buffer.
    private const val MIN_SAMPLE_BUFFER_SIZE = 8 * 1024 * 1024
    private const val MUX_TEMP_SUFFIX = ".mux.tmp"

    private val MP4_MIMES =
        setOf(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MediaFormat.MIMETYPE_VIDEO_MPEG4,
            MediaFormat.MIMETYPE_VIDEO_H263,
            MediaFormat.MIMETYPE_AUDIO_AAC,
            MediaFormat.MIMETYPE_AUDIO_AMR_NB,
            MediaFormat.MIMETYPE_AUDIO_AMR_WB,
        )

    /**
     * Rewrites the MP4 or M4A at [input] into [output] with only its media samples.
     *
     * Every audio and video track `MediaExtractor` exposes is copied sample by sample into a
     * fresh MPEG-4 container, so `udta`, `meta`/`ilst`, `uuid` (XMP) boxes and per-track user
     * data do not carry over; the rotation hint is read from the source and reapplied. Other
     * tracks (timed metadata such as a camera's `mett` track, text) are dropped with their
     * samples. The output is then fast-started so `moov` precedes `mdat`. Codec data is
     * untouched: no decode, no re-encode. Audio encoder priming (samples before an edit list's
     * start) is kept behind an edit list; video samples before it mean a stream-copy trim, which
     * is refused.
     *
     * Residual: `MediaMuxer` writes its own creation time, and a `meta` box holding
     * `com.android.version` (the OS version); metadata carried inside the bitstream (H.264 SEI
     * user data) is not removed.
     *
     * @throws IOException when the source cannot be read, has no audio or video track, has an
     *   audio or video track that cannot be written to an MPEG-4 container (see
     *   [isMuxableIntoMp4]), is a stream-copy trim whose edit list starts mid-GOP (the remux
     *   would show the trimmed-out frames), or the remux fails; [java.io.InterruptedIOException]
     *   when the calling thread is interrupted. [output] does not exist after a throw. [output]
     *   must not be the [input] file, and only one call at a time may write a given [output]
     *   (it and `<output>.mux.tmp` are deleted on failure).
     */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun sanitize(
        context: Context,
        input: Uri,
        output: File,
    ): SanitizeResult = sanitize(context, input, output, MediaExtractor())

    /** [sanitize] with the extractor injected, so tests can stand in for the stub `MediaExtractor`. */
    @Suppress("TooGenericExceptionCaught")
    internal fun sanitize(
        context: Context,
        input: Uri,
        output: File,
        extractor: MediaExtractor,
    ): SanitizeResult {
        // Checked first: cleaning up after a failure deletes output, which here would be the source.
        requireOutputIsNotInput(input, output, extractor)
        val muxerFile = File(output.path + MUX_TEMP_SUFFIX)
        var succeeded = false
        try {
            remux(context, input, muxerFile, extractor)
            val fastStart = finishFastStart(muxerFile, output)
            // An interrupt during the fast-start pass surfaces as a failed conversion (ClosedByInterruptException),
            // which finishFastStart treats as "keep the plain remux"; report it as the interrupt it was.
            if (Thread.currentThread().isInterrupted) throw InterruptedIOException("Sanitize interrupted")
            if (!fastStart) {
                Log.w(TAG, "${output.path} is not fast-start; keeping the plain remux")
            }
            succeeded = true
            return SanitizeResult(output, fastStart)
        } catch (e: Exception) {
            throw e as? IOException ?: IOException("Sanitizing failed: ${e.describe()}", e)
        } finally {
            // In finally rather than the catch, so an Error (e.g. out of direct-buffer memory) also leaves no output.
            // Both are usually gone already; a leftover is logged, since throwing here would hide the real failure.
            muxerFile.deleteOrWarn(TAG)
            if (!succeeded) output.deleteOrWarn(TAG)
        }
    }

    /**
     * True when the file's top-level `moov` atom precedes its first `mdat`. Reads atom headers only.
     * False when either atom is missing, an atom size is corrupt, or the file cannot be read.
     */
    @JvmStatic
    fun isFastStart(file: File): Boolean = fileIsFastStart(file)

    /** Mime types `MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4` accepts. */
    @JvmStatic
    fun isMuxableIntoMp4(mime: String): Boolean = mime in MP4_MIMES

    private fun remux(
        context: Context,
        input: Uri,
        muxerFile: File,
        extractor: MediaExtractor,
    ) {
        try {
            extractor.setDataSource(context, input, null)
            val tracks = mediaTracks(extractor)
            val rotation = readRotation(context, input)
            val muxer = MediaMuxer(muxerFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                // Indexed by extractor track; only selected tracks produce samples, so dropped ones are never read.
                val muxerTracks = IntArray(extractor.trackCount)
                tracks.forEach { (track, format) ->
                    extractor.selectTrack(track)
                    muxerTracks[track] = muxer.addTrack(format)
                }
                if (rotation != 0) muxer.setOrientationHint(rotation)
                muxer.start()
                val written =
                    copySamples(
                        extractor,
                        muxer,
                        muxerTracks,
                        sampleBufferSize(tracks.values, MIN_SAMPLE_BUFFER_SIZE),
                    ) { track ->
                        if (tracks
                                .getValue(
                                    track,
                                ).mime()
                                .startsWith("video/")
                        ) {
                            NegativeTime.REJECT
                        } else {
                            NegativeTime.COPY
                        }
                    }
                // MediaMuxer silently leaves out a track with no samples: a failed sanitize, not a smaller file.
                tracks.keys.firstOrNull { written[it] == 0 }?.let { track ->
                    throw IOException("Track $track (${tracks.getValue(track).mime()}) has no samples to copy")
                }
                muxer.stop()
            } finally {
                // Also stops a muxer that a failed copy left started; the half-written file is deleted anyway.
                runCatching { muxer.release() }
            }
        } finally {
            runCatching { extractor.release() }
        }
    }

    /**
     * The source's audio and video tracks by extractor index. Other tracks are left out, so their
     * samples are never copied. Throws an [IOException] when there is no audio or video track, or
     * naming the first one an MPEG-4 container can't hold.
     */
    private fun mediaTracks(extractor: MediaExtractor): Map<Int, MediaFormat> {
        val tracks = mutableMapOf<Int, MediaFormat>()
        for (track in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(track)
            val mime = format.mime()
            when {
                !mime.startsWith("audio/") && !mime.startsWith("video/") ->
                    Log.i(TAG, "Dropping track $track ($mime): not audio or video")
                !isMuxableIntoMp4(mime) ->
                    throw IOException("Track $track ($mime) cannot be written to an MPEG-4 container")
                else -> tracks[track] = format
            }
        }
        if (tracks.isEmpty()) throw IOException("Source has no audio or video track")
        return tracks
    }

    private fun MediaFormat.mime(): String = getString(MediaFormat.KEY_MIME).orEmpty()

    /** Throws (releasing [extractor], which won't be used) when [output] is the file [input] names. */
    private fun requireOutputIsNotInput(
        input: Uri,
        output: File,
        extractor: MediaExtractor,
    ) {
        if (isSameFile(input, output)) {
            runCatching { extractor.release() }
            throw IOException("output is the input file ${output.path}")
        }
    }

    private fun isSameFile(
        input: Uri,
        output: File,
    ): Boolean {
        val inputPath = input.path
        // MediaExtractor reads a Uri without a scheme as a file path too.
        val isFile = input.scheme == null || input.scheme == ContentResolver.SCHEME_FILE
        return isFile &&
            inputPath != null &&
            File(inputPath).canonicalFile == output.canonicalFile
    }

    /** Rotation is a container property, not part of any track format, so it is read separately. */
    private fun readRotation(
        context: Context,
        input: Uri,
    ): Int {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, input)
            return retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0
        } finally {
            retriever.release()
        }
    }
}
