package com.davotoula.lightcompressor.hls

/**
 * Relative filename of the media playlist that the library writes for this rendition.
 *
 * Returns a path like `"720p/media.m3u8"`. Usable before transcoding starts — consumers
 * can pre-allocate URLs or build rewrite maps up-front.
 */
fun Rendition.mediaPlaylistFilename(): String = "${resolution.label}/media.m3u8"

/**
 * Suggested relative filename for this segment. Consumers use the returned string as the
 * URL-rewrite map key (passed to [PlaylistRewriter.rewrite]) and as a local disk filename.
 *
 * Layout:
 * - Init segment → `"<label>/init.mp4"`
 * - Media segment → `"<label>/segment_NNN.m4s"` (zero-padded 3-digit index)
 * - Combined rendition (single-file mode) → `"<label>.mp4"` (no subdirectory — matches the
 *   `#EXT-X-MAP:URI="<label>.mp4"` reference the byterange playlist emits)
 */
fun HlsSegment.suggestedFilename(rendition: Rendition): String =
    when {
        isCombinedRendition -> "${rendition.resolution.label}.mp4"
        isInitSegment -> "${rendition.resolution.label}/init.mp4"
        else -> "${rendition.resolution.label}/segment_%03d.m4s".format(index)
    }
