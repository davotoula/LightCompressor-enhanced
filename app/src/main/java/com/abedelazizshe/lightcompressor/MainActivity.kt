package com.abedelazizshe.lightcompressor

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.abedelazizshe.lightcompressor.databinding.ActivityMainBinding
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCodec
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import com.abedelazizshe.lightcompressorlibrary.config.VideoResizer
import com.abedelazizshe.lightcompressorlibrary.config.SaveLocation
import com.abedelazizshe.lightcompressorlibrary.config.SharedStorageConfiguration
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils
import kotlinx.coroutines.launch
import androidx.core.net.toUri

/**
 * Created by AbedElaziz Shehadeh on 26 Jan, 2020
 * elaziz.shehadeh@gmail.com
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    companion object {
        const val REQUEST_SELECT_VIDEO = 0
        const val REQUEST_CAPTURE_VIDEO = 1
    }

    private val uris = mutableListOf<Uri>()
    private val data = mutableListOf<VideoDetailsModel>()
    private lateinit var adapter: RecyclerViewAdapter
    private val originalVideoSizes = mutableMapOf<Int, Long>()


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setReadStoragePermission()

        binding.pickVideo.setOnClickListener {
            pickVideo()
        }

        binding.recordVideo.setOnClickListener {
            dispatchTakeVideoIntent()
        }

        binding.cancel.setOnClickListener {
            VideoCompressor.cancel()
        }

        val recyclerview = findViewById<RecyclerView>(R.id.recyclerview)
        recyclerview.layoutManager = LinearLayoutManager(this)
        adapter = RecyclerViewAdapter(applicationContext, data)
        recyclerview.adapter = adapter

        // Handle shared videos from other apps
        handleSharedIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleSharedIntent(intent)
    }

    private fun handleSharedIntent(intent: Intent?) {
        Log.i("MainActivity", "handleSharedIntent called with action: ${intent?.action}, type: ${intent?.type}")

        when (intent?.action) {
            Intent.ACTION_SEND -> {
                // Handle single video share
                if (intent.type?.startsWith("video/") == true || intent.hasExtra(Intent.EXTRA_STREAM)) {
                    val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(Intent.EXTRA_STREAM)
                    }
                    uri?.let {
                        Log.i("MainActivity", "Received shared video: $it")
                        android.widget.Toast.makeText(
                            this,
                            getString(R.string.shared_video_received, 1),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        reset()
                        uris.add(it)
                        processVideo()
                    }
                } else {
                    Log.w("MainActivity", "ACTION_SEND received but type doesn't match video or no EXTRA_STREAM")
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // Handle multiple video share
                Log.i("MainActivity", "Processing ACTION_SEND_MULTIPLE")
                val sharedUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }

                if (sharedUris != null && sharedUris.isNotEmpty()) {
                    Log.i("MainActivity", "Received ${sharedUris.size} shared videos")
                    sharedUris.forEachIndexed { index, uri ->
                        Log.i("MainActivity", "  Shared URI[$index]: $uri")
                    }
                    android.widget.Toast.makeText(
                        this,
                        getString(R.string.shared_video_received, sharedUris.size),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    reset()
                    uris.addAll(sharedUris)
                    Log.i("MainActivity", "After adding, uris list size: ${uris.size}")
                    processVideo()
                } else {
                    Log.w("MainActivity", "ACTION_SEND_MULTIPLE received but no URIs found or list is empty")
                }
            }
        }
    }

    //Pick a video file from device
    private fun pickVideo() {
        val intent = Intent()
        intent.apply {
            type = "video/*"
            action = Intent.ACTION_PICK
        }
        intent.putExtra(
            Intent.EXTRA_ALLOW_MULTIPLE,
            true
        )
        startActivityForResult(Intent.createChooser(intent, "Select video"), REQUEST_SELECT_VIDEO)
    }

    private fun dispatchTakeVideoIntent() {
        Intent(MediaStore.ACTION_VIDEO_CAPTURE).also { takeVideoIntent ->
            takeVideoIntent.resolveActivity(packageManager)?.also {
                startActivityForResult(takeVideoIntent, REQUEST_CAPTURE_VIDEO)
            }
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onActivityResult(requestCode: Int, resultCode: Int, intent: Intent?) {

        reset()

        if (resultCode == RESULT_OK)
            if (requestCode == REQUEST_SELECT_VIDEO || requestCode == REQUEST_CAPTURE_VIDEO) {
                handleResult(intent)
            }

        super.onActivityResult(requestCode, resultCode, intent)
    }

    private fun handleResult(data: Intent?) {
        val clipData: ClipData? = data?.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                val videoItem = clipData.getItemAt(i)
                uris.add(videoItem.uri)
            }
            processVideo()
        } else if (data != null && data.data != null) {
            val uri = data.data
            uris.add(uri!!)
            processVideo()
        }
    }

    private fun reset() {
        uris.clear()
        binding.mainContents.visibility = View.GONE
        val itemCount = data.size
        data.clear()
        if (itemCount > 0) {
            adapter.notifyItemRangeRemoved(0, itemCount)
        }
        originalVideoSizes.clear()
    }

    private fun getOriginalVideoSize(uri: Uri): Long {
        return try {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize
            } ?: 0L
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to get original video size for $uri", e)
            0L
        }
    }

    private fun setReadStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.READ_MEDIA_VIDEO,
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.READ_MEDIA_VIDEO
                    )
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.READ_MEDIA_VIDEO),
                        1
                    )
                }
            }
        } else {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                if (!ActivityCompat.shouldShowRequestPermissionRationale(
                        this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    )
                ) {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                        1
                    )
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun processVideo() {
        binding.mainContents.visibility = View.VISIBLE

        // Get the user-entered bitrate value in kbps and convert to bps
        val bitrateText = binding.bitrateInput.text.toString()
        var videoBitrateInKbps = if (bitrateText.isNotEmpty()) {
            try {
                bitrateText.toLong()
            } catch (e: NumberFormatException) {
                Log.w("MainActivity", "Invalid bitrate input: $bitrateText, using default 1500 kbps")
                1500L // Default fallback value in kbps
            }
        } else {
            1500L // Default fallback value in kbps
        }

        // Validate bitrate range (200 - 100000 kbps)
        if (videoBitrateInKbps < 200) {
            Log.w("MainActivity", "Bitrate $videoBitrateInKbps kbps is below minimum (200 kbps), clamping to 200")
            android.widget.Toast.makeText(
                this,
                "Bitrate too low. Minimum is 200 kbps. Using 200 kbps.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            videoBitrateInKbps = 200L
        } else if (videoBitrateInKbps > 100000) {
            Log.w("MainActivity", "Bitrate $videoBitrateInKbps kbps is above maximum (100000 kbps), clamping to 100000")
            android.widget.Toast.makeText(
                this,
                "Bitrate too high. Maximum is 100000 kbps. Using 100000 kbps.",
                android.widget.Toast.LENGTH_LONG
            ).show()
            videoBitrateInKbps = 100000L
        }

        val videoBitrateInBps = videoBitrateInKbps * 1000 // Convert kbps to bps

        // Get the user-entered resize value
        val resizeText = binding.resizeInput.text.toString()
        val maxResolution = if (resizeText.isNotEmpty()) {
            try {
                resizeText.toDouble()
            } catch (e: NumberFormatException) {
                Log.w("MainActivity", "Invalid resize input: $resizeText, using default 1280")
                1280.0 // Default fallback value
            }
        } else {
            1280.0 // Default fallback value
        }

        // Get the selected codec with automatic fallback to H.264 if H.265 is not supported
        val selectedCodec = when (binding.codecRadioGroup.checkedRadioButtonId) {
            R.id.radioH265 -> {
                if (CompressorUtils.isHevcEncodingSupported()) {
                    VideoCodec.H265
                } else {
                    Log.w("MainActivity", "H.265 selected but not supported on this device, falling back to H.264")
                    binding.radioH264.isChecked = true
                    android.widget.Toast.makeText(
                        this,
                        "H.265 not supported on this device. Using H.264 instead.",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                    VideoCodec.H264
                }
            }
            R.id.radioH264 -> VideoCodec.H264
            else -> {
                Log.e("MainActivity", "Unknown codec radio button: ${binding.codecRadioGroup.checkedRadioButtonId}")
                VideoCodec.H264 // Safe default
            }
        }

        // Get the selected streamable setting
        val isStreamable = when (binding.streamableRadioGroup.checkedRadioButtonId) {
            R.id.radioStreamableYes -> true
            R.id.radioStreamableNo -> false
            else -> {
                Log.e("MainActivity", "Unknown streamable radio button: ${binding.streamableRadioGroup.checkedRadioButtonId}")
                true // Safe default
            }
        }

        Log.i("MainActivity", "Using bitrate: $videoBitrateInKbps kbps ($videoBitrateInBps bps)")
        Log.i("MainActivity", "Using max resolution: ${maxResolution.toInt()}px (long edge)")
        Log.i("MainActivity", "Using codec: ${selectedCodec.name}")
        Log.i("MainActivity", "Using streamable: $isStreamable")
        Log.i("MainActivity", "Starting compression for ${uris.size} video(s)")
        uris.forEachIndexed { index, uri ->
            Log.i("MainActivity", "  Video[$index]: $uri")
        }

        lifecycleScope.launch {
            VideoCompressor.start(
                context = applicationContext,
                uris,
                isStreamable = isStreamable,
                storageConfiguration = SharedStorageConfiguration(
                    saveAt = SaveLocation.MOVIES,
                    subFolderName = "my-demo-videos"
                ),
                configureWith = Configuration.withBitrateInBps(
                    // Quality is ignored when videoBitrateInBps is provided, but required by API
                    videoBitrateInBps = videoBitrateInBps,
                    videoNames = uris.map { uri -> uri.pathSegments.last() },
                    isMinBitrateCheckEnabled = false,
                    resizer = VideoResizer.limitSize(maxResolution),
                    videoCodec = selectedCodec
                ),
                listener = object : CompressionListener {
                    override fun onProgress(index: Int, percent: Float) {
                        //Update UI
                        if (percent <= 100)
                            runOnUiThread {
                                data[index] = VideoDetailsModel(
                                    "",
                                    uris[index],
                                    "",
                                    percent
                                )
                                adapter.notifyItemChanged(index)
                            }
                    }

                    override fun onStart(index: Int) {
                        Log.i("MainActivity", "onStart called for index $index (URI: ${uris[index]})")
                        // Store original video size for comparison later
                        val originalSize = getOriginalVideoSize(uris[index])
                        originalVideoSizes[index] = originalSize
                        Log.i("MainActivity", "Original video size for index $index: ${getFileSize(originalSize)}")

                        data.add(
                            index,
                            VideoDetailsModel("", uris[index], "")
                        )
                        runOnUiThread {
                            adapter.notifyItemInserted(index)
                        }

                    }

                    override fun onSuccess(index: Int, size: Long, path: String?) {
                        Log.i("MainActivity", "onSuccess called for index $index, size: ${getFileSize(size)}, path: $path")
                        val originalSize = originalVideoSizes[index] ?: 0L

                        // Check if compressed video is larger than original
                        if (size > originalSize && originalSize > 0) {
                            Log.w("MainActivity", "Compressed video ($size bytes) is larger than original ($originalSize bytes). Not saving.")

                            // Delete the compressed file if it exists
                            path?.let { filePath ->
                                try {
                                    // Try to delete as a regular file first
                                    val file = java.io.File(filePath)
                                    if (file.exists() && file.delete()) {
                                        Log.i("MainActivity", "Deleted compressed file: $filePath")
                                    } else {
                                        // If it's a content URI or file doesn't exist via File API,
                                        // try deleting via ContentResolver
                                        try {
                                            val uri = filePath.toUri()
                                            val deleted = contentResolver.delete(uri, null, null)
                                            if (deleted > 0) {
                                                Log.i("MainActivity", "Deleted compressed file via ContentResolver: $filePath")
                                            }
                                        } catch (e: Exception) {
                                            Log.w("MainActivity", "Could not delete compressed file: $filePath", e)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e("MainActivity", "Failed to delete compressed file: $filePath", e)
                                }
                            }

                            // Show toast to user
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.video_not_saved_larger_toast, getFileSize(size), getFileSize(originalSize)),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()

                                // Update UI to show failure
                                data[index] = VideoDetailsModel(
                                    null,
                                    uris[index],
                                    getString(R.string.video_not_saved_status),
                                    0F
                                )
                                adapter.notifyItemChanged(index)
                            }
                        } else {
                            // Normal success case - compressed file is smaller or equal
                            Log.i("MainActivity", "Compression successful. Original: ${getFileSize(originalSize)}, Compressed: ${getFileSize(size)}")
                            data[index] = VideoDetailsModel(
                                path,
                                uris[index],
                                getFileSize(size),
                                100F
                            )
                            runOnUiThread {
                                adapter.notifyItemChanged(index)
                            }
                        }
                    }

                    override fun onFailure(index: Int, failureMessage: String) {
                        Log.e("MainActivity", "onFailure called for index $index: $failureMessage")
                    }

                    override fun onCancelled(index: Int) {
                        Log.w("MainActivity", "onCancelled called for index $index")
                        // make UI changes, cleanup, etc
                    }
                },
            )
        }
    }
}
