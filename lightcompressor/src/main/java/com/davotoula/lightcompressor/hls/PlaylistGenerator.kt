package com.davotoula.lightcompressor.hls

/**
 * Metadata for one segment, used to build the media playlist.
 */
internal data class SegmentInfo(
    val filename: String,
    val durationSeconds: Double,
)

/**
 * Metadata for one segment inside a single-file rendition. Each entry maps to a contiguous
 * byte range inside the rendition's combined fMP4 file.
 */
internal data class ByteRangeSegment(
    val durationSeconds: Double,
    val offset: Long,
    val length: Long,
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
internal object PlaylistGenerator {
    private const val EXTM3U = "#EXTM3U"
    private const val EXT_X_VERSION_7 = "#EXT-X-VERSION:7"

    /**
     * Builds a media playlist for a single rendition.
     */
    fun buildMediaPlaylist(
        segments: List<SegmentInfo>,
        targetDurationSeconds: Int,
    ): String =
        buildString {
            appendLine(EXTM3U)
            appendLine(EXT_X_VERSION_7)
            appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine("#EXT-X-MAP:URI=\"init.mp4\"")
            appendLine()
            for (segment in segments) {
                appendLine("#EXTINF:${formatDuration(segment.durationSeconds)},")
                appendLine(segment.filename)
            }
            appendLine("#EXT-X-ENDLIST")
        }

    /**
     * Builds a media playlist for a single-file rendition. The init segment and every media
     * segment live inside [combinedFilename]; each segment is referenced by `#EXT-X-BYTERANGE`.
     *
     * @param combinedFilename name of the rendition file (relative to the playlist)
     * @param initRangeLength length in bytes of the leading init segment (offset is always 0)
     * @param segments byte-range entries in playback order
     * @param targetDurationSeconds value for `#EXT-X-TARGETDURATION`
     */
    fun buildByteRangeMediaPlaylist(
        combinedFilename: String,
        initRangeLength: Long,
        segments: List<ByteRangeSegment>,
        targetDurationSeconds: Int,
    ): String =
        buildString {
            appendLine(EXTM3U)
            appendLine(EXT_X_VERSION_7)
            appendLine("#EXT-X-TARGETDURATION:$targetDurationSeconds")
            appendLine("#EXT-X-MEDIA-SEQUENCE:0")
            appendLine("#EXT-X-PLAYLIST-TYPE:VOD")
            appendLine(
                "#EXT-X-MAP:URI=\"$combinedFilename\",BYTERANGE=\"$initRangeLength@0\"",
            )
            appendLine()
            for (segment in segments) {
                appendLine("#EXTINF:${formatDuration(segment.durationSeconds)},")
                appendLine("#EXT-X-BYTERANGE:${segment.length}@${segment.offset}")
                appendLine(combinedFilename)
            }
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
            appendLine(EXTM3U)
            appendLine(EXT_X_VERSION_7)
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
