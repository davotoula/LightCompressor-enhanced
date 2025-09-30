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
        data.clear()
        adapter.notifyDataSetChanged()
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

        // Get the user-entered bitrate value
        val bitrateText = binding.bitrateInput.text.toString()
        val videoBitrateInBps = if (bitrateText.isNotEmpty()) {
            try {
                bitrateText.toLong()
            } catch (e: NumberFormatException) {
                Log.w("MainActivity", "Invalid bitrate input: $bitrateText, using default 1500000")
                1500000L // Default fallback value
            }
        } else {
            1500000L // Default fallback value
        }

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

        // Get the selected codec
        val selectedCodec = when (binding.codecRadioGroup.checkedRadioButtonId) {
            R.id.radioH265 -> VideoCodec.H265
            else -> VideoCodec.H264
        }

        // Check if H.265 is supported when selected
        if (selectedCodec == VideoCodec.H265 && !CompressorUtils.isHevcEncodingSupported()) {
            Log.w("MainActivity", "H.265 selected but not supported on this device, falling back to H.264")
            binding.radioH264.isChecked = true
            android.widget.Toast.makeText(
                this,
                "H.265 not supported on this device. Using H.264 instead.",
                android.widget.Toast.LENGTH_LONG
            ).show()
        }

        val finalCodec = if (selectedCodec == VideoCodec.H265 && CompressorUtils.isHevcEncodingSupported()) {
            VideoCodec.H265
        } else {
            VideoCodec.H264
        }

        Log.i("MainActivity", "Using bitrate: $videoBitrateInBps bps (${videoBitrateInBps / 1000000.0} Mbps)")
        Log.i("MainActivity", "Using max resolution: ${maxResolution.toInt()}px (long edge)")
        Log.i("MainActivity", "Using codec: ${finalCodec.name}")

        lifecycleScope.launch {
            VideoCompressor.start(
                context = applicationContext,
                uris,
                isStreamable = false,
                storageConfiguration = SharedStorageConfiguration(
                    saveAt = SaveLocation.movies,
                    subFolderName = "my-demo-videos"
                ),
                configureWith = Configuration.withBitrateInBps(
                    // Quality is ignored when videoBitrateInBps is provided, but required by API
                    videoBitrateInBps = videoBitrateInBps,
                    videoNames = uris.map { uri -> uri.pathSegments.last() },
                    isMinBitrateCheckEnabled = false,
                    resizer = VideoResizer.limitSize(maxResolution),
                    videoCodec = finalCodec
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
                                adapter.notifyDataSetChanged()
                            }
                    }

                    override fun onStart(index: Int) {
                        data.add(
                            index,
                            VideoDetailsModel("", uris[index], "")
                        )
                        runOnUiThread {
                            adapter.notifyDataSetChanged()
                        }

                    }

                    override fun onSuccess(index: Int, size: Long, path: String?) {
                        data[index] = VideoDetailsModel(
                            path,
                            uris[index],
                            getFileSize(size),
                            100F
                        )
                        runOnUiThread {
                            adapter.notifyDataSetChanged()
                        }
                    }

                    override fun onFailure(index: Int, failureMessage: String) {
                        Log.wtf("failureMessage", failureMessage)
                    }

                    override fun onCancelled(index: Int) {
                        Log.wtf("TAG", "compression has been cancelled")
                        // make UI changes, cleanup, etc
                    }
                },
            )
        }
    }
}
