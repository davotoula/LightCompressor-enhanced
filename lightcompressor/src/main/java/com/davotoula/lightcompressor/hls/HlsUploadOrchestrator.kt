package com.davotoula.lightcompressor.hls

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File

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
 * - Uploads the rewritten media playlists in parallel via `withContext(Dispatchers.IO)` —
 *   legal here because we're inside a suspending context.
 * - Rewrites and returns the master playlist.
 *
 * Listener callbacks reach the orchestrator from two threads — `Dispatchers.Default` for
 * [onSegmentReady] / [onProgress], `Dispatchers.Main` for the rest — and [completeUpload]
 * then reads the accumulated state from the caller's coroutine context. All mutable state
 * is guarded by [stateLock]; `uploader` calls and [externalListener] forwarding are kept
 * outside the lock so blocking uploads never stall the UI thread.
 *
 * If [externalListener] is non-null, every callback is forwarded to it **after** the
 * orchestrator's own bookkeeping. This lets consumers (e.g. a ViewModel-driven UI) observe
 * the raw `HlsPreparer` event stream for progress reporting, while the orchestrator handles
 * the upload plumbing underneath. Forwarding is unconditional — the external listener sees
 * every event even after an internal upload failure, so its view of the event stream is
 * identical to what it would receive if it were the direct `HlsPreparer` listener.
 *
 * The orchestrator is generic over `T` so the public [HlsUploadResult.uploads] map can
 * carry arbitrary per-upload metadata the caller returns from the uploader lambda.
 */
internal class HlsUploadOrchestrator<T>(
    private val context: Context,
    private val uploader: suspend (File, String) -> HlsUploaded<T>,
    private val externalListener: HlsListener? = null,
) : HlsListener {
    private val stateLock = Any()

    // Per-rendition rewrite map, keyed on the playlist-relative filename that
    // PlaylistRewriter will look up (e.g. "init.mp4", "segment_000.m4s", "720p.mp4").
    private val segmentUrls = mutableMapOf<Rendition, LinkedHashMap<String, String>>()

    // Flat output map preserving upload timeline order, keyed on suggestedFilename
    // (the public contract of HlsUploadResult.uploads).
    private val uploads = LinkedHashMap<String, HlsUploaded<T>>()

    private val pendingSummaries = mutableListOf<HlsRenditionSummary>()

    @Volatile
    private var masterPlaylist: String? = null

    @Volatile
    private var terminalError: Throwable? = null

    override fun onStart(renditionCount: Int) {
        externalListener?.onStart(renditionCount)
    }

    override fun onRenditionStart(rendition: Rendition) {
        synchronized(stateLock) {
            segmentUrls.getOrPut(rendition) { LinkedHashMap() }
        }
        externalListener?.onRenditionStart(rendition)
    }

    override fun onSegmentReady(
        rendition: Rendition,
        segment: HlsSegment,
    ) {
        try {
            if (terminalError == null && masterPlaylist == null) {
                val suggested = rendition.suggestedFilename(segment)
                val playlistKey = rendition.playlistRelativeFilename(segment)
                // Catch Throwable so uploader failures, including Errors from lambdas, reach completeUpload().
                @Suppress("TooGenericExceptionCaught")
                try {
                    val uploaded = runBlocking(Dispatchers.IO) { uploader(segment.file, suggested) }
                    synchronized(stateLock) {
                        // getOrPut (vs getValue) keeps the write self-healing even if a future
                        // refactor stops calling onRenditionStart before the first segment.
                        segmentUrls.getOrPut(rendition) { LinkedHashMap() }[playlistKey] = uploaded.url
                        uploads[suggested] = uploaded
                    }
                } catch (e: Throwable) {
                    terminalError = e
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
        if (terminalError == null) {
            synchronized(stateLock) {
                pendingSummaries += summary
            }
        }
        externalListener?.onRenditionComplete(rendition, summary)
    }

    override fun onComplete(masterPlaylist: String) {
        if (terminalError == null) {
            this.masterPlaylist = masterPlaylist
        }
        externalListener?.onComplete(masterPlaylist)
    }

    override fun onFailure(error: HlsError) {
        if (terminalError == null) terminalError = IllegalStateException(error.message)
        externalListener?.onFailure(error)
    }

    override fun onProgress(
        rendition: Rendition,
        percent: Float,
    ) {
        externalListener?.onProgress(rendition, percent)
    }

    override fun onCancelled() {
        if (terminalError == null) terminalError = CancellationException("HLS upload cancelled")
        externalListener?.onCancelled()
    }

    /**
     * Complete the upload: rewrites and uploads every rendition's media playlist in
     * parallel, then rewrites and returns the master playlist. Must only be called after
     * the transcoder has delivered a terminal callback (`onComplete` / `onFailure` /
     * `onCancelled`).
     *
     * Throws:
     * - whatever the segment uploader threw, if a segment upload failed during the sync phase
     * - [CancellationException] if the transcoder was cancelled
     * - [IllegalStateException] if all renditions failed ([onFailure] fired) or if
     *   `completeUpload` is called before any terminal signal
     * - whatever a media playlist uploader throws, if one fails during the suspend phase
     */
    suspend fun completeUpload(): HlsUploadResult<T> {
        terminalError?.let { throw it }
        val master =
            masterPlaylist
                ?: error("HlsUploadOrchestrator.completeUpload called before onComplete")

        // Snapshot the accumulated state under the lock so downstream reads see a stable
        // view regardless of which thread delivered the final transcoder callback.
        val summaries: List<HlsRenditionSummary>
        val rewriteMaps: Map<Rendition, Map<String, String>>
        synchronized(stateLock) {
            summaries = pendingSummaries.toList()
            rewriteMaps = segmentUrls.mapValues { (_, map) -> map.toMap() }
        }

        val uploadedPlaylists =
            coroutineScope {
                summaries
                    .map { summary ->
                        async {
                            val rewriteMap = rewriteMaps[summary.rendition] ?: emptyMap()
                            val rewrittenMedia = PlaylistRewriter.rewrite(summary.mediaPlaylist, rewriteMap)
                            val label = summary.rendition.resolution.label
                            val tempFile = File(context.cacheDir, "hls-upload-$label-media.m3u8")
                            try {
                                tempFile.writeText(rewrittenMedia)
                                val uploaded =
                                    withContext(Dispatchers.IO) {
                                        uploader(tempFile, summary.playlistFilename)
                                    }
                                summary.playlistFilename to uploaded
                            } finally {
                                tempFile.delete()
                            }
                        }
                    }.awaitAll()
            }

        val renditionPlaylistUrls = LinkedHashMap<String, String>()
        synchronized(stateLock) {
            for ((playlistFilename, uploaded) in uploadedPlaylists) {
                uploads[playlistFilename] = uploaded
                renditionPlaylistUrls[playlistFilename] = uploaded.url
            }
        }

        val uploadsSnapshot =
            synchronized(stateLock) {
                LinkedHashMap(uploads)
            }

        return HlsUploadResult(
            masterPlaylist = PlaylistRewriter.rewrite(master, renditionPlaylistUrls),
            renditions = summaries,
            uploads = uploadsSnapshot,
        )
    }
}
