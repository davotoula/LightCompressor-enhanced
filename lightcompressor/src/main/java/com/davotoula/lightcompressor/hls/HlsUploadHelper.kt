package com.davotoula.lightcompressor.hls

import android.content.Context
import android.net.Uri
import com.davotoula.lightcompressor.HlsPreparer
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * The return value of the uploader lambda passed to [HlsUploadHelper.run]. Pairs the URL
 * the file was uploaded to with arbitrary per-upload metadata that the caller wants to
 * carry back into [HlsUploadResult.uploads].
 *
 * Use the [metadata] slot to thread content-addressed hashes, sizes, server-assigned IDs,
 * or any other per-upload payload you need later. Callers who don't need metadata use
 * [Unit] as `T` and `HlsUploaded(url, Unit)`.
 *
 * @property url the URL the file is now available at, used by the library to rewrite
 *   segment and playlist references
 * @property metadata caller-supplied payload preserved verbatim in [HlsUploadResult.uploads]
 */
data class HlsUploaded<out T>(
    val url: String,
    val metadata: T,
)

/**
 * The result of a successful [HlsUploadHelper.run] call.
 *
 * @property masterPlaylist the rewritten master playlist, with every rendition reference
 *   replaced by the URL the uploader returned. Ready for the caller to publish however they
 *   want (write to disk, POST to a server, insert into a database row, etc.).
 * @property renditions one summary per successfully uploaded rendition. Carries the output
 *   dimensions, codec string, and filenames the caller may need when building downstream
 *   metadata (e.g. Nostr `imeta` tags or a video manifest).
 * @property uploads every call the helper made to the uploader lambda, keyed by the
 *   `suggestedFilename` passed to the lambda. Contents:
 *   - **Multi-file mode**: init segment (`"<label>/init.mp4"`), each media segment
 *     (`"<label>/segment_NNN.m4s"`), and the rewritten media playlist (`"<label>/media.m3u8"`)
 *     for every rendition.
 *   - **Single-file mode**: combined rendition file (`"<label>.mp4"`) and the rewritten media
 *     playlist (`"<label>/media.m3u8"`) for every rendition.
 *
 *   **Key format contract**: map keys match exactly what [Rendition.suggestedFilename]
 *   returns for segments, [HlsRenditionSummary.playlistFilename] (equivalently
 *   [Rendition.mediaPlaylistFilename]) for media playlists, and
 *   [HlsRenditionSummary.combinedFilename] for combined renditions in single-file mode.
 *   Consumers can look up entries with the same identifiers they already hold.
 *
 *   **Iteration order**: the returned `Map` is a `LinkedHashMap`; iteration order matches
 *   the actual upload timeline. That timeline is **all segments first** (across all
 *   renditions, in emission order from the transcoder — so 720p segments before 1080p
 *   segments, init before media segments within a rendition), **then all media playlists**
 *   (in rendition order). The split reflects how the helper batches work: segments upload
 *   synchronously during the transcoder's callbacks, media playlists upload after the
 *   transcoder has finished. Useful for logging, timing analysis, and deterministic output.
 *   The master playlist is **not** in this map — the helper does not upload the master
 *   playlist itself.
 */
data class HlsUploadResult<out T>(
    val masterPlaylist: String,
    val renditions: List<HlsRenditionSummary>,
    val uploads: Map<String, HlsUploaded<T>>,
)

/**
 * Convenience orchestrator for "transcode → upload → rewrite → publish" pipelines.
 *
 * Pass an [uploader] lambda that knows how to push a local file to wherever your content
 * lives. The lambda returns an [HlsUploaded] wrapper carrying the URL (used by the library
 * to rewrite playlist references) plus arbitrary per-upload metadata of type `T` that the
 * caller wants to keep — content hashes, sizes, server-side IDs, anything.
 * [HlsUploadHelper.run] drives the full HLS pipeline: every segment (and every media
 * playlist, after rewriting) is handed to the uploader in order; the returned
 * [HlsUploadResult.masterPlaylist] is already rewritten to point at the URLs the uploader
 * returned; [HlsUploadResult.uploads] surfaces the entire upload timeline so consumers can
 * look up any individual upload's URL and metadata by its `suggestedFilename`.
 *
 * The helper does **not** upload the master playlist itself — the caller publishes the
 * returned string however they want. If you want to upload it via the same pipeline, one
 * more `uploader(tempFile, "master.m3u8")` call is all it takes.
 *
 * **Threading:**
 * - Segment uploads (init + media segments) run serially during `HlsListener.onSegmentReady`,
 *   which the transcoder dispatches on `Dispatchers.Default`. The orchestrator wraps each
 *   uploader call in `runBlocking(Dispatchers.IO)`, so consumer code always executes on the
 *   IO dispatcher. Blocking a `Default` thread is acceptable because the next segment can't
 *   start encoding until the current one's callback returns.
 * - Media-playlist rewriting and upload happen **after** the transcoder has finished
 *   (inside [HlsUploadOrchestrator.completeUpload]). Each rendition's media playlist is
 *   uploaded in parallel via `async`/`awaitAll` on `Dispatchers.IO`; `Dispatchers.Main` is
 *   never blocked.
 * - Overall order: lowest rendition first, init segment before media segments, then all
 *   media playlists concurrently.
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
 * Example with per-upload metadata:
 * ```
 * data class UploadedBlob(val sha256: String, val sizeBytes: Long)
 *
 * val result: HlsUploadResult<UploadedBlob> = HlsUploadHelper.run(
 *     context = context,
 *     uri = videoUri,
 *     config = HlsConfig(),
 *     listener = progressListener,
 * ) { file, name ->
 *     val contentType = if (name.endsWith(".m3u8")) {
 *         HlsContentTypes.HLS_PLAYLIST
 *     } else {
 *         HlsContentTypes.FMP4_SEGMENT
 *     }
 *     val blob = myUploader.upload(file, contentType = contentType)
 *     HlsUploaded(url = blob.url, metadata = UploadedBlob(blob.sha256, blob.size))
 * }
 * // result.uploads["720p.mp4"] carries the combined rendition's sha256/size for imeta tags.
 * // result.masterPlaylist is already rewritten — publish it however you want.
 * ```
 *
 * Example without metadata (use `Unit` for `T`):
 * ```
 * val result: HlsUploadResult<Unit> = HlsUploadHelper.run(context, videoUri) { file, name ->
 *     HlsUploaded(url = myUploader.upload(file).url, metadata = Unit)
 * }
 * ```
 */
object HlsUploadHelper {
    /**
     * Transcode [uri] into an HLS ladder and upload every segment via [uploader]. Returns
     * the rewritten master playlist along with per-rendition summaries and a per-upload
     * map keyed by `suggestedFilename`. See class docs for threading, error handling,
     * progress reporting, partial-success semantics, and the key-format / iteration-order
     * contract on [HlsUploadResult.uploads].
     *
     * @param listener optional [HlsListener] that observes the raw `HlsPreparer` event
     *   stream (rendition start, per-segment progress, etc.). Every callback is forwarded
     *   after the helper's own bookkeeping. Useful for driving a progress UI.
     * @param uploader called for every segment and rewritten media playlist. Must return
     *   an [HlsUploaded] wrapper carrying the upload URL plus caller-controlled metadata
     *   of type [T]. Callers who only need URLs use `T = Unit`.
     */
    suspend fun <T> run(
        context: Context,
        uri: Uri,
        config: HlsConfig = HlsConfig(),
        listener: HlsListener? = null,
        uploader: suspend (file: File, suggestedFilename: String) -> HlsUploaded<T>,
    ): HlsUploadResult<T> =
        coroutineScope {
            val orchestrator = HlsUploadOrchestrator(context, uploader, listener)
            @Suppress("TooGenericExceptionCaught")
            try {
                val preparerJob =
                    HlsPreparer.start(
                        context = context,
                        uri = uri,
                        config = config,
                        listener = orchestrator,
                    )
                preparerJob.join()
                orchestrator.completeUpload()
            } catch (e: Throwable) {
                HlsPreparer.cancel()
                throw e
            }
        }
}
