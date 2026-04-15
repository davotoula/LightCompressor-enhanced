package com.davotoula.lightcompressor.hls

/**
 * Canonical MIME type constants for HLS content. Android's `MimeTypeMap` does not know the
 * HLS playlist type, so use this as the single source of truth.
 */
object HlsContentTypes {
    /** MIME type for HLS (m3u8) playlists — `application/vnd.apple.mpegurl`. */
    const val HLS_PLAYLIST = "application/vnd.apple.mpegurl"

    /** MIME type for fMP4 segments (init segments, media segments, combined renditions). */
    const val FMP4_SEGMENT = "video/mp4"
}
