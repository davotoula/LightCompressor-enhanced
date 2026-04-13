package com.davotoula.lightcompressor.hls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRewriterTest {
    @Test
    fun `rewrites segment filenames to URLs`() {
        val playlist =
            """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.000,
            segment_000.m4s
            #EXTINF:6.000,
            segment_001.m4s
            #EXT-X-ENDLIST
            """.trimIndent()
        val urlMap =
            mapOf(
                "init.mp4" to "https://blossom.example/abc123",
                "segment_000.m4s" to "https://blossom.example/def456",
                "segment_001.m4s" to "https://blossom.example/ghi789",
            )
        val result = PlaylistRewriter.rewrite(playlist, urlMap)
        assertTrue("https://blossom.example/abc123" in result)
        assertTrue("https://blossom.example/def456" in result)
        assertTrue("https://blossom.example/ghi789" in result)
        assertTrue("init.mp4" !in result)
        assertTrue("segment_000.m4s" !in result)
    }

    @Test
    fun `preserves HLS tags unchanged`() {
        val playlist =
            """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXTINF:6.000,
            segment_000.m4s
            #EXT-X-ENDLIST
            """.trimIndent()
        val urlMap = mapOf("segment_000.m4s" to "https://example.com/a")
        val result = PlaylistRewriter.rewrite(playlist, urlMap)
        assertTrue("#EXTM3U" in result)
        assertTrue("#EXT-X-VERSION:7" in result)
        assertTrue("#EXTINF:6.000," in result)
    }

    @Test
    fun `leaves unmapped filenames unchanged`() {
        val playlist = "#EXTINF:6.000,\nsegment_000.m4s\n"
        val result = PlaylistRewriter.rewrite(playlist, emptyMap())
        assertTrue("segment_000.m4s" in result)
    }

    @Test
    fun `rewrites EXT-X-MAP URI attribute`() {
        val playlist = "#EXT-X-MAP:URI=\"init.mp4\"\n"
        val urlMap = mapOf("init.mp4" to "https://blossom.example/init")
        val result = PlaylistRewriter.rewrite(playlist, urlMap)
        assertEquals("#EXT-X-MAP:URI=\"https://blossom.example/init\"\n", result)
    }
}
