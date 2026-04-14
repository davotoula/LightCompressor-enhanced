package com.davotoula.lightcompressor.hls

import java.io.File

/**
 * Callback interface for HLS preparation progress and results.
 *
 * Threading:
 * - [onSegmentReady] and [onProgress]: called on worker thread (Dispatchers.Default)
 * - All other callbacks: called on Main thread
 *
 * The [onSegmentReady] callback is synchronous: the segment file is valid
 * until the callback returns. The listener may copy, upload, or move/rename
 * the file during the callback. After the callback returns, the library
 * will delete the temp file if it is still present.
 */
interface HlsListener {
    /** Called when preparation starts. [renditionCount] = number of renditions to process. */
    fun onStart(renditionCount: Int)

    /** Called when a rendition begins encoding. */
    fun onRenditionStart(rendition: Rendition)

    /**
     * Called when a segment is ready. The [segment] file is valid until this method returns.
     * The listener must copy, upload, or move the file synchronously before returning.
     *
     * **Performance tip:** For large combined-rendition files (see [HlsSegment.isCombinedRendition]),
     * prefer [java.io.File.renameTo] over copying when the destination is on the same filesystem.
     * The library tolerates the file being moved — it will not log warnings if the temp file is
     * already gone when it tries to clean up.
     */
    fun onSegmentReady(
        rendition: Rendition,
        segment: HlsSegment,
    )

    /** Called when a rendition finishes. [playlist] is the m3u8 media playlist content. */
    fun onRenditionComplete(
        rendition: Rendition,
        playlist: String,
    )

    /** Called when all renditions complete. [masterPlaylist] is the master m3u8 content. */
    fun onComplete(masterPlaylist: String)

    /** Called if all renditions fail. Partial success still triggers [onComplete]. */
    fun onFailure(error: HlsError)

    /** Encoding progress for the current rendition. [percent] is 0.0 to 100.0. */
    fun onProgress(
        rendition: Rendition,
        percent: Float,
    )

    /** Called if [HlsPreparer.cancel] is invoked. */
    fun onCancelled()
}

/**
 * Represents a single fMP4 segment ready for upload.
 *
 * In the default multi-file layout, the listener receives one [HlsSegment] per init segment
 * and per media segment. In the single-file layout enabled by
 * [com.davotoula.lightcompressor.hls.HlsConfig.singleFilePerRendition], the listener receives
 * exactly one [HlsSegment] per rendition with [isCombinedRendition] set to `true`.
 */
data class HlsSegment(
    /**
     * Temp file containing the segment data. Valid until [HlsListener.onSegmentReady] returns.
     * The listener may move/rename this file during the callback for a fast O(1) handoff on the
     * same filesystem.
     */
    val file: File,
    /** Segment sequence number (0-based). */
    val index: Int,
    /** Actual segment duration in seconds. For a combined rendition this is the rendition total. */
    val durationSeconds: Double,
    /** True for the initialization segment (init.mp4), false for media segments. */
    val isInitSegment: Boolean,
    /**
     * True when the file contains the entire rendition (init + every media segment concatenated).
     * Mutually exclusive with [isInitSegment]; only set when
     * [com.davotoula.lightcompressor.hls.HlsConfig.singleFilePerRendition] is enabled.
     */
    val isCombinedRendition: Boolean = false,
)

/**
 * Error details when HLS preparation fails.
 */
data class HlsError(
    val message: String,
    val failedRenditions: List<Rendition>,
    val completedRenditions: List<Rendition>,
)
