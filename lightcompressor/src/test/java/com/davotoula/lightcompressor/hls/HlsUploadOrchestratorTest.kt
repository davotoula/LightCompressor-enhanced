package com.davotoula.lightcompressor.hls

import android.content.Context
import com.davotoula.lightcompressor.Resolution
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class HlsUploadOrchestratorTest {
    private lateinit var tempDir: File
    private lateinit var context: Context

    @Before
    fun setup() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "hlsUploadOrchestratorTest-${System.nanoTime()}")
        tempDir.mkdirs()
        context = mockk()
        every { context.cacheDir } returns tempDir
    }

    @After
    fun teardown() {
        tempDir.deleteRecursively()
    }

    private val rendition720 = Rendition(Resolution.HD_720, bitrateKbps = 2500)
    private val rendition1080 = Rendition(Resolution.FHD_1080, bitrateKbps = 5000)

    private fun summary(
        rendition: Rendition,
        mediaPlaylist: String,
        combinedFilename: String? = null,
    ) = HlsRenditionSummary(
        rendition = rendition,
        mediaPlaylist = mediaPlaylist,
        playlistFilename = rendition.mediaPlaylistFilename(),
        width = 1280,
        height = 720,
        codecString = "avc1.64001F",
        combinedFilename = combinedFilename,
    )

    private fun segmentFile(
        name: String,
        content: String = "x",
    ): File {
        val f = File(tempDir, name).apply { writeText(content) }
        return f
    }

    @Test
    fun `happy path multi-file emits rewritten master playlist`() {
        val uploads = mutableListOf<Pair<String, String>>() // suggestedFilename -> content
        val uploader: suspend (File, String) -> String = { file, name ->
            uploads += name to file.readText()
            "https://cdn/$name"
        }
        val orchestrator = HlsUploadOrchestrator(context, uploader)

        orchestrator.onStart(renditionCount = 1)
        orchestrator.onRenditionStart(rendition720)
        orchestrator.onSegmentReady(
            rendition720,
            HlsSegment(
                file = segmentFile("init.mp4", "init-bytes"),
                index = 0,
                durationSeconds = 0.0,
                isInitSegment = true,
            ),
        )
        orchestrator.onSegmentReady(
            rendition720,
            HlsSegment(
                file = segmentFile("s000.m4s", "s0-bytes"),
                index = 0,
                durationSeconds = 6.0,
                isInitSegment = false,
            ),
        )
        val playlist720 =
            """
            #EXTM3U
            #EXT-X-MAP:URI="init.mp4"
            #EXTINF:6.000,
            segment_000.m4s
            #EXT-X-ENDLIST
            """.trimIndent()
        orchestrator.onRenditionComplete(rendition720, summary(rendition720, playlist720))
        orchestrator.onComplete(
            masterPlaylist =
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.64001F"
                720p/media.m3u8
                """.trimIndent(),
        )

        val result = kotlinx.coroutines.runBlocking { orchestrator.completeUpload() }

        // Uploader called for init, media segment, and media playlist (3 total).
        assertEquals(3, uploads.size)
        assertEquals("720p/init.mp4", uploads[0].first)
        assertEquals("720p/segment_000.m4s", uploads[1].first)
        assertEquals("720p/media.m3u8", uploads[2].first)

        // The rewritten media playlist was uploaded (not the raw one).
        assertTrue(uploads[2].second.contains("https://cdn/720p/init.mp4"))
        assertTrue(uploads[2].second.contains("https://cdn/720p/segment_000.m4s"))

        // The returned master playlist contains the uploaded rendition URL.
        assertTrue(result.masterPlaylist.contains("https://cdn/720p/media.m3u8"))
        assertEquals(1, result.renditions.size)
        assertEquals(rendition720, result.renditions[0].rendition)
    }

    @Test
    fun `happy path single-file emits byterange playlist rewrites`() {
        val uploads = mutableListOf<Pair<String, String>>()
        val uploader: suspend (File, String) -> String = { file, name ->
            uploads += name to file.readText()
            "https://cdn/$name"
        }
        val orchestrator = HlsUploadOrchestrator(context, uploader)

        orchestrator.onStart(1)
        orchestrator.onRenditionStart(rendition720)
        orchestrator.onSegmentReady(
            rendition720,
            HlsSegment(
                file = segmentFile("720p.mp4", "combined"),
                index = 0,
                durationSeconds = 12.0,
                isInitSegment = false,
                isCombinedRendition = true,
            ),
        )
        val playlist720 =
            """
            #EXTM3U
            #EXT-X-MAP:URI="720p.mp4",BYTERANGE="1024@0"
            #EXTINF:6.000,
            #EXT-X-BYTERANGE:4096@1024
            720p.mp4
            #EXTINF:6.000,
            #EXT-X-BYTERANGE:4096@5120
            720p.mp4
            #EXT-X-ENDLIST
            """.trimIndent()
        orchestrator.onRenditionComplete(
            rendition720,
            summary(rendition720, playlist720, combinedFilename = "720p.mp4"),
        )
        orchestrator.onComplete(
            masterPlaylist =
                """
                #EXTM3U
                #EXT-X-STREAM-INF:BANDWIDTH=2500000,RESOLUTION=1280x720,CODECS="avc1.64001F"
                720p/media.m3u8
                """.trimIndent(),
        )

        val result = kotlinx.coroutines.runBlocking { orchestrator.completeUpload() }

        assertEquals(2, uploads.size)
        assertEquals("720p.mp4", uploads[0].first)
        assertEquals("720p/media.m3u8", uploads[1].first)

        // Rewritten media playlist references the uploaded combined file URL everywhere it used to say 720p.mp4.
        assertTrue(uploads[1].second.contains("https://cdn/720p.mp4"))
        // Byterange and MAP directives are preserved intact by PlaylistRewriter.
        assertTrue(uploads[1].second.contains("#EXT-X-BYTERANGE:4096@1024"))
        assertTrue(uploads[1].second.contains("#EXT-X-MAP:URI=\"https://cdn/720p.mp4\",BYTERANGE=\"1024@0\""))

        assertTrue(result.masterPlaylist.contains("https://cdn/720p/media.m3u8"))
    }

    @Test
    fun `uploader failure stops orchestration and is rethrown by completeUpload`() {
        var callCount = 0
        val uploader: suspend (File, String) -> String = { _, _ ->
            callCount += 1
            if (callCount == 2) throw RuntimeException("boom")
            "https://cdn/x"
        }
        val orchestrator = HlsUploadOrchestrator(context, uploader)

        orchestrator.onStart(1)
        orchestrator.onRenditionStart(rendition720)
        orchestrator.onSegmentReady(
            rendition720,
            HlsSegment(file = segmentFile("init.mp4"), index = 0, durationSeconds = 0.0, isInitSegment = true),
        )
        orchestrator.onSegmentReady(
            rendition720,
            HlsSegment(file = segmentFile("s000.m4s"), index = 0, durationSeconds = 6.0, isInitSegment = false),
        )
        // Further calls on the orchestrator after a failure should be no-ops.
        orchestrator.onSegmentReady(
            rendition720,
            HlsSegment(file = segmentFile("s001.m4s"), index = 1, durationSeconds = 6.0, isInitSegment = false),
        )
        orchestrator.onRenditionComplete(rendition720, summary(rendition720, "#EXTM3U\n#EXT-X-ENDLIST\n"))
        orchestrator.onComplete("")

        assertEquals(2, callCount) // first succeeded, second threw, rest were suppressed
        try {
            kotlinx.coroutines.runBlocking { orchestrator.completeUpload() }
            fail("expected RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun `onFailure from transcoder surfaces as completeUpload exception`() {
        val orchestrator = HlsUploadOrchestrator(context) { _, _ -> "unused" }
        orchestrator.onStart(1)
        orchestrator.onRenditionStart(rendition720)
        orchestrator.onFailure(
            HlsError(
                message = "all renditions failed",
                failedRenditions = listOf(rendition720),
                completedRenditions = emptyList(),
            ),
        )

        try {
            kotlinx.coroutines.runBlocking { orchestrator.completeUpload() }
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("all renditions failed", e.message)
        }
    }

    @Test
    fun `onCancelled surfaces as CancellationException from completeUpload`() {
        val orchestrator = HlsUploadOrchestrator(context) { _, _ -> "unused" }
        orchestrator.onStart(1)
        orchestrator.onCancelled()

        try {
            kotlinx.coroutines.runBlocking { orchestrator.completeUpload() }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            // expected
        }
    }

    @Test
    fun `media playlist upload failure during completeUpload propagates`() {
        val uploader: suspend (File, String) -> String = { _, name ->
            if (name.endsWith(".m3u8")) throw RuntimeException("playlist upload failed")
            "https://cdn/$name"
        }
        val orchestrator = HlsUploadOrchestrator(context, uploader)

        orchestrator.onStart(1)
        orchestrator.onRenditionStart(rendition720)
        orchestrator.onSegmentReady(
            rendition720,
            HlsSegment(file = segmentFile("init.mp4"), index = 0, durationSeconds = 0.0, isInitSegment = true),
        )
        orchestrator.onRenditionComplete(rendition720, summary(rendition720, "#EXTM3U\n#EXT-X-ENDLIST\n"))
        orchestrator.onComplete(
            masterPlaylist = "#EXTM3U\n720p/media.m3u8\n",
        )

        try {
            kotlinx.coroutines.runBlocking { orchestrator.completeUpload() }
            fail("expected RuntimeException")
        } catch (e: RuntimeException) {
            assertEquals("playlist upload failed", e.message)
        }
    }
}
