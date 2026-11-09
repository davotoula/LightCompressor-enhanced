package com.davotoula.lightcompressor.sanitizer

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.WorkerThread
import com.davotoula.lightcompressor.data.MDAT_ATOM
import com.davotoula.lightcompressor.data.MOOV_ATOM
import com.davotoula.lightcompressor.describe
import com.davotoula.lightcompressor.utils.CompressorUtils.convertExtractorFlagsToCodecFlags
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Outcome of [Mp4Sanitizer.sanitize]: [file] is [Mp4Sanitizer.sanitize]'s `output`. */
data class SanitizeResult(
    val file: File,
    /** `moov` precedes `mdat` in [file]. False means the fast-start pass failed and the plain remux was kept. */
    val fastStart: Boolean,
)

/** Strips container metadata from an MP4 or M4A by remuxing its samples, without decoding or re-encoding. */
object Mp4Sanitizer {
    private const val TAG = "Mp4Sanitizer"
    private const val MIN_SAMPLE_BUFFER_SIZE = 8 * 1024 * 1024
    private const val MUX_TEMP_SUFFIX = ".mux.tmp"
    private const val ATOM_HEADER_SIZE = 8L
    private const val LARGE_ATOM_HEADER_SIZE = 16L
    private const val UINT32_MASK = 0xFFFFFFFFL
    private const val NO_TRACK = -1

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
     * untouched: no decode, no re-encode.
     *
     * Residual: `MediaMuxer` writes its own creation time, and a `meta` box holding
     * `com.android.version` (the OS version); metadata carried inside the bitstream (H.264 SEI
     * user data) is not removed.
     *
     * @throws IOException when the source cannot be read, has no audio or video track, has an
     *   audio or video track that cannot be written to an MPEG-4 container (see
     *   [isMuxableIntoMp4]), or the remux fails. [output] does not exist after a throw.
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
        val muxerFile = File(output.path + MUX_TEMP_SUFFIX)
        var succeeded = false
        try {
            remux(context, input, muxerFile, extractor)
            val fastStart = finishFastStart(muxerFile, output)
            if (!fastStart) {
                Log.w(TAG, "${output.path} is not fast-start; keeping the plain remux")
            }
            succeeded = true
            return SanitizeResult(output, fastStart)
        } catch (e: Exception) {
            throw e as? IOException ?: IOException("Sanitizing failed: ${e.describe()}", e)
        } finally {
            muxerFile.delete()
            if (!succeeded) output.delete()
        }
    }

    /**
     * True when the file's top-level `moov` atom precedes its first `mdat`. Reads atom headers only.
     * False when either atom is missing, an atom size is corrupt, or the file cannot be read.
     */
    @JvmStatic
    fun isFastStart(file: File): Boolean =
        try {
            moovPrecedesMdat(file)
        } catch (_: IOException) {
            false
        }

    /** Mime types `MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4` accepts. */
    @JvmStatic
    fun isMuxableIntoMp4(mime: String): Boolean = mime in MP4_MIMES

    private fun remux(
        context: Context,
        input: Uri,
        muxerFile: File,
        extractor: MediaExtractor,
    ) {
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(context, input, null)
            val tracks = mediaTracks(extractor)
            val rotation = readRotation(context, input)
            val newMuxer =
                MediaMuxer(muxerFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            // Indexed by extractor track; only selected tracks produce samples, so dropped ones are never looked up.
            val muxerTracks = IntArray(extractor.trackCount) { NO_TRACK }
            tracks.forEach { (track, format) ->
                extractor.selectTrack(track)
                muxerTracks[track] = newMuxer.addTrack(format)
            }
            if (rotation != 0) newMuxer.setOrientationHint(rotation)
            newMuxer.start()
            muxerStarted = true
            copySamples(extractor, newMuxer, muxerTracks, sampleBufferSize(tracks.values))
            muxerStarted = false
            newMuxer.stop()
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
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
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
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

    /** Large enough for 4K keyframes; [copySamples] still grows it for a larger sample. */
    private fun sampleBufferSize(formats: Collection<MediaFormat>): Int =
        formats
            .filter { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }
            .maxOfOrNull { it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) }
            ?.coerceAtLeast(MIN_SAMPLE_BUFFER_SIZE)
            ?: MIN_SAMPLE_BUFFER_SIZE

    private fun copySamples(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        muxerTracks: IntArray,
        initialBufferSize: Int,
    ) {
        var buffer = ByteBuffer.allocateDirect(initialBufferSize)
        val bufferInfo = MediaCodec.BufferInfo()
        while (true) {
            // getSampleSize is API 28+; below it the buffer floor has to be enough.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && extractor.sampleSize > buffer.capacity()) {
                buffer = ByteBuffer.allocateDirect(extractor.sampleSize.toInt())
            }
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            val sampleTime = extractor.sampleTime
            if (sampleSize < 0 || sampleTime < 0) break
            bufferInfo.set(0, sampleSize, sampleTime, convertExtractorFlagsToCodecFlags(extractor.sampleFlags))
            muxer.writeSampleData(muxerTracks[extractor.sampleTrackIndex], buffer, bufferInfo)
            extractor.advance()
        }
    }

    private fun moovPrecedesMdat(file: File): Boolean {
        var moovOffset = -1L
        var mdatOffset = -1L
        RandomAccessFile(file, "r").use { raf ->
            var offset = 0L
            while (moovOffset < 0 || mdatOffset < 0) {
                val atom = atomAt(raf, offset) ?: break
                if (atom.type == MOOV_ATOM && moovOffset < 0) moovOffset = offset
                if (atom.type == MDAT_ATOM && mdatOffset < 0) mdatOffset = offset
                offset += atom.size
            }
        }
        return moovOffset >= 0 && mdatOffset >= 0 && moovOffset < mdatOffset
    }

    private class Atom(
        val type: Int,
        val size: Long,
    )

    /** The atom at [offset], or null at the end of the file or when its size is corrupt. */
    private fun atomAt(
        raf: RandomAccessFile,
        offset: Long,
    ): Atom? {
        val length = raf.length()
        if (offset + ATOM_HEADER_SIZE > length) return null
        raf.seek(offset)
        var size = raf.readInt().toLong() and UINT32_MASK
        val type = raf.readInt()
        var headerSize = ATOM_HEADER_SIZE
        when (size) {
            0L -> size = length - offset
            1L -> {
                headerSize = LARGE_ATOM_HEADER_SIZE
                // A truncated 64-bit size reads as 0, which the check below rejects.
                size = if (offset + headerSize <= length) raf.readLong() else 0L
            }
        }
        // A corrupt size ends the walk instead of seeking outside the file.
        return if (size < headerSize || size > length - offset) null else Atom(type, size)
    }
}
