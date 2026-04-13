package com.davotoula.lightcompressor.hls

/**
 * Metadata for one segment, used to build the media playlist.
 */
internal data class SegmentInfo(
    val filename: String,
    val durationSeconds: Double,
)

/**
 * Result for one completed rendition, used to build the master playlist.
 */
internal data class RenditionResult(
    val rendition: Rendition,
    val actualWidth: Int,
    val actualHeight: Int,
    val codecString: String,
    val playlistFilename: String,
    val mediaPlaylist: String,
)

/**
 * Generates HLS m3u8 playlist strings (VOD).
 */
internal class PlaylistGenerator {
    /**
     * Builds a media playlist for a single rendition.
     */
    fun buildMediaPlaylist(
        segments: List<SegmentInfo>,
        targetDurationSeconds: Int,
    ): String =
        buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-MAP:URI=\"init.mp4\"")
            appendLine()
            for (segment in segments) {
                appendLine("#EXTINF:${formatDuration(segment.durationSeconds)},")
                appendLine(segment.filename)
            }
            appendLine()
            appendLine("#EXT-X-ENDLIST")
        }

    /**
     * Builds the master playlist referencing all completed renditions.
     * Renditions are sorted by bandwidth ascending.
     */
    @Suppress("MagicNumber")
    fun buildMasterPlaylist(renditions: List<RenditionResult>): String =
        buildString {
            val sorted = renditions.sortedBy { it.rendition.bitrateKbps }
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:7")
            appendLine()
            for (r in sorted) {
                val bandwidthBps = r.rendition.bitrateKbps * 1000
                append("#EXT-X-STREAM-INF:")
                append("BANDWIDTH=$bandwidthBps,")
                append("RESOLUTION=${r.actualWidth}x${r.actualHeight},")
                appendLine("CODECS=\"${r.codecString}\"")
                appendLine(r.playlistFilename)
                appendLine()
            }
        }

    private fun formatDuration(seconds: Double): String = "%.3f".format(seconds)
}
