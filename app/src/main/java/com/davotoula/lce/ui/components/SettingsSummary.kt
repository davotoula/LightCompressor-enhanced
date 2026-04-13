package com.davotoula.lce.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.davotoula.lce.R
import com.davotoula.lce.ui.Codec
import com.davotoula.lightcompressor.Resolution

@Composable
fun SettingsSummary(
    resolution: Resolution,
    customResolution: Int?,
    codec: Codec,
    bitrateKbps: Int,
    isStreamable: Boolean,
    modifier: Modifier = Modifier,
) {
    val resolutionText = customResolution?.let { "${it}p" } ?: resolution.label
    val codecText = codec.displayName
    val bitrateText = stringResource(R.string.summary_bitrate_kbps, bitrateKbps)
    val streamableText =
        if (isStreamable) {
            stringResource(R.string.summary_streamable)
        } else {
            stringResource(R.string.summary_not_streamable)
        }

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = listOf(resolutionText, codecText, bitrateText, streamableText).joinToString("  •  "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
