package com.davotoula.lce.ui.hls

import android.content.Context
import android.net.Uri
import com.davotoula.lightcompressor.hls.HlsConfig
import com.davotoula.lightcompressor.hls.HlsListener
import com.davotoula.lightcompressor.hls.HlsUploadHelper
import com.davotoula.lightcompressor.hls.HlsUploadResult
import com.davotoula.lightcompressor.hls.HlsUploaded
import java.io.File

/**
 * Exercises `HlsUploadHelper` against a local-disk uploader. The uploader writes every
 * file to `<rootDir>/<suggestedFilename>` and returns a `file://` URL pointing at the
 * written file — the rewritten master playlist then becomes a fully-playable local HLS
 * manifest, which is a complete smoke test for the helper wiring.
 *
 * This smoke test uses `Unit` as the `HlsUploaded` metadata type because it has nothing
 * extra to carry back. Real consumers (e.g. Amethyst) use their own metadata type to
 * thread content hashes or sizes through for NIP-71 imeta tags.
 */
object HlsUploadTestRunner {
    /**
     * Runs the helper and writes the rewritten master playlist to `<rootDir>/master.m3u8`.
     * Returns the absolute path to the master playlist, which the caller can hand to
     * `PlayerScreen` via the same `HlsTerminal.Succeeded(masterPlaylistPath = ...)` state
     * the existing flow uses.
     *
     * Pass an optional [listener] to observe per-segment progress from inside the helper.
     */
    suspend fun run(
        context: Context,
        sourceUri: Uri,
        rootDir: File,
        config: HlsConfig = HlsConfig(),
        listener: HlsListener? = null,
    ): File {
        rootDir.deleteRecursively()
        rootDir.mkdirs()

        val result: HlsUploadResult<Unit> =
            HlsUploadHelper.run(
                context = context,
                uri = sourceUri,
                config = config,
                listener = listener,
            ) { file, suggestedFilename ->
                val dest = File(rootDir, suggestedFilename)
                dest.parentFile?.mkdirs()
                file.copyTo(dest, overwrite = true)
                HlsUploaded(url = dest.toURI().toString(), metadata = Unit)
            }

        val masterFile = File(rootDir, "master.m3u8")
        masterFile.writeText(result.masterPlaylist)
        return masterFile
    }
}
