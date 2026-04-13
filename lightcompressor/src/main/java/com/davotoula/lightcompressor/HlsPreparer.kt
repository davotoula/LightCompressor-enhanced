package com.davotoula.lightcompressor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.davotoula.lightcompressor.hls.HlsConfig
import com.davotoula.lightcompressor.hls.HlsError
import com.davotoula.lightcompressor.hls.HlsListener
import com.davotoula.lightcompressor.hls.HlsTranscoder
import com.davotoula.lightcompressor.hls.PlaylistGenerator
import com.davotoula.lightcompressor.hls.Rendition
import com.davotoula.lightcompressor.hls.RenditionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

/**
 * Public entry point for HLS VOD preparation.
 *
 * Given a local video URI, transcodes it into multiple resolution renditions
 * as fMP4 segments with HLS playlists. Segments are emitted incrementally
 * via [HlsListener] callbacks.
 *
 * Usage:
 * ```
 * HlsPreparer.start(context, videoUri, HlsConfig()) { ... }
 * ```
 */
object HlsPreparer : CoroutineScope by MainScope() {
    private var currentJob: Job? = null
    private var transcoder: HlsTranscoder? = null

    /**
     * Start HLS preparation for a single video.
     *
     * @param context Android context
     * @param uri source video URI
     * @param config HLS configuration (ladder, codec, segment duration)
     * @param listener callbacks for progress, segments, and completion
     * @return [Job] that can be used to track completion
     */
    @JvmStatic
    fun start(
        context: Context,
        uri: Uri,
        config: HlsConfig = HlsConfig(),
        listener: HlsListener,
    ): Job {
        cancel() // Cancel any running preparation
        val job =
            launch(Dispatchers.IO) {
                prepareHls(context, uri, config, listener)
            }
        currentJob = job
        return job
    }

    /** Cancel any running HLS preparation. */
    @JvmStatic
    fun cancel() {
        transcoder?.isCancelled = true
        currentJob?.cancel()
        currentJob = null
        transcoder = null
    }

    @Suppress("TooGenericExceptionCaught", "NestedBlockDepth", "ReturnCount")
    private suspend fun prepareHls(
        context: Context,
        uri: Uri,
        config: HlsConfig,
        listener: HlsListener,
    ) {
        try {
            // Extract source metadata
            val sourceInfo =
                extractSourceInfo(context, uri) ?: run {
                    withContext(Dispatchers.Main) {
                        listener.onFailure(
                            HlsError("Failed to read source video metadata", emptyList(), emptyList()),
                        )
                    }
                    return
                }

            // Filter ladder to source resolution
            val effectiveLadder = config.ladder.forSource(sourceInfo.shortSide)
            if (effectiveLadder.renditions.isEmpty()) {
                withContext(Dispatchers.Main) {
                    listener.onFailure(
                        HlsError(
                            "No renditions match source resolution (${sourceInfo.shortSide}p)",
                            emptyList(),
                            emptyList(),
                        ),
                    )
                }
                return
            }

            withContext(Dispatchers.Main) {
                listener.onStart(effectiveLadder.renditions.size)
            }

            val hlsTranscoder = HlsTranscoder(context, uri, config)
            transcoder = hlsTranscoder
            val tempDir = File(context.cacheDir, "hls_temp_${System.currentTimeMillis()}")
            tempDir.mkdirs()

            val completed = mutableListOf<RenditionResult>()
            val failed = mutableListOf<Rendition>()

            try {
                for (rendition in effectiveLadder.renditions) {
                    if (hlsTranscoder.isCancelled) {
                        withContext(Dispatchers.Main) { listener.onCancelled() }
                        return
                    }

                    val (actualWidth, actualHeight) =
                        calculateDimensions(
                            rendition,
                            sourceInfo.width,
                            sourceInfo.height,
                        )

                    withContext(Dispatchers.Main) {
                        listener.onRenditionStart(rendition)
                    }

                    val result =
                        withContext(Dispatchers.Default) {
                            hlsTranscoder.encodeRendition(
                                rendition = rendition,
                                actualWidth = actualWidth,
                                actualHeight = actualHeight,
                                listener = listener,
                                tempDir = tempDir,
                            )
                        }

                    if (result != null) {
                        completed.add(result)
                        withContext(Dispatchers.Main) {
                            listener.onRenditionComplete(rendition, result.mediaPlaylist)
                        }
                    } else {
                        failed.add(rendition)
                    }
                }

                if (completed.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        listener.onFailure(
                            HlsError(
                                "All renditions failed",
                                failedRenditions = failed,
                                completedRenditions = emptyList(),
                            ),
                        )
                    }
                } else {
                    val masterPlaylist = PlaylistGenerator().buildMasterPlaylist(completed)
                    withContext(Dispatchers.Main) {
                        listener.onComplete(masterPlaylist)
                    }
                }
            } finally {
                tempDir.deleteRecursively()
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            withContext(Dispatchers.Main) { listener.onCancelled() }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                listener.onFailure(HlsError(e.message ?: "Unknown error", emptyList(), emptyList()))
            }
        } finally {
            transcoder = null
        }
    }

    @Suppress("MagicNumber", "ReturnCount")
    private fun extractSourceInfo(
        context: Context,
        uri: Uri,
    ): SourceInfo? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val width =
                retriever
                    .extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                    )?.toIntOrNull() ?: return null
            val height =
                retriever
                    .extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT,
                    )?.toIntOrNull() ?: return null
            val rotation =
                retriever
                    .extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
                    )?.toIntOrNull() ?: 0

            val (effectiveWidth, effectiveHeight) =
                if (rotation == 90 || rotation == 270) {
                    height to width
                } else {
                    width to height
                }

            SourceInfo(
                width = effectiveWidth,
                height = effectiveHeight,
                shortSide = min(effectiveWidth, effectiveHeight),
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /**
     * Calculate actual output dimensions for a rendition, preserving source aspect ratio.
     * The rendition's resolution targets the short side.
     */
    internal fun calculateDimensions(
        rendition: Rendition,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Pair<Int, Int> {
        val targetShortSide = rendition.resolution.shortSide
        val isPortrait = sourceHeight > sourceWidth
        val aspectRatio = sourceWidth.toDouble() / sourceHeight.toDouble()

        val (width, height) =
            if (isPortrait) {
                val w = targetShortSide
                val h = (w / aspectRatio).toInt()
                w to h
            } else {
                val h = targetShortSide
                val w = (h * aspectRatio).toInt()
                w to h
            }

        // Round to even (MediaCodec requirement)
        return (width and 1.inv()) to (height and 1.inv())
    }

    private data class SourceInfo(
        val width: Int,
        val height: Int,
        val shortSide: Int,
    )
}
