package com.davotoula.lightcompressor.hls

import android.content.Context
import android.net.Uri
import com.davotoula.lightcompressor.HlsPreparer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * The result of a successful [HlsUploadHelper.run] call.
 *
 * @property masterPlaylist the rewritten master playlist, with every rendition reference
 *   replaced by the URL the uploader returned. Ready for the caller to publish however they
 *   want (write to disk, POST to a server, insert into a database row, etc.).
 * @property renditions one summary per successfully uploaded rendition. Carries the output
 *   dimensions, codec string, and filenames the caller may need when building downstream
 *   metadata (e.g. Nostr `imeta` tags or a video manifest).
 */
data class HlsUploadResult(
    val masterPlaylist: String,
    val renditions: List<HlsRenditionSummary>,
)

/**
 * Convenience orchestrator for "transcode → upload → rewrite → publish" pipelines.
 *
 * Pass an [uploader] lambda that knows how to push a local file to wherever your content
 * lives. [HlsUploadHelper.run] drives the full HLS pipeline: every segment (and every
 * media playlist, after rewriting) is handed to the uploader in order; the returned
 * [HlsUploadResult.masterPlaylist] is already rewritten to point at the URLs the uploader
 * returned.
 *
 * The helper does **not** upload the master playlist itself — the caller publishes the
 * returned string however they want. If you want to upload it via the same pipeline, one
 * more `uploader(tempFile, "master.m3u8")` call is all it takes.
 *
 * **Threading:**
 * - Segment uploads (init + media segments) run during `HlsListener.onSegmentReady`, which
 *   the transcoder dispatches on `Dispatchers.Default`. The orchestrator wraps each
 *   uploader call in `runBlocking(Dispatchers.IO)`, so consumer code always executes on
 *   the IO dispatcher. Blocking a `Default` thread is acceptable because the next segment
 *   can't start encoding until the current one's callback returns.
 * - Media-playlist and master-playlist rewriting and upload happen **after** the transcoder
 *   has finished (inside [HlsUploadOrchestrator.completeUpload]), in the outer coroutine's
 *   context, via `withContext(Dispatchers.IO)`. `Dispatchers.Main` is never blocked.
 * - Uploads happen serially: lowest rendition first, init segment before media segments,
 *   then media playlists in rendition order.
 *
 * **Error handling:** the first uploader failure cancels the whole operation. [run] throws
 * the original cause. Partial rendition failures in the transcoder behave the same way as
 * [HlsPreparer] — if at least one rendition makes it through, [run] returns a result
 * containing only the successful renditions; if all fail, [run] throws.
 *
 * **Progress reporting:** pass an optional [HlsListener] as the `listener` parameter to
 * observe the raw `HlsPreparer` event stream (rendition start, per-segment progress, etc.)
 * while the helper handles upload and rewriting underneath. Every `HlsListener` callback
 * is forwarded to the supplied listener after the helper's own bookkeeping, so the
 * listener's view of the event stream matches what it would see as a direct `HlsPreparer`
 * listener. Typical use: a ViewModel passes a progress-only `SimpleHlsListener` subclass
 * that updates UI state.
 *
 * Example:
 * ```
 * val result = HlsUploadHelper.run(
 *     context = context,
 *     uri = videoUri,
 *     config = HlsConfig(),
 *     listener = progressListener, // optional; receives per-segment progress
 * ) { file, name ->
 *     val contentType = if (name.endsWith(".m3u8")) {
 *         HlsContentTypes.forPlaylist()
 *     } else {
 *         HlsContentTypes.FMP4_SEGMENT
 *     }
 *     myUploader.upload(file, contentType = contentType).url
 * }
 * // Publish result.masterPlaylist however you want — it's already rewritten.
 * ```
 */
object HlsUploadHelper {
    /**
     * Transcode [uri] into an HLS ladder and upload every segment via [uploader]. Returns
     * the rewritten master playlist along with per-rendition summaries. See class docs for
     * threading, error handling, progress reporting, and partial-success semantics.
     *
     * @param listener optional [HlsListener] that observes the raw `HlsPreparer` event
     *   stream (rendition start, per-segment progress, etc.). Every callback is forwarded
     *   after the helper's own bookkeeping. Useful for driving a progress UI.
     */
    suspend fun run(
        context: Context,
        uri: Uri,
        config: HlsConfig = HlsConfig(),
        listener: HlsListener? = null,
        uploader: suspend (file: File, suggestedFilename: String) -> String,
    ): HlsUploadResult =
        coroutineScope {
            val orchestrator = HlsUploadOrchestrator(context, uploader, listener)
            try {
                val preparerJob =
                    HlsPreparer.start(
                        context = context,
                        uri = uri,
                        config = config,
                        listener = orchestrator,
                    )
                preparerJob.join()
            } catch (e: CancellationException) {
                HlsPreparer.cancel()
                throw e
            }
            orchestrator.completeUpload()
        }
}
