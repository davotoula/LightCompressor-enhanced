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
 * until the callback returns. The library deletes the temp file after return.
 */
interface HlsListener {
    /** Called when preparation starts. [renditionCount] = number of renditions to process. */
    fun onStart(renditionCount: Int)

    /** Called when a rendition begins encoding. */
    fun onRenditionStart(rendition: Rendition)

    /**
     * Called when a segment is ready. The [segment] file is valid until this method returns.
     * Upload or copy the file before returning — the library deletes it afterward.
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
 */
data class HlsSegment(
    /** Temp file containing the segment data. Valid until [HlsListener.onSegmentReady] returns. */
    val file: File,
    /** Segment sequence number (0-based). */
    val index: Int,
    /** Actual segment duration in seconds. */
    val durationSeconds: Double,
    /** True for the initialization segment (init.mp4), false for media segments. */
    val isInitSegment: Boolean,
)

/**
 * Error details when HLS preparation fails.
 */
data class HlsError(
    val message: String,
    val failedRenditions: List<Rendition>,
    val completedRenditions: List<Rendition>,
)
