package com.davotoula.lce.ui.hls

import com.davotoula.lightcompressor.hls.HlsError
import com.davotoula.lightcompressor.hls.HlsListener
import com.davotoula.lightcompressor.hls.HlsSegment
import com.davotoula.lightcompressor.hls.Rendition
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

private const val PROGRESS_COMPLETE_PERCENT = 100
private const val SEGMENT_FILENAME_FORMAT = "segment_%03d.m4s"

const val HLS_TERMINAL_SUCCESS = "success"
const val HLS_TERMINAL_FAILURE = "failure"
const val HLS_TERMINAL_CANCELLED = "cancelled"

/**
 * `HlsListener` implementation used by the sample app's "Prepare HLS" affordance.
 *
 * Persists segments and playlists under [rootDir] in the layout the playlists already
 * reference. The exact layout depends on the library mode:
 * - Multi-file: `<label>/init.mp4`, `<label>/segment_NNN.m4s`, `<label>/media.m3u8`
 * - Single-file: `<label>/<label>.mp4` (init + every segment), `<label>/media.m3u8`
 * In both cases the master playlist is `master.m3u8` at the root and the result is
 * directly playable by `ExoPlayer`.
 *
 * State mutations go through [updateState], which the caller wires to a thread-safe
 * sink (e.g. `MutableStateFlow.update`). No explicit dispatcher hop is needed even
 * though `onSegmentReady` and `onProgress` run on `Dispatchers.Default` while the rest
 * run on Main.
 *
 * [onTerminal] is invoked exactly once per session with one of [HLS_TERMINAL_SUCCESS],
 * [HLS_TERMINAL_FAILURE], or [HLS_TERMINAL_CANCELLED]. It is gated so that the
 * IO-failure → `HlsPreparer.cancel()` → `onCancelled` race only emits a single result.
 */
class HlsTestSession(
    private val rootDir: File,
    private val updateState: ((HlsTestState?) -> HlsTestState?) -> Unit,
    private val onIoFailure: () -> Unit,
    private val onTerminal: (String) -> Unit = {},
) : HlsListener {
    private val terminalReported = AtomicBoolean(false)

    private fun reportTerminal(status: String) {
        if (!terminalReported.compareAndSet(false, true)) return
        onTerminal(status)
    }

    override fun onStart(renditionCount: Int) {
        updateState { current ->
            current?.copy(
                isRunning = true,
                renditions = current.renditions.take(renditionCount),
            )
        }
    }

    override fun onRenditionStart(rendition: Rendition) {
        updateRow(rendition) { row ->
            row.copy(status = HlsRenditionStatus.Active, progressPercent = 0)
        }
    }

    override fun onSegmentReady(
        rendition: Rendition,
        segment: HlsSegment,
    ) {
        try {
            val targetDir = File(rootDir, rendition.resolution.label)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IOException("Could not create $targetDir")
            }
            val targetFile =
                when {
                    segment.isCombinedRendition ->
                        File(targetDir, "${rendition.resolution.label}.mp4")
                    segment.isInitSegment -> File(targetDir, "init.mp4")
                    else -> File(targetDir, SEGMENT_FILENAME_FORMAT.format(segment.index))
                }
            // Try rename first (O(1) on same filesystem). Fall back to copy when the source
            // and destination straddle filesystems (e.g. cacheDir on a separate partition).
            if (targetFile.exists()) targetFile.delete()
            if (!segment.file.renameTo(targetFile)) {
                segment.file.copyTo(targetFile, overwrite = true)
            }
        } catch (e: IOException) {
            failWithIoError("Failed to write segment ${segment.index}: ${e.message}")
            return
        }

        if (segment.isInitSegment) return

        // In single-file mode, isCombinedRendition segments count as 1 regardless of
        // how many segments the encoding actually produced (the count is for UI display only).
        updateRow(rendition) { row ->
            row.copy(segmentCount = row.segmentCount + 1)
        }
    }

    override fun onRenditionComplete(
        rendition: Rendition,
        playlist: String,
    ) {
        try {
            val targetDir = File(rootDir, rendition.resolution.label)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IOException("Could not create $targetDir")
            }
            File(targetDir, "media.m3u8").writeText(playlist)
        } catch (e: IOException) {
            failWithIoError("Failed to write media.m3u8: ${e.message}")
            return
        }

        updateRow(rendition) { row ->
            row.copy(
                status = HlsRenditionStatus.Complete,
                progressPercent = PROGRESS_COMPLETE_PERCENT,
            )
        }
    }

    override fun onComplete(masterPlaylist: String) {
        val masterFile: File
        try {
            if (!rootDir.exists() && !rootDir.mkdirs()) {
                throw IOException("Could not create $rootDir")
            }
            masterFile = File(rootDir, "master.m3u8")
            masterFile.writeText(masterPlaylist)
        } catch (e: IOException) {
            failWithIoError("Failed to write master.m3u8: ${e.message}")
            return
        }

        updateState { current ->
            current?.copy(
                isRunning = false,
                terminal = HlsTerminal.Succeeded(masterPlaylistPath = masterFile.absolutePath),
            )
        }
        reportTerminal(HLS_TERMINAL_SUCCESS)
    }

    override fun onProgress(
        rendition: Rendition,
        percent: Float,
    ) {
        val intPercent = percent.toInt()
        updateRow(rendition) { row ->
            if (row.progressPercent == intPercent) row else row.copy(progressPercent = intPercent)
        }
    }

    override fun onFailure(error: HlsError) {
        updateState { current ->
            current?.copy(
                isRunning = false,
                terminal = HlsTerminal.Failed(error.message),
                renditions =
                    current.renditions.map { row ->
                        if (row.status == HlsRenditionStatus.Active) {
                            row.copy(status = HlsRenditionStatus.Failed)
                        } else {
                            row
                        }
                    },
            )
        }
        reportTerminal(HLS_TERMINAL_FAILURE)
    }

    override fun onCancelled() {
        updateState { current ->
            current?.copy(
                isRunning = false,
                terminal = current.terminal ?: HlsTerminal.Cancelled,
            )
        }
        reportTerminal(HLS_TERMINAL_CANCELLED)
    }

    private fun updateRow(
        rendition: Rendition,
        transform: (HlsRenditionState) -> HlsRenditionState,
    ) {
        updateState { current ->
            current?.copy(
                renditions =
                    current.renditions.map { row ->
                        if (row.label == rendition.resolution.label) transform(row) else row
                    },
            )
        }
    }

    private fun failWithIoError(message: String) {
        updateState { current ->
            current?.copy(
                isRunning = false,
                terminal = HlsTerminal.Failed(message),
            )
        }
        reportTerminal(HLS_TERMINAL_FAILURE)
        onIoFailure()
    }
}
