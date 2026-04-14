package com.davotoula.lightcompressor.hls

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException

/**
 * Upload-aware [HlsListener] impl. Splits upload work into two phases so that
 * `Dispatchers.Main` is never blocked:
 *
 * **Sync phase** (driven by [HlsListener] callbacks from inside the transcoder):
 * - [onSegmentReady] uploads each segment via a bounded `runBlocking(Dispatchers.IO)` call.
 *   This runs on `Dispatchers.Default` (the transcoder's per-segment dispatcher), where
 *   blocking one thread is acceptable — the thread pool has others and the next segment
 *   has to wait for the current one's emission anyway.
 * - [onRenditionComplete] only stores the summary, no uploads. This runs on
 *   `Dispatchers.Main`, which we cannot block.
 * - [onComplete] only stores the master playlist string.
 *
 * **Suspend phase** ([completeUpload], called from the outer coroutine in [HlsUploadHelper.run]):
 * - Rewrites each rendition's media playlist using the URLs collected during the sync phase.
 * - Uploads each rewritten media playlist via `withContext(Dispatchers.IO)` — this is
 *   legal here because we're inside a suspending context.
 * - Rewrites and returns the master playlist.
 *
 * If [externalListener] is non-null, every callback is forwarded to it **after** the
 * orchestrator's own bookkeeping. This lets consumers (e.g. a ViewModel-driven UI) observe
 * the raw `HlsPreparer` event stream for progress reporting, while the orchestrator handles
 * the upload plumbing underneath. Forwarding is unconditional — the external listener sees
 * every event even after an internal upload failure, so its view of the event stream is
 * identical to what it would receive if it were the direct `HlsPreparer` listener.
 *
 * Kept separate from [HlsUploadHelper] so the entire flow can be unit-tested without
 * real Android framework dependencies. Tests drive the listener methods directly, then
 * call [completeUpload] inside a `runBlocking { ... }` block.
 */
internal class HlsUploadOrchestrator(
    private val context: Context,
    private val uploader: suspend (File, String) -> String,
    private val externalListener: HlsListener? = null,
) : HlsListener {
    private val segmentUrls = mutableMapOf<Rendition, MutableMap<String, String>>()
    private val pendingSummaries = mutableListOf<HlsRenditionSummary>()

    private var masterPlaylist: String? = null

    @Volatile
    private var failure: Throwable? = null

    @Volatile
    private var cancelled: Boolean = false
    private var completed: Boolean = false

    override fun onStart(renditionCount: Int) {
        externalListener?.onStart(renditionCount)
    }

    override fun onRenditionStart(rendition: Rendition) {
        segmentUrls.getOrPut(rendition) { mutableMapOf() }
        externalListener?.onRenditionStart(rendition)
    }

    override fun onSegmentReady(
        rendition: Rendition,
        segment: HlsSegment,
    ) {
        try {
            if (failure == null && !cancelled && !completed) {
                val suggested = segment.suggestedFilename(rendition)
                // Catch Throwable so uploader failures, including Errors from lambdas, reach completeUpload().
                @Suppress("TooGenericExceptionCaught")
                try {
                    val url = runBlocking(Dispatchers.IO) { uploader(segment.file, suggested) }
                    segmentUrls.getOrPut(rendition) { mutableMapOf() }[suggested] = url
                } catch (e: Throwable) {
                    failure = e
                }
            }
        } finally {
            externalListener?.onSegmentReady(rendition, segment)
        }
    }

    override fun onRenditionComplete(
        rendition: Rendition,
        summary: HlsRenditionSummary,
    ) {
        if (failure == null && !cancelled) {
            pendingSummaries += summary
        }
        externalListener?.onRenditionComplete(rendition, summary)
    }

    override fun onComplete(masterPlaylist: String) {
        if (failure == null && !cancelled) {
            this.masterPlaylist = masterPlaylist
            completed = true
        }
        externalListener?.onComplete(masterPlaylist)
    }

    override fun onFailure(error: HlsError) {
        if (failure == null) failure = IllegalStateException(error.message)
        externalListener?.onFailure(error)
    }

    override fun onProgress(
        rendition: Rendition,
        percent: Float,
    ) {
        externalListener?.onProgress(rendition, percent)
    }

    override fun onCancelled() {
        cancelled = true
        externalListener?.onCancelled()
    }

    /**
     * Complete the upload: rewrites and uploads every rendition's media playlist, then
     * rewrites and returns the master playlist. Must only be called after the transcoder
     * has delivered a terminal callback (`onComplete` / `onFailure` / `onCancelled`).
     *
     * Throws:
     * - whatever the segment uploader threw, if a segment upload failed during the sync phase
     * - [CancellationException] if the transcoder was cancelled
     * - [IllegalStateException] if all renditions failed ([onFailure] fired) or if
     *   `completeUpload` is called before any terminal signal
     * - whatever a media playlist uploader throws, if one fails during the suspend phase
     */
    @Suppress("ThrowsCount") // three distinct error modes: prior failure, cancellation, premature call
    suspend fun completeUpload(): HlsUploadResult {
        failure?.let { throw it }
        if (cancelled) throw CancellationException("HLS upload cancelled")
        val master =
            masterPlaylist
                ?: error("HlsUploadOrchestrator.completeUpload called before onComplete")

        val renditionPlaylistUrls = mutableMapOf<String, String>()
        for (summary in pendingSummaries) {
            val prefix = "${summary.rendition.resolution.label}/"
            // segmentUrls keys are suggestedFilenames (e.g. "720p/init.mp4", "720p/segment_000.m4s",
            // or "720p.mp4" for combined). Media playlists reference bare names ("init.mp4",
            // "segment_000.m4s") for multi-file, and the full label-filename ("720p.mp4") for
            // combined. Strip the "720p/" prefix to get playlist-local keys for multi-file entries;
            // combined-rendition keys have no slash prefix and pass through unchanged.
            val perRenditionMap =
                (segmentUrls[summary.rendition] ?: emptyMap()).entries.associate { (key, url) ->
                    val playlistKey = if (key.startsWith(prefix)) key.removePrefix(prefix) else key
                    playlistKey to url
                }
            val rewrittenMedia = PlaylistRewriter.rewrite(summary.mediaPlaylist, perRenditionMap)
            val tempFile = File(context.cacheDir, "hls-upload-${summary.rendition.resolution.label}-media.m3u8")
            try {
                tempFile.writeText(rewrittenMedia)
                val url = withContext(Dispatchers.IO) { uploader(tempFile, summary.playlistFilename) }
                renditionPlaylistUrls[summary.playlistFilename] = url
            } finally {
                tempFile.delete()
            }
        }

        return HlsUploadResult(
            masterPlaylist = PlaylistRewriter.rewrite(master, renditionPlaylistUrls),
            renditions = pendingSummaries.toList(),
        )
    }
}
