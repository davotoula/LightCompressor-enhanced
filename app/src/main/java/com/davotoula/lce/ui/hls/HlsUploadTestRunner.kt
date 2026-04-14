package com.davotoula.lce.ui.hls

import android.content.Context
import android.net.Uri
import com.davotoula.lightcompressor.hls.HlsConfig
import com.davotoula.lightcompressor.hls.HlsUploadHelper
import com.davotoula.lightcompressor.hls.HlsUploadResult
import java.io.File

/**
 * Exercises `HlsUploadHelper` against a local-disk uploader. The uploader writes every
 * file to `<rootDir>/<suggestedFilename>` and returns a `file://` URL pointing at the
 * written file — the rewritten master playlist then becomes a fully-playable local HLS
 * manifest, which is a complete smoke test for the helper wiring.
 */
object HlsUploadTestRunner {
    /**
     * Runs the helper and writes the rewritten master playlist to `<rootDir>/master.m3u8`.
     * Returns the absolute path to the master playlist, which the caller can hand to
     * `PlayerScreen` via the same `HlsTerminal.Succeeded(masterPlaylistPath = ...)` state
     * the existing flow uses.
     */
    suspend fun run(
        context: Context,
        sourceUri: Uri,
        rootDir: File,
        config: HlsConfig = HlsConfig(),
    ): File {
        rootDir.deleteRecursively()
        rootDir.mkdirs()

        val result: HlsUploadResult =
            HlsUploadHelper.run(
                context = context,
                uri = sourceUri,
                config = config,
            ) { file, suggestedFilename ->
                val dest = File(rootDir, suggestedFilename)
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
                dest.toURI().toString()
            }

        val masterFile = File(rootDir, "master.m3u8")
        masterFile.writeText(result.masterPlaylist)
        return masterFile
    }
}
