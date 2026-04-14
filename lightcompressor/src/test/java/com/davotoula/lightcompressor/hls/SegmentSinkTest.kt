package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.Resolution
import com.davotoula.lightcompressor.muxer.EncodedSample
import com.davotoula.lightcompressor.muxer.Mp4SegmentWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class SegmentSinkTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var tempDir: File
    private val rendition = Rendition(Resolution.HD_720, 2500)

    private val sampleAvcCsd =
        byteArrayOf(
            0x00,
            0x00,
            0x00,
            0x01,
            0x67,
            0x42,
            0x00,
            0x0A,
            0x00,
            0x00,
            0x00,
            0x01,
            0x68,
            0xCE.toByte(),
            0x06,
            0xE2.toByte(),
        )

    private fun fakeAnnexBSample(totalSize: Int): ByteArray =
        ByteArray(totalSize).apply {
            this[3] = 0x01
            this[4] = 0x65.toByte()
        }

    private fun newWriter(): Mp4SegmentWriter =
        Mp4SegmentWriter(
            videoCodecConfig = sampleAvcCsd,
            videoMimeType = "video/avc",
            videoWidth = 1280,
            videoHeight = 720,
            audioConfig = null,
        )

    private fun newFlushed(
        sequenceNumber: Int,
        baseDecodeTimeUs: Long,
        sampleSize: Int,
        durationUs: Long,
    ): FlushedSegment =
        FlushedSegment(
            videoSamples =
                listOf(
                    EncodedSample(
                        data = fakeAnnexBSample(sampleSize),
                        presentationTimeUs = baseDecodeTimeUs,
                        durationUs = durationUs,
                        flags = 1,
                    ),
                ),
            audioSamples = emptyList(),
            sequenceNumber = sequenceNumber,
            baseDecodeTimeUs = baseDecodeTimeUs,
            durationUs = durationUs,
        )

    @Before
    fun setUp() {
        tempDir = tempFolder.newFolder("sink")
    }

    @Test
    fun `multi-file sink emits init plus segment per write and deletes temp files`() {
        val listener = RecordingHlsListener()
        val sink = MultiFileSegmentSink(rendition, listener, tempDir)

        sink.writeInit(newWriter())
        sink.writeMedia(newWriter(), newFlushed(1, 0L, 100, 6_000_000L))
        sink.writeMedia(newWriter(), newFlushed(2, 6_000_000L, 80, 6_000_000L))
        sink.finish()

        assertEquals(3, listener.segments.size)
        assertTrue("first callback should be init", listener.segments[0].isInitSegment)
        assertFalse(listener.segments[1].isInitSegment)
        assertEquals(0, listener.segments[1].index)
        assertEquals(1, listener.segments[2].index)
        // Multi-file sink deletes temp files synchronously after each callback returns.
        for (segment in listener.segments) {
            assertFalse("temp file ${segment.file} should be deleted", segment.file.exists())
        }
    }

    @Test
    fun `multi-file sink builds playlist with one EXTINF per media segment`() {
        val listener = RecordingHlsListener()
        val sink = MultiFileSegmentSink(rendition, listener, tempDir)

        sink.writeInit(newWriter())
        sink.writeMedia(newWriter(), newFlushed(1, 0L, 100, 6_000_000L))
        sink.writeMedia(newWriter(), newFlushed(2, 6_000_000L, 80, 6_000_000L))
        sink.finish()

        val playlist = sink.buildPlaylist(targetDurationSeconds = 7)
        assertEquals(2, Regex("#EXTINF:").findAll(playlist).count())
        assertTrue("references init.mp4", "#EXT-X-MAP:URI=\"init.mp4\"" in playlist)
        assertTrue("references first segment", "segment_000.m4s" in playlist)
        assertTrue("references second segment", "segment_001.m4s" in playlist)
    }

    @Test
    fun `single-file sink defers callback until finish and emits one combined segment`() {
        val listener = RecordingHlsListener()
        val sink = SingleFileSegmentSink(rendition, listener, tempDir)

        sink.writeInit(newWriter())
        sink.writeMedia(newWriter(), newFlushed(1, 0L, 100, 6_000_000L))
        sink.writeMedia(newWriter(), newFlushed(2, 6_000_000L, 80, 5_500_000L))

        assertEquals("no callback before finish", 0, listener.segments.size)

        sink.finish()

        assertEquals("exactly one callback at finish", 1, listener.segments.size)
        val combined = listener.segments.single()
        assertTrue(combined.isCombinedRendition)
        assertFalse(combined.isInitSegment)
        assertEquals(0, combined.index)
        assertEquals(11.5, combined.durationSeconds, 0.0001)
    }

    @Test
    fun `single-file sink playlist byte ranges cover the combined file end-to-end`() {
        // Mirror the sink's writes against an in-memory writer to compute the exact byte
        // sizes expected at each boundary, then verify the playlist references match.
        val mirror = ByteArrayOutputStream()
        val mirrorWriter = newWriter()
        mirrorWriter.writeInitSegment(mirror)
        val expectedInitLength = mirror.size().toLong()

        mirrorWriter.writeMediaSegment(
            videoSamples = newFlushed(1, 0L, 100, 6_000_000L).videoSamples,
            audioSamples = emptyList(),
            sequenceNumber = 1,
            baseDecodeTimeUs = 0L,
            output = mirror,
        )
        val expectedFirstEnd = mirror.size().toLong()
        val expectedFirstLength = expectedFirstEnd - expectedInitLength

        mirrorWriter.writeMediaSegment(
            videoSamples = newFlushed(2, 6_000_000L, 80, 6_000_000L).videoSamples,
            audioSamples = emptyList(),
            sequenceNumber = 2,
            baseDecodeTimeUs = 6_000_000L,
            output = mirror,
        )
        val expectedTotal = mirror.size().toLong()
        val expectedSecondLength = expectedTotal - expectedFirstEnd

        val listener = RecordingHlsListener()
        val sink = SingleFileSegmentSink(rendition, listener, tempDir)
        sink.writeInit(newWriter())
        sink.writeMedia(newWriter(), newFlushed(1, 0L, 100, 6_000_000L))
        sink.writeMedia(newWriter(), newFlushed(2, 6_000_000L, 80, 6_000_000L))
        sink.finish()

        val playlist = sink.buildPlaylist(targetDurationSeconds = 7)
        assertTrue(
            "init range matches mirror writer init length",
            "#EXT-X-MAP:URI=\"720p.mp4\",BYTERANGE=\"$expectedInitLength@0\"" in playlist,
        )
        assertTrue(
            "first segment byte range matches first fragment size",
            "#EXT-X-BYTERANGE:$expectedFirstLength@$expectedInitLength" in playlist,
        )
        assertTrue(
            "second segment byte range starts where the first ends",
            "#EXT-X-BYTERANGE:$expectedSecondLength@$expectedFirstEnd" in playlist,
        )
    }

    @Test
    fun `single-file sink lets listener observe combined file before deletion`() {
        var observedSize: Long = -1L
        var existedDuringCallback = false
        val listener =
            object : RecordingHlsListener() {
                override fun onSegmentReady(
                    rendition: Rendition,
                    segment: HlsSegment,
                ) {
                    super.onSegmentReady(rendition, segment)
                    existedDuringCallback = segment.file.exists()
                    observedSize = segment.file.length()
                }
            }
        val sink = SingleFileSegmentSink(rendition, listener, tempDir)

        sink.writeInit(newWriter())
        sink.writeMedia(newWriter(), newFlushed(1, 0L, 100, 6_000_000L))
        sink.finish()

        assertTrue("file must exist while listener owns it", existedDuringCallback)
        assertTrue("file should have actual content", observedSize > 0)
        // After the callback returns the temp file is deleted.
        val callbackFile = listener.segments.single().file
        assertFalse("temp file should be deleted after finish", callbackFile.exists())
    }

    @Test
    fun `single-file sink close before finish releases stream without emitting callback`() {
        val listener = RecordingHlsListener()
        val sink = SingleFileSegmentSink(rendition, listener, tempDir)

        sink.writeInit(newWriter())
        sink.writeMedia(newWriter(), newFlushed(1, 0L, 100, 6_000_000L))
        sink.close()

        assertEquals("no callback should fire on close-only", 0, listener.segments.size)
    }

    @Test
    fun `single-file sink playlist target duration uses sink reported max`() {
        val listener = RecordingHlsListener()
        val sink = SingleFileSegmentSink(rendition, listener, tempDir)

        sink.writeInit(newWriter())
        sink.writeMedia(newWriter(), newFlushed(1, 0L, 100, 6_000_000L))
        sink.writeMedia(newWriter(), newFlushed(2, 6_000_000L, 80, 4_500_000L))
        sink.finish()

        assertEquals(2, sink.mediaSegmentCount)
        assertEquals(6.0, sink.maxSegmentDurationSeconds, 0.0001)
    }

    private open class RecordingHlsListener : HlsListener {
        val segments = mutableListOf<HlsSegment>()

        override fun onStart(renditionCount: Int) = Unit

        override fun onRenditionStart(rendition: Rendition) = Unit

        override fun onSegmentReady(
            rendition: Rendition,
            segment: HlsSegment,
        ) {
            // Snapshot file metadata while the file is still on disk, since the sink may
            // delete the underlying temp file once this method returns.
            assertNotNull(segment.file)
            segments.add(segment)
        }

        override fun onRenditionComplete(
            rendition: Rendition,
            playlist: String,
        ) = Unit

        override fun onComplete(masterPlaylist: String) = Unit

        override fun onFailure(error: HlsError) = Unit

        override fun onProgress(
            rendition: Rendition,
            percent: Float,
        ) = Unit

        override fun onCancelled() = Unit
    }
}
