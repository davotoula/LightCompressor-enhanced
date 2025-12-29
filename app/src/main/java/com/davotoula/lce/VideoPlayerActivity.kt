package com.davotoula.lce

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Video Player Activity - placeholder for Compose migration
 * TODO: Implement Media3 PlayerView with Compose in subsequent tasks
 */
class VideoPlayerActivity : ComponentActivity() {

    private var uri = ""

    companion object {
        fun start(context: Context, uri: String?) {
            val intent = Intent(context, VideoPlayerActivity::class.java)
                .putExtra("uri", uri)
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent?.extras?.let {
            uri = it.getString("uri", "")
        }

        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Video Player - Coming Soon",
                        color = Color.White
                    )
                }
            }
        }
    }
}
