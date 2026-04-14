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

    /** The content-type header value to use when uploading an HLS playlist. */
    fun forPlaylist(): String = HLS_PLAYLIST

    /**
     * The content-type header value to use when uploading [segment]. Today every HLS segment
     * emitted by this library is fMP4, but routing through this helper keeps callers correct
     * if segment formats ever expand.
     */
    @Suppress("UNUSED_PARAMETER")
    fun forSegment(segment: HlsSegment): String = FMP4_SEGMENT
}
