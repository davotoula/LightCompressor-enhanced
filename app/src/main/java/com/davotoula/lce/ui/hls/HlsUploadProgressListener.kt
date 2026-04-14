package com.davotoula.lce.ui.hls

import com.davotoula.lightcompressor.hls.HlsRenditionSummary
import com.davotoula.lightcompressor.hls.Rendition
import com.davotoula.lightcompressor.hls.SimpleHlsListener

private const val PROGRESS_COMPLETE_PERCENT = 100

/**
 * Progress-only `HlsListener` for the "Prepare HLS + Upload" smoke test. Mirrors the UI
 * state updates from `HlsTestSession` (rendition rows going Pending → Active → Complete
 * with per-segment progress) without touching the disk — `HlsUploadHelper`'s uploader
 * lambda already handles file persistence. The viewmodel owns terminal state via its own
 * try/catch around `HlsUploadTestRunner.run`, so this listener deliberately skips
 * `onComplete`, `onFailure`, and `onCancelled`.
 */
class HlsUploadProgressListener(
    private val updateState: ((HlsTestState?) -> HlsTestState?) -> Unit,
) : SimpleHlsListener() {
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

    override fun onProgress(
        rendition: Rendition,
        percent: Float,
    ) {
        val intPercent = percent.toInt()
        updateRow(rendition) { row ->
            if (row.progressPercent == intPercent) row else row.copy(progressPercent = intPercent)
        }
    }

    override fun onRenditionComplete(
        rendition: Rendition,
        summary: HlsRenditionSummary,
    ) {
        updateRow(rendition) { row ->
            row.copy(
                status = HlsRenditionStatus.Complete,
                progressPercent = PROGRESS_COMPLETE_PERCENT,
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
}
