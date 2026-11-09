package com.davotoula.lightcompressor.sanitizer

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import com.davotoula.lightcompressor.atom
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

class Mp4SanitizerTest {
    private val context: Context = mockk()
    private val uri: Uri = mockk()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun isFastStart_moovBeforeMdat_isTrue() {
        assertTrue(Mp4Sanitizer.isFastStart(file(atom("ftyp", ByteArray(4)), atom("moov", ByteArray(8)), mdat())))
    }

    @Test
    fun isFastStart_moovAfterMdat_isFalse() {
        assertFalse(Mp4Sanitizer.isFastStart(file(atom("ftyp", ByteArray(4)), mdat(), atom("moov", ByteArray(8)))))
    }

    @Test
    fun isFastStart_noMdat_isFalse() {
        assertFalse(Mp4Sanitizer.isFastStart(file(atom("ftyp", ByteArray(4)), atom("moov", ByteArray(8)))))
    }

    @Test
    fun isFastStart_corruptSize_isFalse() {
        // ftyp claims to run past the end of the file, so the walk stops before reaching moov.
        val corruptFtyp =
            ByteBuffer
                .allocate(12)
                .putInt(4096)
                .put("ftyp".toByteArray())
                .putInt(0)
                .array()
        assertFalse(Mp4Sanitizer.isFastStart(file(corruptFtyp, atom("moov", ByteArray(8)), mdat())))
    }

    @Test
    fun isFastStart_largeSizeMdat_isTrue() {
        // size 1: the real size follows the type as a 64-bit value.
        val largeMdat =
            ByteBuffer
                .allocate(24)
                .putInt(1)
                .put("mdat".toByteArray())
                .putLong(24)
                .array()
        assertTrue(Mp4Sanitizer.isFastStart(file(atom("moov", ByteArray(8)), largeMdat)))
    }

    @Test
    fun isFastStart_missingFile_isFalse() {
        assertFalse(Mp4Sanitizer.isFastStart(File("does/not/exist.mp4")))
    }

    @Test
    fun isMuxableIntoMp4_acceptsMpeg4Mimes() {
        listOf(
            "video/avc",
            "video/hevc",
            "video/mp4v-es",
            "video/3gpp",
            "audio/mp4a-latm",
            "audio/3gpp",
            "audio/amr-wb",
        ).forEach { assertTrue(it, Mp4Sanitizer.isMuxableIntoMp4(it)) }
    }

    @Test
    fun isMuxableIntoMp4_rejectsOthers() {
        listOf("audio/opus", "audio/mpeg", "application/octet-stream", "")
            .forEach { assertFalse(it, Mp4Sanitizer.isMuxableIntoMp4(it)) }
    }

    @Test
    fun sanitize_unmuxableTrack_throwsNamingMimeAndLeavesNoOutput() {
        val output = File.createTempFile("sanitized", ".mp4").apply { deleteOnExit() }
        val extractor = extractorWith("audio/opus")

        val error =
            assertThrows(IOException::class.java) {
                Mp4Sanitizer.sanitize(context, uri, output, extractor)
            }

        assertTrue(error.message, error.message!!.contains("audio/opus"))
        assertFalse("output should not exist", output.exists())
        assertFalse("muxer temp file should not exist", File(output.path + ".mux.tmp").exists())
        verify { extractor.release() }
    }

    @Test
    fun sanitize_noTracks_throws() {
        val output = File.createTempFile("sanitized", ".mp4").apply { deleteOnExit() }

        val error =
            assertThrows(IOException::class.java) {
                Mp4Sanitizer.sanitize(context, uri, output, extractorWith())
            }

        assertEquals("Source has no audio or video track", error.message)
        assertFalse("output should not exist", output.exists())
    }

    @Test
    fun sanitize_onlyMetadataTrack_dropsItAndThrows() {
        val output = File.createTempFile("sanitized", ".mp4").apply { deleteOnExit() }

        val error =
            assertThrows(IOException::class.java) {
                Mp4Sanitizer.sanitize(context, uri, output, extractorWith("application/meta"))
            }

        assertEquals("Source has no audio or video track", error.message)
        assertFalse("output should not exist", output.exists())
        verify { Log.i(any(), match<String> { "application/meta" in it }) }
    }

    @Test
    fun sanitize_metadataTrackIsDroppedNotRejected() {
        val output = File.createTempFile("sanitized", ".mp4").apply { deleteOnExit() }

        val extractor = extractorWith("application/meta", "audio/opus")

        // The metadata track is skipped; the unmuxable audio track after it is what fails.
        val error =
            assertThrows(IOException::class.java) {
                Mp4Sanitizer.sanitize(context, uri, output, extractor)
            }

        assertEquals("Track 1 (audio/opus) cannot be written to an MPEG-4 container", error.message)
        verify(exactly = 0) { extractor.selectTrack(any()) }
    }

    private fun extractorWith(vararg mimes: String): MediaExtractor {
        val extractor = mockk<MediaExtractor>()
        every { extractor.setDataSource(context, uri, null) } just runs
        every { extractor.trackCount } returns mimes.size
        every { extractor.release() } just runs
        mimes.forEachIndexed { i, mime ->
            val format = mockk<MediaFormat>()
            every { format.getString(MediaFormat.KEY_MIME) } returns mime
            every { extractor.getTrackFormat(i) } returns format
        }
        return extractor
    }

    private fun mdat() = atom("mdat", ByteArray(8))

    private fun file(vararg atoms: ByteArray) =
        File.createTempFile("faststart", ".mp4").apply {
            deleteOnExit()
            writeBytes(atoms.fold(ByteArray(0)) { acc, a -> acc + a })
        }
}
