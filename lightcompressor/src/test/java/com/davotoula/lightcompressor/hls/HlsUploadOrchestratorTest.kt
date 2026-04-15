package com.davotoula.lightcompressor.hls

import android.content.Context
import com.davotoula.lightcompressor.Resolution
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File

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
        val uploadOrder = mutableListOf<Pair<String, String>>() // suggestedFilename -> content
        val uploader: suspend (File, String) -> HlsUploaded<Unit> = { file, name ->
            uploadOrder += name to file.readText()
            HlsUploaded("https://cdn/$name", Unit)
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
        assertEquals(3, uploadOrder.size)
        assertEquals("720p/init.mp4", uploadOrder[0].first)
        assertEquals("720p/segment_000.m4s", uploadOrder[1].first)
        assertEquals("720p/media.m3u8", uploadOrder[2].first)

        // The rewritten media playlist was uploaded (not the raw one).
        assertTrue(uploadOrder[2].second.contains("https://cdn/720p/init.mp4"))
        assertTrue(uploadOrder[2].second.contains("https://cdn/720p/segment_000.m4s"))

        // The returned master playlist contains the uploaded rendition URL.
        assertTrue(result.masterPlaylist.contains("https://cdn/720p/media.m3u8"))
        assertEquals(1, result.renditions.size)
        assertEquals(rendition720, result.renditions[0].rendition)

        // result.uploads exposes every call to the uploader in timeline order.
        assertEquals(
            listOf("720p/init.mp4", "720p/segment_000.m4s", "720p/media.m3u8"),
            result.uploads.keys.toList(),
        )
        assertEquals("https://cdn/720p/init.mp4", result.uploads["720p/init.mp4"]?.url)
        assertEquals("https://cdn/720p/segment_000.m4s", result.uploads["720p/segment_000.m4s"]?.url)
        assertEquals("https://cdn/720p/media.m3u8", result.uploads["720p/media.m3u8"]?.url)
    }

    @Test
    fun `happy path single-file emits byterange playlist rewrites`() {
        val uploadOrder = mutableListOf<Pair<String, String>>()
        val uploader: suspend (File, String) -> HlsUploaded<Unit> = { file, name ->
            uploadOrder += name to file.readText()
            HlsUploaded("https://cdn/$name", Unit)
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

        assertEquals(2, uploadOrder.size)
        assertEquals("720p.mp4", uploadOrder[0].first)
        assertEquals("720p/media.m3u8", uploadOrder[1].first)

        // Rewritten media playlist references the uploaded combined file URL everywhere it used to say 720p.mp4.
        assertTrue(uploadOrder[1].second.contains("https://cdn/720p.mp4"))
        // Byterange and MAP directives are preserved intact by PlaylistRewriter.
        assertTrue(uploadOrder[1].second.contains("#EXT-X-BYTERANGE:4096@1024"))
        assertTrue(uploadOrder[1].second.contains("#EXT-X-MAP:URI=\"https://cdn/720p.mp4\",BYTERANGE=\"1024@0\""))

        assertTrue(result.masterPlaylist.contains("https://cdn/720p/media.m3u8"))

        // Combined rendition key matches summary.combinedFilename exactly — the contract Amethyst depends on.
        assertEquals(
            listOf("720p.mp4", "720p/media.m3u8"),
            result.uploads.keys.toList(),
        )
        assertNotNull(result.uploads["720p.mp4"])
        assertEquals("https://cdn/720p.mp4", result.uploads["720p.mp4"]?.url)
    }

    @Test
    fun `uploads map carries caller metadata and iteration order across two renditions`() {
        data class Blob(
            val sha256: String,
            val size: Long,
        )

        val uploader: suspend (File, String) -> HlsUploaded<Blob> = { file, name ->
            HlsUploaded(
                url = "https://cdn/$name",
                metadata = Blob(sha256 = "sha-$name", size = file.length()),
            )
        }
        val orchestrator = HlsUploadOrchestrator(context, uploader)

        // 720p rendition (single-file mode) — combined + media playlist
        orchestrator.onStart(2)
        orchestrator.onRenditionStart(rendition720)
        orchestrator.onSegmentReady(
            rendition720,
            HlsSegment(
                file = segmentFile("720p.mp4", "a".repeat(100)),
                index = 0,
                durationSeconds = 12.0,
                isInitSegment = false,
                isCombinedRendition = true,
            ),
        )
        orchestrator.onRenditionComplete(
            rendition720,
            summary(rendition720, "#EXTM3U\n720p.mp4\n#EXT-X-ENDLIST\n", combinedFilename = "720p.mp4"),
        )

        // 1080p rendition (single-file mode)
        orchestrator.onRenditionStart(rendition1080)
        orchestrator.onSegmentReady(
            rendition1080,
            HlsSegment(
                file = segmentFile("1080p.mp4", "b".repeat(200)),
                index = 0,
                durationSeconds = 12.0,
                isInitSegment = false,
                isCombinedRendition = true,
            ),
        )
        orchestrator.onRenditionComplete(
            rendition1080,
            summary(rendition1080, "#EXTM3U\n1080p.mp4\n#EXT-X-ENDLIST\n", combinedFilename = "1080p.mp4"),
        )

        orchestrator.onComplete("#EXTM3U\n720p/media.m3u8\n1080p/media.m3u8\n")

        val result: HlsUploadResult<Blob> = kotlinx.coroutines.runBlocking { orchestrator.completeUpload() }

        // Iteration order tracks the actual upload timeline. Segments are uploaded during the sync phase
        // (as onSegmentReady fires, in emission order — 720p combined then 1080p combined). Media playlists
        // are uploaded during the suspend phase in completeUpload(), in rendition order, AFTER every segment
        // has been emitted. So the final order is: all segments → all media playlists.
        assertEquals(
            listOf("720p.mp4", "1080p.mp4", "720p/media.m3u8", "1080p/media.m3u8"),
            result.uploads.keys.toList(),
        )

        // Combined-rendition metadata is reachable by summary.combinedFilename — the key Amethyst uses for imeta tags.
        val combined720 = result.uploads["720p.mp4"]
        assertNotNull(combined720)
        assertEquals("sha-720p.mp4", combined720?.metadata?.sha256)
        assertEquals(100L, combined720?.metadata?.size)

        val combined1080 = result.uploads["1080p.mp4"]
        assertNotNull(combined1080)
        assertEquals("sha-1080p.mp4", combined1080?.metadata?.sha256)
        assertEquals(200L, combined1080?.metadata?.size)

        // Media playlist uploads also surface metadata — useful for integrity verification or analytics.
        assertNotNull(result.uploads["720p/media.m3u8"])
        assertNotNull(result.uploads["1080p/media.m3u8"])

        assertEquals(2, result.renditions.size)
    }

    @Test
    fun `uploader failure stops orchestration and is rethrown by completeUpload`() {
        var callCount = 0
        val uploader: suspend (File, String) -> HlsUploaded<Unit> = { _, _ ->
            callCount += 1
            if (callCount == 2) error("boom")
            HlsUploaded("https://cdn/x", Unit)
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
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
    }

    @Test
    fun `onFailure from transcoder surfaces as completeUpload exception`() {
        val orchestrator =
            HlsUploadOrchestrator<Unit>(
                context,
                uploader = { _, _ -> HlsUploaded("unused", Unit) },
            )
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
        val orchestrator =
            HlsUploadOrchestrator<Unit>(
                context,
                uploader = { _, _ -> HlsUploaded("unused", Unit) },
            )
        orchestrator.onStart(1)
        orchestrator.onCancelled()

        try {
            kotlinx.coroutines.runBlocking { orchestrator.completeUpload() }
            fail("expected CancellationException")
        } catch (e: CancellationException) {
            assertEquals("HLS upload cancelled", e.message)
        }
    }

    @Test
    fun `media playlist upload failure during completeUpload propagates`() {
        val uploader: suspend (File, String) -> HlsUploaded<Unit> = { _, name ->
            if (name.endsWith(".m3u8")) error("playlist upload failed")
            HlsUploaded("https://cdn/$name", Unit)
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
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertEquals("playlist upload failed", e.message)
        }
    }

    @Test
    fun `external listener receives every callback in order`() {
        val uploader: suspend (File, String) -> HlsUploaded<Unit> = { _, name ->
            HlsUploaded("https://cdn/$name", Unit)
        }
        val events = mutableListOf<String>()
        val recorder =
            object : SimpleHlsListener() {
                override fun onStart(renditionCount: Int) {
                    events += "onStart($renditionCount)"
                }

                override fun onRenditionStart(rendition: Rendition) {
                    events += "onRenditionStart(${rendition.resolution.label})"
                }

                override fun onSegmentReady(
                    rendition: Rendition,
                    segment: HlsSegment,
                ) {
                    val label = rendition.resolution.label
                    events += "onSegmentReady($label, idx=${segment.index}, init=${segment.isInitSegment})"
                }

                override fun onRenditionComplete(
                    rendition: Rendition,
                    summary: HlsRenditionSummary,
                ) {
                    events += "onRenditionComplete(${rendition.resolution.label})"
                }

                override fun onComplete(masterPlaylist: String) {
                    events += "onComplete(${masterPlaylist.length}bytes)"
                }

                override fun onProgress(
                    rendition: Rendition,
                    percent: Float,
                ) {
                    events += "onProgress(${rendition.resolution.label}, $percent)"
                }
            }
        val orchestrator = HlsUploadOrchestrator(context, uploader, recorder)

        orchestrator.onStart(1)
        orchestrator.onRenditionStart(rendition720)
        orchestrator.onSegmentReady(
            rendition720,
            HlsSegment(file = segmentFile("init.mp4"), index = 0, durationSeconds = 0.0, isInitSegment = true),
        )
        orchestrator.onProgress(rendition720, 50f)
        orchestrator.onSegmentReady(
            rendition720,
            HlsSegment(file = segmentFile("s000.m4s"), index = 0, durationSeconds = 6.0, isInitSegment = false),
        )
        orchestrator.onRenditionComplete(rendition720, summary(rendition720, "#EXTM3U\n#EXT-X-ENDLIST\n"))
        orchestrator.onComplete("master")

        assertEquals(
            listOf(
                "onStart(1)",
                "onRenditionStart(720p)",
                "onSegmentReady(720p, idx=0, init=true)",
                "onProgress(720p, 50.0)",
                "onSegmentReady(720p, idx=0, init=false)",
                "onRenditionComplete(720p)",
                "onComplete(6bytes)",
            ),
            events,
        )
    }

    @Test
    fun `external listener still receives failure and cancellation`() {
        val uploader: suspend (File, String) -> HlsUploaded<Unit> = { _, _ -> HlsUploaded("unused", Unit) }
        val events = mutableListOf<String>()
        val recorder =
            object : SimpleHlsListener() {
                override fun onFailure(error: HlsError) {
                    events += "onFailure(${error.message})"
                }

                override fun onCancelled() {
                    events += "onCancelled"
                }
            }
        val orchestrator = HlsUploadOrchestrator(context, uploader, recorder)

        orchestrator.onFailure(HlsError("boom", emptyList(), emptyList()))
        orchestrator.onCancelled()

        assertEquals(listOf("onFailure(boom)", "onCancelled"), events)
    }
}
