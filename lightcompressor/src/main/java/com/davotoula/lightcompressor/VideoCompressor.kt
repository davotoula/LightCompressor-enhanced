package com.davotoula.lightcompressor

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.WorkerThread
import com.davotoula.lightcompressor.compressor.Compressor.compressVideo
import com.davotoula.lightcompressor.config.Configuration
import com.davotoula.lightcompressor.config.HdrMode
import com.davotoula.lightcompressor.config.StorageConfiguration
import com.davotoula.lightcompressor.hdr.ColorInfoReader
import com.davotoula.lightcompressor.hdr.HdrInput
import com.davotoula.lightcompressor.hdr.HdrProbe
import com.davotoula.lightcompressor.hdr.MediaCodecHdrCapabilities
import com.davotoula.lightcompressor.hdr.hdrProbeOf
import com.davotoula.lightcompressor.video.Result
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.ensureActive
import java.io.File

enum class VideoQuality {
    VERY_HIGH,
    HIGH,
    MEDIUM,
    LOW,
    VERY_LOW,
}

enum class VideoCodec(
    val mimeType: String,
) {
    H264("video/avc"),
    H265("video/hevc"),
}

private const val PLACEHOLDER_VIDEO_NAME = "video"

object VideoCompressor : CoroutineScope by MainScope() {
    private val batches = CompressionBatches()

    /**
     * This function compresses a given list of [uris] of video files and writes the compressed
     * video file at [SharedStorageConfiguration.saveAt] directory, or at [AppSpecificStorageConfiguration.subFolderName]
     *
     * The source videos should be provided content uris.
     *
     * Only [sharedStorageConfiguration] or [appSpecificStorageConfiguration] must be specified at a
     * time. Passing both will throw an Exception.
     *
     * @param [context] the application context.
     * @param [uris] the list of content Uris of the video files.
     * @param [isStreamable] determines if the output video should be prepared for streaming.
     * @param [sharedStorageConfiguration] configuration for the path directory where the compressed
     * videos will be saved, and the name of the file
     * @param [appSpecificStorageConfiguration] configuration for the path directory where the compressed
     * videos will be saved, the name of the file, and any sub-folders name. The library won't create the subfolder
     * and will throw an exception if the subfolder does not exist.
     * @param [listener] a compression listener that listens to compression [CompressionListener.onStart],
     * [CompressionListener.onProgress], [CompressionListener.onFailure], [CompressionListener.onSuccess]
     * and if the compression was [CompressionListener.onCancelled] (no output file remains for a cancelled video)
     * @param [configureWith] to allow add video compression configuration that could be:
     * [Configuration.quality] to allow choosing a video quality that can be [VideoQuality.LOW],
     * [VideoQuality.MEDIUM], [VideoQuality.HIGH], and [VideoQuality.VERY_HIGH].
     * This defaults to [VideoQuality.MEDIUM]
     * [Configuration.isMinBitrateCheckEnabled] to determine if the checking for a minimum bitrate threshold
     * before compression is enabled or not. This default to `true`
     * [Configuration.videoBitrateInMbps] which is a custom bitrate for the video in Mbps. You might consider setting
     * [Configuration.isMinBitrateCheckEnabled] to `false` if your bitrate is less than 2000000.
     * [Configuration.videoBitrateInBps] which is a custom bitrate for the video in bps. This provides more
     * granular control than Mbps and takes precedence over videoBitrateInMbps when both are specified.
     *  * [Configuration.keepOriginalResolution] to keep the original video height and width when compressing.
     * This defaults to `false`
     * [Configuration.videoHeight] which is a custom height for the video. Must be specified with [Configuration.videoWidth]
     * [Configuration.videoWidth] which is a custom width for the video. Must be specified with [Configuration.videoHeight]
     * @return a handle that cancels this call's videos and no others
     */
    @JvmStatic
    @JvmOverloads
    fun start(
        context: Context,
        uris: List<Uri>,
        isStreamable: Boolean = false,
        storageConfiguration: StorageConfiguration,
        configureWith: Configuration,
        listener: CompressionListener,
    ): CompressionHandle {
        // Only one is allowed
        assert(configureWith.videoNames.size == uris.size)

        return doVideoCompression(
            context,
            uris,
            isStreamable,
            storageConfiguration,
            configureWith,
            listener,
        )
    }

    /**
     * Cancels every video of every [start] call still running, including videos that haven't
     * started yet. Each of them gets [CompressionListener.onCancelled] unless it has already
     * finished. A [start] call made afterwards is unaffected. To cancel one [start] call only, use
     * the [CompressionHandle] it returns.
     */
    @JvmStatic
    fun cancel() {
        batches.cancelAll()
    }

    /**
     * Reads the colour tags of the video at [uri] and says whether compression could keep its HDR:
     * [HdrProbe.canKeepHdr] answers for [VideoCodec.H265] with [HdrMode.KEEP_IF_SUPPORTED] (H.264
     * output is always tone mapped). Opens the file with a MediaExtractor, so call it off the main
     * thread. Never throws: unreadable input reports [HdrInput.SDR], which is also what compression
     * would assume for it.
     */
    @JvmStatic
    @WorkerThread
    fun probeHdr(
        context: Context,
        uri: Uri,
    ): HdrProbe = hdrProbeOf(ColorInfoReader.read(context, uri), MediaCodecHdrCapabilities())

    private fun doVideoCompression(
        context: Context,
        uris: List<Uri>,
        isStreamable: Boolean,
        storageConfiguration: StorageConfiguration,
        configuration: Configuration,
        listener: CompressionListener,
    ): CompressionHandle =
        batches.launch(
            count = uris.size,
            onResult = { i, result: Result ->
                if (result.success) {
                    listener.onSuccess(i, result.size, result.path)
                } else {
                    listener.onFailure(i, result.failureMessage ?: "An error has occurred!")
                }
            },
            onFailure = listener::onFailure,
            onCancelled = listener::onCancelled,
        ) { i ->
            val path = getMediaPath(context, uris[i])

            val desFile =
                saveVideoFile(
                    context,
                    path,
                    storageConfiguration,
                    configuration.videoNames[i],
                    shouldSave = false,
                )

            // A video cancelled while its path was resolving ends with onCancelled alone.
            ensureActive()
            listener.onStart(i)
            val result =
                desFile.deletedIfCancelled {
                    compressVideo(
                        i,
                        context,
                        uris[i],
                        desFile.path,
                        isStreamable,
                        configuration,
                        progressListener(listener),
                    )
                }

            if (result.success) {
                val savedFile =
                    result.path?.let {
                        saveVideoFile(
                            context,
                            it,
                            storageConfiguration,
                            configuration.videoNames[i],
                            shouldSave = true,
                        )
                    }
                result.copy(path = savedFile?.path)
            } else {
                result
            }
        }

    private fun progressListener(listener: CompressionListener) =
        object : CompressionProgressListener {
            override fun onProgressChanged(
                index: Int,
                percent: Float,
            ) {
                listener.onProgress(index, percent)
            }

            // CompressionBatches reports onCancelled once the video's coroutine ends.
            override fun onProgressCancelled(index: Int) = Unit
        }

    private fun saveVideoFile(
        context: Context,
        filePath: String,
        storageConfiguration: StorageConfiguration,
        videoName: String,
        shouldSave: Boolean,
    ): File =
        storageConfiguration.createFileToSave(
            context,
            File(filePath),
            validatedFileName(videoName),
            shouldSave,
        )

    // Only names the source for StorageConfiguration.createFileToSave (see its KDoc); compression
    // reads the Uri itself, so a Uri with no MediaStore path gets a placeholder.
    private fun getMediaPath(
        context: Context,
        uri: Uri,
    ): String =
        runCatching {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)
                ?.use { cursor ->
                    cursor.moveToFirst()
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
                }
        }.onFailure { Log.w("VideoCompressor", "No MediaStore path for $uri", it) }
            .getOrNull()
            ?.takeUnless { it.isBlank() }
            ?: File(context.cacheDir, PLACEHOLDER_VIDEO_NAME).path

    internal fun validatedFileName(name: String): String = if (name.contains("mp4")) name else "$name.mp4"
}
