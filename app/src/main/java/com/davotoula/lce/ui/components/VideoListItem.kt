package com.davotoula.lce.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.davotoula.lce.VideoDetailsModel

/**
 * A composable that displays a video item in a list format.
 *
 * Features:
 * - Video thumbnail on the left (80dp square with rounded corners)
 * - Compression progress bar when compressing (progress between 0 and 100)
 * - Progress percentage text during compression
 * - Compressed size display when complete
 * - "Tap to play" hint when video is playable
 * - Card container with elevation for visual hierarchy
 *
 * @param video The video details model containing URI, progress, and playable path info
 * @param onClick Callback invoked when the card is clicked (only enabled when playable)
 * @param modifier Optional modifier for customizing the component
 */
@Composable
fun VideoListItem(
    video: VideoDetailsModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPlayable = video.playableVideoPath != null
    val isCompressing = video.progress > 0f && video.progress < 1f
    val isComplete = video.progress >= 1f

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        enabled = isPlayable,
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            AsyncImage(
                model = video.uri,
                contentDescription = "Video thumbnail",
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )

            Spacer(modifier = Modifier.width(16.dp))

            // Content column
            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Progress indicator and percentage when compressing
                if (isCompressing) {
                    Text(
                        text = "${(video.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { video.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                // Completed state
                if (isComplete) {
                    Text(
                        text = "Compressed",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = video.newSize,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Pending state (not started)
                if (!isCompressing && !isComplete) {
                    Text(
                        text = video.newSize,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Tap to play hint when playable
                if (isPlayable) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Tap to play",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        }
    }
}
