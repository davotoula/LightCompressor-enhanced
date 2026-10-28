package com.davotoula.lightcompressor

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.davotoula.lightcompressor.compressor.Compressor.compressVideo
import com.davotoula.lightcompressor.config.Configuration
import com.davotoula.lightcompressor.config.StorageConfiguration
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
     * and if the compression was [CompressionListener.onCancelled]
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
    ) {
        // Only one is allowed
        assert(configureWith.videoNames.size == uris.size)

        doVideoCompression(
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
     * finished. A [start] call made afterwards is unaffected.
     */
    @JvmStatic
    fun cancel() {
        batches.cancelAll()
    }

    private fun doVideoCompression(
        context: Context,
        uris: List<Uri>,
        isStreamable: Boolean,
        storageConfiguration: StorageConfiguration,
        configuration: Configuration,
        listener: CompressionListener,
    ) {
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
                    isStreamable,
                    configuration.videoNames[i],
                    shouldSave = false,
                )

            val streamableFile =
                if (isStreamable) {
                    saveVideoFile(
                        context,
                        path,
                        storageConfiguration,
                        null,
                        configuration.videoNames[i],
                        shouldSave = false,
                    )
                } else {
                    null
                }

            // A video cancelled while its path was resolving ends with onCancelled alone.
            ensureActive()
            listener.onStart(i)
            val result =
                compressVideo(
                    i,
                    context,
                    uris[i],
                    desFile.path,
                    streamableFile?.path,
                    configuration,
                    progressListener(listener),
                )

            if (result.success) {
                val savedFile =
                    result.path?.let {
                        saveVideoFile(
                            context,
                            it,
                            storageConfiguration,
                            isStreamable,
                            configuration.videoNames[i],
                            shouldSave = true,
                        )
                    }
                result.copy(path = savedFile?.path)
            } else {
                result
            }
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
        isStreamable: Boolean?,
        videoName: String,
        shouldSave: Boolean,
    ): File =
        storageConfiguration.createFileToSave(
            context,
            File(filePath),
            validatedFileName(
                videoName,
                isStreamable,
            ),
            shouldSave,
        )

    // The path only names the output: it reaches StorageConfiguration.createFileToSave with
    // shouldSave = false, while compression reads the Uri itself. A Uri with no MediaStore file
    // path (file Uris, documents) gets a placeholder that doesn't exist.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun getMediaPath(
        context: Context,
        uri: Uri,
    ): String =
        try {
            context.contentResolver
                .query(uri, arrayOf(MediaStore.Video.Media.DATA), null, null, null)
                ?.use { cursor ->
                    cursor.moveToFirst()
                    cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA))
                }
        } catch (e: Exception) {
            null
        } ?: File(context.cacheDir, PLACEHOLDER_VIDEO_NAME).path

    private fun validatedFileName(
        name: String,
        isStreamable: Boolean?,
    ): String {
        val videoName =
            if (isStreamable == null || !isStreamable) {
                name
            } else {
                "${name}_temp"
            }

        if (!videoName.contains("mp4")) return "$videoName.mp4"
        return videoName
    }
}
