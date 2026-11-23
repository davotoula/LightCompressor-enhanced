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
     * @see sanitize the overload that also trims.
     */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun sanitize(
        context: Context,
        input: Uri,
        output: File,
    ): SanitizeResult = sanitize(context, input, output, MediaExtractor())

    /**
     * [sanitize], keeping only [trim] of the source: trimmed, stripped and fast-started in one pass, still without
     * decoding or re-encoding, so the cut falls on key frames.
     *
     * - **Start:** the cut starts at the video key frame at or before [TrimRange.startMs], so a little before the
     *   start can show. That frame is written at time 0, and every track is shifted by the same amount, so audio
     *   and video stay in sync. Audio before it is dropped (but see priming, below), and so are video frames that
     *   display before it (open-GOP leading pictures, which need the GOP before). Audio-only input starts at the
     *   audio sample at or before the start.
     * - **End:** [TrimRange.endMs] is inclusive: samples at or before it are kept, and each track stops at its
     *   first sample after it. Up to 8 video frames past the end are kept when a frame at or before the end
     *   follows them in decode order, so the last B-frames still decode.
     * - **The source's beginning:** when the cut starts there, AAC encoder priming is kept behind an edit list,
     *   as [sanitize] keeps it. A video key frame at a negative time (a source whose edit list starts mid-GOP,
     *   which [sanitize] refuses) is kept too: it is what the cut snaps back to, and it is written at 0 like any
     *   other. The audio shifts with it, so priming after that key frame plays, under the frames the source hid.
     * - **Tracks:** an audio track with no sample in the range is left out of the output rather than written
     *   empty. Only one video track is supported.
     *
     * @throws IOException as [sanitize] does, and when [TrimRange.startMs] is after the end of the video (or, for
     *   audio-only input, the audio), or the source has more than one video track. [output] does not exist after
     *   a throw.
     */
    @JvmStatic
    @WorkerThread
    @Throws(IOException::class)
    fun sanitize(
        context: Context,
        input: Uri,
        output: File,
        trim: TrimRange,
    ): SanitizeResult = sanitize(context, input, output, MediaExtractor(), trim)

    /**
     * [sanitize] with the extractors injected, so tests can stand in for the stub `MediaExtractor`. [newProbe]
     * makes the extractor a trim is planned with, and is only called with a [trim].
     */
    @Suppress("TooGenericExceptionCaught")
    internal fun sanitize(
        context: Context,
        input: Uri,
        output: File,
        extractor: MediaExtractor,
        trim: TrimRange? = null,
        newProbe: () -> MediaExtractor = ::MediaExtractor,
    ): SanitizeResult {
        // Checked first: cleaning up after a failure deletes output, which here would be the source.
        requireOutputIsNotInput(input, output, extractor)
        val muxerFile = File(output.path + MUX_TEMP_SUFFIX)
        var succeeded = false
        try {
            remux(context, input, muxerFile, extractor) { tracks ->
                trim?.let { probeTrim(context, input, newProbe(), tracks, it) }
            }
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

    /** [plan] plans the trim of the source's media tracks, or returns null to copy them whole. */
    private fun remux(
        context: Context,
        input: Uri,
        muxerFile: File,
        extractor: MediaExtractor,
        plan: (tracks: Map<Int, MediaFormat>) -> TrimPlan?,
    ) {
        try {
            extractor.setDataSource(context, input, null)
            val sourceTracks = mediaTracks(extractor)
            val trimPlan = plan(sourceTracks)
            val tracks = trimPlan?.let { sourceTracks.filterKeys(it.tracks::contains) } ?: sourceTracks
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
                val written = copy(extractor, muxer, muxerTracks, tracks, trimPlan?.window)
                // MediaMuxer silently leaves out a track with no samples: a failed sanitize, not a smaller file. A
                // trim has already left out the tracks it has no samples for, so this is a failure there too.
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

    /** Copies the selected [tracks] whole, or within [trim]; the samples written per extractor track. */
    private fun copy(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTracks: IntArray,
        tracks: Map<Int, MediaFormat>,
        trim: TrimWindow?,
    ): IntArray {
        val bufferSize = sampleBufferSize(tracks.values, MIN_SAMPLE_BUFFER_SIZE)
        return if (trim == null) {
            copySamples(extractor, muxer, muxerTracks, bufferSize) { track ->
                if (tracks.getValue(track).isVideo()) NegativeTime.REJECT else NegativeTime.COPY
            }
        } else {
            copyTrimmed(extractor, muxer, muxerTracks, bufferSize, trim)
        }
    }

    private fun MediaFormat.mime(): String = getString(MediaFormat.KEY_MIME).orEmpty()

    private fun MediaFormat.isVideo(): Boolean = mime().startsWith("video/")

    /** Plans [trim] of [tracks] with [probe], a second extractor of [input], released afterwards. */
    private fun probeTrim(
        context: Context,
        input: Uri,
        probe: MediaExtractor,
        tracks: Map<Int, MediaFormat>,
        trim: TrimRange,
    ): TrimPlan {
        val plan =
            try {
                probe.setDataSource(context, input, null)
                planTrim(probe, tracks.keys, tracks.filterValues { it.isVideo() }.keys, trim)
            } finally {
                runCatching { probe.release() }
            }
        (tracks.keys - plan.tracks).forEach { track ->
            Log.i(TAG, "Leaving out track $track (${tracks.getValue(track).mime()}): no samples in the trim")
        }
        return plan
    }

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
