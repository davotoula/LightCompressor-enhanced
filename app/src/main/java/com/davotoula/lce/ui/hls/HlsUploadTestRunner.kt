package com.davotoula.lce.ui.hls

import android.content.Context
import android.net.Uri
import com.davotoula.lightcompressor.hls.HlsConfig
import com.davotoula.lightcompressor.hls.HlsListener
import com.davotoula.lightcompressor.hls.HlsUploadHelper
import com.davotoula.lightcompressor.hls.HlsUploadResult
import com.davotoula.lightcompressor.hls.HlsUploaded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Smoke-tests [HlsUploadHelper] against a local-disk uploader that writes every file to
 * `<rootDir>/<suggestedFilename>` and returns a `file://` URL. The rewritten master
 * playlist is a fully-playable local HLS manifest.
 */
object HlsUploadTestRunner {
    suspend fun run(
        context: Context,
        sourceUri: Uri,
        rootDir: File,
        config: HlsConfig = HlsConfig(),
        listener: HlsListener? = null,
    ): File =
        withContext(Dispatchers.IO) {
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
            masterFile
        }
}
