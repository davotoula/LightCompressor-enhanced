package com.davotoula.lce.ui.hls

import com.davotoula.lightcompressor.hls.HlsError
import com.davotoula.lightcompressor.hls.HlsListener
import com.davotoula.lightcompressor.hls.HlsSegment
import com.davotoula.lightcompressor.hls.Rendition
import java.io.File
import java.io.IOException

private const val PROGRESS_COMPLETE_PERCENT = 100
private const val SEGMENT_FILENAME_FORMAT = "segment_%03d.m4s"

/**
 * `HlsListener` implementation used by the sample app's "Prepare HLS" affordance.
 *
 * Persists segments and playlists under [rootDir] in the layout the playlists already
 * reference (`<label>/init.mp4`, `<label>/segment_NNN.m4s`, `<label>/media.m3u8`,
 * `master.m3u8`) so the result is directly playable by `ExoPlayer`.
 *
 * State mutations go through [updateState], which the caller wires to a thread-safe
 * sink (e.g. `MutableStateFlow.update`). No explicit dispatcher hop is needed even
 * though `onSegmentReady` and `onProgress` run on `Dispatchers.Default` while the rest
 * run on Main.
 */
class HlsTestSession(
    private val rootDir: File,
    private val updateState: ((HlsTestState?) -> HlsTestState?) -> Unit,
    private val onIoFailure: () -> Unit,
) : HlsListener {
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
                if (segment.isInitSegment) {
                    File(targetDir, "init.mp4")
                } else {
                    File(targetDir, SEGMENT_FILENAME_FORMAT.format(segment.index))
                }
            segment.file.inputStream().use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: IOException) {
            failWithIoError("Failed to write segment ${segment.index}: ${e.message}")
            return
        }

        if (segment.isInitSegment) return

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
    }

    override fun onProgress(
        rendition: Rendition,
        percent: Float,
    ) {
        updateRow(rendition) { row ->
            row.copy(progressPercent = percent.toInt())
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
    }

    override fun onCancelled() {
        updateState { current ->
            current?.copy(
                isRunning = false,
                terminal = current.terminal ?: HlsTerminal.Cancelled,
            )
        }
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
        onIoFailure()
    }
}
