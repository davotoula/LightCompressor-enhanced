package com.davotoula.lightcompressor.hls

/**
 * Rewrites HLS playlist segment filenames to uploaded URLs.
 *
 * After uploading segments to a content-addressed server (e.g. Blossom),
 * use this to replace predictable filenames with actual URLs before
 * uploading the playlist.
 */
object PlaylistRewriter {
    private val MAP_URI_PATTERN = Regex("""(#EXT-X-MAP:URI=")([^"]+)(")""")

    /**
     * Replaces segment filenames in [playlist] using [urlMap].
     *
     * @param playlist the m3u8 content (media or master)
     * @param urlMap maps original filenames (e.g. "segment_000.m4s") to URLs
     * @return the rewritten playlist string
     */
    @Suppress("MagicNumber")
    fun rewrite(
        playlist: String,
        urlMap: Map<String, String>,
    ): String =
        playlist.lines().joinToString("\n") { line ->
            when {
                line.startsWith("#EXT-X-MAP:") -> {
                    MAP_URI_PATTERN.replace(line) { match ->
                        val filename = match.groupValues[2]
                        val url = urlMap[filename] ?: filename
                        "${match.groupValues[1]}$url${match.groupValues[3]}"
                    }
                }
                line.startsWith("#") || line.isBlank() -> line
                else -> urlMap[line.trim()] ?: line
            }
        }
}
