package com.davotoula.lce

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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.abedelazizshe.lightcompressorlibrary.CompressionListener
import com.abedelazizshe.lightcompressorlibrary.VideoCodec
import com.abedelazizshe.lightcompressorlibrary.VideoCompressor
import com.abedelazizshe.lightcompressorlibrary.config.Configuration
import com.abedelazizshe.lightcompressorlibrary.config.SaveLocation
import com.abedelazizshe.lightcompressorlibrary.config.SharedStorageConfiguration
import com.abedelazizshe.lightcompressorlibrary.config.VideoResizer
import com.abedelazizshe.lightcompressorlibrary.utils.CompressorUtils
import com.davotoula.lce.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

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
    private var videosFromSharing = false // Track if videos came from sharing vs file picker
    private var lastSelectionSource = "unknown"
    private var completedVideosCount = 0
    private var currentBatchStartIndex = 0 // Track where current batch starts in data list
    private var selectedPresetButton: android.widget.Button? = null
    private val pickVideos =
        registerForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { pickedUris ->
            if (pickedUris.isNullOrEmpty()) {
                Log.i("MainActivity", "Photo picker returned no selection")
                return@registerForActivityResult
            }

            clearPreviousSelection()
            uris.addAll(pickedUris)
            videosFromSharing = false
            lastSelectionSource = "photo_picker"
            AnalyticsTracker.logVideoSelection(lastSelectionSource, pickedUris.size)
            processVideo()
        }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setReadStoragePermission()

        binding.pickVideo.setOnClickListener {
            if (uris.isNotEmpty()) {
                // Videos already loaded, start compression
                Log.i("MainActivity", "Start button clicked, beginning compression")
                processVideo()
            } else {
                // No videos loaded, pick videos
                Log.i("MainActivity", "Pick Video button clicked")
                pickVideo()
            }
        }

        binding.recordVideo.setOnClickListener {
            dispatchTakeVideoIntent()
        }

        binding.cancel.setOnClickListener {
            if (uris.isNotEmpty()) {
                AnalyticsTracker.logCompressionCancelled(lastSelectionSource, uris.size)
            }
            VideoCompressor.cancel()
        }

        // Resolution preset buttons
        binding.preset4k.setOnClickListener {
            binding.resizeInput.setText("3840")
            updatePresetButtonSelection(binding.preset4k)
            Log.i("MainActivity", "4K preset selected: 3840px")
        }

        binding.preset1080p.setOnClickListener {
            binding.resizeInput.setText("1920")
            updatePresetButtonSelection(binding.preset1080p)
            Log.i("MainActivity", "1080p preset selected: 1920px")
        }

        binding.preset720p.setOnClickListener {
            binding.resizeInput.setText("1280")
            updatePresetButtonSelection(binding.preset720p)
            Log.i("MainActivity", "720p preset selected: 1280px")
        }

        binding.preset540p.setOnClickListener {
            binding.resizeInput.setText("960")
            updatePresetButtonSelection(binding.preset540p)
            Log.i("MainActivity", "540p preset selected: 960px")
        }

        // Auto bitrate button
        binding.bitrateAuto.setOnClickListener {
            calculateAndSetAutoBitrate()
        }

        val recyclerview = findViewById<RecyclerView>(R.id.recyclerview)
        recyclerview.layoutManager = LinearLayoutManager(this)
        adapter = RecyclerViewAdapter(applicationContext, data)
        recyclerview.adapter = adapter

        // Handle shared videos from other apps
        handleSharedIntent(intent)
    }

    private fun updatePresetButtonSelection(button: android.widget.Button) {
        // Reset previous selection
        selectedPresetButton?.let {
            it.setBackgroundColor(android.graphics.Color.TRANSPARENT)
            it.setTextColor(android.graphics.Color.BLACK)
        }

        // Apply selected styling
        button.setBackgroundColor(android.graphics.Color.BLUE)
        button.setTextColor(android.graphics.Color.WHITE)
        selectedPresetButton = button
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
                        videosFromSharing = true // Mark as shared videos
                        lastSelectionSource = "share_sheet"
                        AnalyticsTracker.logVideoSelection(lastSelectionSource, uris.size)
                        showSharedVideosInUI()
                        updateButtonState()
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
                    videosFromSharing = true // Mark as shared videos
                    lastSelectionSource = "share_sheet"
                    AnalyticsTracker.logVideoSelection(lastSelectionSource, uris.size)
                    Log.i("MainActivity", "After adding, uris list size: ${uris.size}")
                    showSharedVideosInUI()
                    updateButtonState()
                } else {
                    Log.w("MainActivity", "ACTION_SEND_MULTIPLE received but no URIs found or list is empty")
                }
            }
        }
    }

    private fun showSharedVideosInUI() {
        // Make the RecyclerView visible
        binding.mainContents.visibility = View.VISIBLE

        // Add all URIs to the TOP of the data list (newest at top)
        uris.forEachIndexed { index, uri ->
            data.add(index, VideoDetailsModel("", uri, getString(R.string.video_waiting_to_start)))
            adapter.notifyItemInserted(index)
        }

        Log.i("MainActivity", "Showing ${uris.size} shared videos in UI at top of list")
    }

    private fun updateButtonState() {
        if (uris.isNotEmpty()) {
            // Videos are loaded, change button to "Start"
            binding.pickVideo.text = getString(R.string.start_compression)
            Log.i("MainActivity", "Button changed to Start (${uris.size} video(s) loaded)")
        } else {
            // No videos loaded, show "Pick Video"
            binding.pickVideo.text = getString(R.string.pick_video)
            Log.i("MainActivity", "Button changed to Pick Video")
        }
    }

    private fun calculateAndSetAutoBitrate() {
        // Get resolution from resizeInput
        val resolutionText = binding.resizeInput.text.toString()
        val resolution = if (resolutionText.isNotEmpty()) {
            try {
                resolutionText.toInt()
            } catch (e: NumberFormatException) {
                Log.w("MainActivity", "Invalid resolution input: $resolutionText, using default 1280", e)
                1280 // Default if invalid
            }
        } else {
            1280 // Default if empty
        }

        // Calculate recommended H.264 bitrate for cellular streaming (aggressive compression)
        val h264Bitrate = when {
            resolution >= 3840 -> 16000 // 4K
            resolution >= 2560 -> 10000 // 1440p
            resolution >= 1920 -> 5000  // 1080p
            resolution >= 1280 -> 2500  // 720p
            resolution >= 960 -> 1500   // 540p
            resolution >= 640 -> 800    // 360p
            else -> 500                 // Lower resolutions
        }

        // Check if H.265 is selected
        val isH265 = binding.codecRadioGroup.checkedRadioButtonId == R.id.radioH265

        // Apply 0.7 multiplier for H.265
        val finalBitrate = if (isH265) {
            (h264Bitrate * 0.7).toInt()
        } else {
            h264Bitrate
        }

        // Set the calculated bitrate
        binding.bitrateInput.setText(finalBitrate.toString())

        val codecName = if (isH265) getString(R.string.codec_h265) else getString(R.string.codec_h264)
        Log.i("MainActivity", "Auto bitrate: ${resolution}px with $codecName = $finalBitrate kbps")
        android.widget.Toast.makeText(
            this,
            getString(R.string.auto_bitrate_toast, finalBitrate, resolution, codecName),
            android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    //Pick a video file from device
    private fun pickVideo() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pickVideos.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
            )
            return
        }
        val intent = Intent()
        intent.apply {
            type = "video/*"
            action = Intent.ACTION_PICK
        }
        intent.putExtra(
            Intent.EXTRA_ALLOW_MULTIPLE,
            true
        )
        startActivityForResult(
            Intent.createChooser(intent, getString(R.string.select_video_prompt)),
            REQUEST_SELECT_VIDEO
        )
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

        // Don't call reset() here - we want to keep previous results visible
        // Just clear URIs if there are any old ones
        clearPreviousSelection()

        if (resultCode == RESULT_OK)
            if (requestCode == REQUEST_SELECT_VIDEO || requestCode == REQUEST_CAPTURE_VIDEO) {
                handleResult(intent, requestCode)
            }

        super.onActivityResult(requestCode, resultCode, intent)
    }

    private fun handleResult(data: Intent?, requestCode: Int) {
        val source = if (requestCode == REQUEST_CAPTURE_VIDEO) "camera" else "picker"
        val clipData: ClipData? = data?.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                val videoItem = clipData.getItemAt(i)
                uris.add(videoItem.uri)
            }
            videosFromSharing = false // Mark as picked videos
            lastSelectionSource = source
            AnalyticsTracker.logVideoSelection(lastSelectionSource, clipData.itemCount)
            // Auto-start compression for picked videos
            processVideo()
        } else if (data != null && data.data != null) {
            val uri = data.data
            uris.add(uri!!)
            videosFromSharing = false // Mark as picked videos
            lastSelectionSource = source
            AnalyticsTracker.logVideoSelection(lastSelectionSource, 1)
            // Auto-start compression for picked videos
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
        videosFromSharing = false
        lastSelectionSource = "unknown"
        completedVideosCount = 0
        updateButtonState()

        // Make sure buttons are enabled after reset
        binding.pickVideo.isEnabled = true
        binding.pickVideo.alpha = 1.0f
        binding.recordVideo.isEnabled = true
        binding.recordVideo.alpha = 1.0f
    }

    private fun clearPreviousSelection() {
        if (uris.isNotEmpty()) {
            uris.clear()
            originalVideoSizes.clear()
        }
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
        val (permission, requestCode) = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> return // Photo Picker handles access

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                Manifest.permission.READ_EXTERNAL_STORAGE to 2
            }

            else -> {
                Manifest.permission.WRITE_EXTERNAL_STORAGE to 3
            }
        }

        if (
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED &&
            !ActivityCompat.shouldShowRequestPermissionRationale(this, permission)
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
        }
    }

    private fun checkAndResetAfterCompletion() {
        completedVideosCount++
        Log.i("MainActivity", "Completed $completedVideosCount of ${uris.size} videos")

        // Check if all videos are done
        if (completedVideosCount >= uris.size) {
            runOnUiThread {
                // Re-enable the buttons
                binding.pickVideo.isEnabled = true
                binding.pickVideo.alpha = 1.0f
                binding.recordVideo.isEnabled = true
                binding.recordVideo.alpha = 1.0f
                Log.i("MainActivity", "Re-enabled Pick/Record Video buttons after compression complete")
            }

            // If videos were from file picker, clear URIs but keep results visible
            if (!videosFromSharing) {
                Log.i("MainActivity", "All picked videos completed, clearing URIs for next pick")
                runOnUiThread {
                    // Small delay to let user see the final result
                    android.os.Handler(mainLooper).postDelayed({
                        // Clear URIs so "Pick Video" button works for new videos
                        // But keep data list so results remain visible
                        uris.clear()
                        originalVideoSizes.clear()
                        completedVideosCount = 0
                        updateButtonState()
                        Log.i("MainActivity", "URIs cleared, ready to pick new videos. Results still visible.")
                    }, 1000)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun processVideo() {
        binding.mainContents.visibility = View.VISIBLE
        completedVideosCount = 0 // Reset counter when starting new compression
        if (lastSelectionSource == "unknown") {
            lastSelectionSource = if (videosFromSharing) "share_sheet" else "picker"
        }

        // Disable buttons during compression
        binding.pickVideo.isEnabled = false
        binding.pickVideo.alpha = 0.5f
        binding.recordVideo.isEnabled = false
        binding.recordVideo.alpha = 0.5f
        Log.i("MainActivity", "Disabled Pick/Record Video buttons during compression")

        // If we're reprocessing shared videos (data list already has items for these URIs)
        // clear those old results before starting fresh
        if (videosFromSharing && data.isNotEmpty()) {
            // Find items in data list that match current URIs and remove them
            val itemsToRemove = data.filter { item ->
                uris.any { uri -> uri == item.uri }
            }
            if (itemsToRemove.isNotEmpty()) {
                Log.i("MainActivity", "Clearing ${itemsToRemove.size} old results for re-compression")
                itemsToRemove.forEach { item ->
                    val index = data.indexOf(item)
                    if (index >= 0) {
                        data.removeAt(index)
                        adapter.notifyItemRemoved(index)
                    }
                }
            }
        }

        currentBatchStartIndex = 0 // New items will be inserted at the top
        Log.i("MainActivity", "Starting new batch at data index: $currentBatchStartIndex")

        // Get the user-entered bitrate value in kbps and convert to bps
        val bitrateText = binding.bitrateInput.text.toString()
        var videoBitrateInKbps = if (bitrateText.isNotEmpty()) {
            try {
                bitrateText.toLong()
            } catch (e: NumberFormatException) {
                Log.w("MainActivity", "Invalid bitrate input: $bitrateText, using default 1500 kbps", e)
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
                getString(R.string.bitrate_too_low, 200),
                android.widget.Toast.LENGTH_LONG
            ).show()
            videoBitrateInKbps = 200L
        } else if (videoBitrateInKbps > 100000) {
            Log.w("MainActivity", "Bitrate $videoBitrateInKbps kbps is above maximum (100000 kbps), clamping to 100000")
            android.widget.Toast.makeText(
                this,
                getString(R.string.bitrate_too_high, 100000),
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
                Log.w("MainActivity", "Invalid resize input: $resizeText, using default 1280", e)
                1280.0 // Default fallback value
            }
        } else {
            1280.0 // Default fallback value
        }

        val maxResolutionInt = maxResolution.toInt()

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
                        getString(R.string.codec_h265_not_supported),
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

        AnalyticsTracker.logCompressionStarted(
            bitrateKbps = videoBitrateInKbps,
            maxResolution = maxResolutionInt,
            codec = selectedCodec.name,
            streamable = isStreamable,
            videoCount = uris.size,
            source = lastSelectionSource,
        )

        Log.i("MainActivity", "Using bitrate: $videoBitrateInKbps kbps ($videoBitrateInBps bps)")
        Log.i("MainActivity", "Using max resolution: ${maxResolutionInt}px (long edge)")
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
                                val dataIndex = currentBatchStartIndex + index
                                data[dataIndex] = VideoDetailsModel(
                                    "",
                                    uris[index],
                                    "",
                                    percent
                                )
                                adapter.notifyItemChanged(dataIndex)
                            }
                    }

                    override fun onStart(index: Int) {
                        Log.i("MainActivity", "onStart called for index $index (URI: ${uris[index]})")
                        // Store original video size for comparison later
                        val originalSize = getOriginalVideoSize(uris[index])
                        originalVideoSizes[index] = originalSize
                        Log.i("MainActivity", "Original video size for index $index: ${getFileSize(originalSize)}")

                        runOnUiThread {
                            val dataIndex = currentBatchStartIndex + index
                            if (dataIndex < data.size && data[dataIndex].uri == uris[index]) {
                                // Item already exists (from showSharedVideosInUI), update it
                                data[dataIndex] = VideoDetailsModel("", uris[index], getString(R.string.video_starting))
                                adapter.notifyItemChanged(dataIndex)
                            } else {
                                // Item doesn't exist, add it (file picker flow)
                                data.add(dataIndex, VideoDetailsModel("", uris[index], ""))
                                adapter.notifyItemInserted(dataIndex)
                            }
                        }

                    }

                    override fun onSuccess(index: Int, size: Long, path: String?) {
                        Log.i("MainActivity", "onSuccess called for index $index, size: ${getFileSize(size)}, path: $path")
                        val originalSize = originalVideoSizes[index] ?: 0L
                        val dataIndex = currentBatchStartIndex + index

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
                            AnalyticsTracker.logCompressionResult(
                                status = "skipped_larger",
                                codec = selectedCodec.name,
                                source = lastSelectionSource,
                                videoCount = 1,
                            )

                            // Show toast to user
                            runOnUiThread {
                                android.widget.Toast.makeText(
                                    this@MainActivity,
                                    getString(R.string.video_not_saved_larger_toast, getFileSize(size), getFileSize(originalSize)),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()

                                // Update UI to show failure
                                data[dataIndex] = VideoDetailsModel(
                                    null,
                                    uris[index],
                                    getString(R.string.video_not_saved_status),
                                    0F
                                )
                                adapter.notifyItemChanged(dataIndex)
                            }
                        } else {
                            // Normal success case - compressed file is smaller or equal
                            Log.i("MainActivity", "Compression successful. Original: ${getFileSize(originalSize)}, Compressed: ${getFileSize(size)}")
                            data[dataIndex] = VideoDetailsModel(
                                path,
                                uris[index],
                                getFileSize(size),
                                100F
                            )
                            AnalyticsTracker.logCompressionResult(
                                status = "success",
                                codec = selectedCodec.name,
                                source = lastSelectionSource,
                                videoCount = 1,
                            )
                            runOnUiThread {
                                adapter.notifyItemChanged(dataIndex)
                            }
                        }

                        // Check if all videos are done and reset if needed
                        checkAndResetAfterCompletion()
                    }

                    override fun onFailure(index: Int, failureMessage: String) {
                        Log.e("MainActivity", "onFailure called for index $index: $failureMessage")
                        AnalyticsTracker.recordCompressionFailure(
                            message = failureMessage,
                            codec = selectedCodec.name,
                            bitrateKbps = videoBitrateInKbps,
                            streamable = isStreamable,
                            source = lastSelectionSource,
                        )
                        AnalyticsTracker.logCompressionResult(
                            status = "failure",
                            codec = selectedCodec.name,
                            source = lastSelectionSource,
                            videoCount = 1,
                        )
                        // Check if all videos are done and reset if needed
                        checkAndResetAfterCompletion()
                    }

                    override fun onCancelled(index: Int) {
                        Log.w("MainActivity", "onCancelled called for index $index")
                        AnalyticsTracker.logCompressionResult(
                            status = "cancelled",
                            codec = selectedCodec.name,
                            source = lastSelectionSource,
                            videoCount = 1,
                        )
                        // Check if all videos are done and reset if needed
                        checkAndResetAfterCompletion()
                        // make UI changes, cleanup, etc
                    }
                },
            )
        }
    }
}
