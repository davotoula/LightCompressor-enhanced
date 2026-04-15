package com.davotoula.lightcompressor.hls

internal const val MEDIA_PLAYLIST_FILENAME = "media.m3u8"
internal const val MASTER_PLAYLIST_FILENAME = "master.m3u8"

/**
 * Relative filename of the media playlist that the library writes for this rendition.
 *
 * Returns a path like `"720p/media.m3u8"`. Usable before transcoding starts — consumers
 * can pre-allocate URLs or build rewrite maps up-front.
 */
fun Rendition.mediaPlaylistFilename(): String = "${resolution.label}/$MEDIA_PLAYLIST_FILENAME"

/**
 * Suggested relative filename for [segment] within this rendition. Consumers use the
 * returned string as the URL-rewrite map key (passed to [PlaylistRewriter.rewrite]) and
 * as a local disk filename.
 *
 * Layout:
 * - Init segment → `"<label>/init.mp4"`
 * - Media segment → `"<label>/segment_NNN.m4s"` (zero-padded 3-digit index)
 * - Combined rendition (single-file mode) → `"<label>.mp4"` (no subdirectory — matches the
 *   `#EXT-X-MAP:URI="<label>.mp4"` reference the byterange playlist emits)
 */
fun Rendition.suggestedFilename(segment: HlsSegment): String =
    when {
        segment.isCombinedRendition -> "${resolution.label}.mp4"
        segment.isInitSegment -> "${resolution.label}/init.mp4"
        else -> "${resolution.label}/segment_%03d.m4s".format(segment.index)
    }

/**
 * The name [segment] appears as inside this rendition's media playlist. The media
 * playlist lives in `<label>/`, so init/media segments are referenced bare; combined
 * renditions (single-file mode) resolve to `<label>.mp4` in the same directory, matching
 * the `#EXT-X-MAP` / byterange URI the library emits.
 */
internal fun Rendition.playlistRelativeFilename(segment: HlsSegment): String =
    when {
        segment.isCombinedRendition -> "${resolution.label}.mp4"
        segment.isInitSegment -> "init.mp4"
        else -> "segment_%03d.m4s".format(segment.index)
    }
