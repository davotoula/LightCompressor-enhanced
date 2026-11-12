package com.davotoula.lightcompressor.utils

import android.util.Log
import com.davotoula.lightcompressor.atom
import com.davotoula.lightcompressor.stco
import com.davotoula.lightcompressor.topLevelAtomTypes
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

class FastStartFinisherTest {
    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        mockkObject(StreamableVideo)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun moovAfterMdat_isConvertedAndMuxerFileDeleted() {
        // stco must point into mdat's payload: ftyp (12) + mdat header (8).
        val muxerFile = tempFile(atom("ftyp", ByteArray(4)) + atom("mdat", ByteArray(8)) + atom("moov", stco(20)))
        val destination = missingFile()

        val fastStart = finishFastStart(muxerFile, destination)

        assertTrue(fastStart)
        assertEquals(listOf("ftyp", "moov", "mdat"), topLevelAtomTypes(destination))
        assertFalse("muxer file should be removed", muxerFile.exists())
    }

    @Test
    fun alreadyFastStart_movesMuxerFileAndReportsFastStart() {
        val bytes = atom("ftyp", ByteArray(4)) + atom("moov", stco(40)) + atom("mdat", ByteArray(8))
        val muxerFile = tempFile(bytes)
        val destination = missingFile()

        val fastStart = finishFastStart(muxerFile, destination)

        assertTrue(fastStart)
        assertArrayEquals(bytes, destination.readBytes())
        assertFalse("muxer file should be removed", muxerFile.exists())
        verify(exactly = 1) { StreamableVideo.start(muxerFile, destination) }
    }

    @Test
    fun conversionThrows_movesMuxerFileAndReportsNotFastStart() {
        val bytes = atom("ftyp", ByteArray(4)) + atom("mdat", ByteArray(8)) + atom("moov", stco(20))
        val muxerFile = tempFile(bytes)
        val destination = missingFile()
        every { StreamableVideo.start(muxerFile, destination) } throws IOException("unreadable atom")

        val fastStart = finishFastStart(muxerFile, destination)

        assertFalse(fastStart)
        assertArrayEquals(bytes, destination.readBytes())
        assertFalse("muxer file should be removed", muxerFile.exists())
        verify(exactly = 1) { Log.w(any(), match<String> { destination.path in it }) }
    }

    @Test
    fun existingDestination_isReplacedByTheConversion() {
        val muxerFile = tempFile(atom("ftyp", ByteArray(4)) + atom("mdat", ByteArray(8)) + atom("moov", stco(20)))
        // Longer than the converted file, so a destination that was not truncated would keep a stale tail.
        val destination = tempFile(ByteArray(256) { 1 })

        val fastStart = finishFastStart(muxerFile, destination)

        assertTrue(fastStart)
        assertEquals(listOf("ftyp", "moov", "mdat"), topLevelAtomTypes(destination))
        assertFalse("muxer file should be removed", muxerFile.exists())
    }

    @Test
    fun existingDestination_isReplaced() {
        val bytes = atom("ftyp", ByteArray(4)) + atom("moov", stco(40)) + atom("mdat", ByteArray(8))
        val muxerFile = tempFile(bytes)
        val destination = tempFile("stale".toByteArray())

        finishFastStart(muxerFile, destination)

        assertArrayEquals(bytes, destination.readBytes())
    }

    @Test
    fun deleteOrWarn_logsOnlyWhenTheFileSurvives() {
        // File.delete can't remove a non-empty directory, so this one survives.
        val stuck =
            kotlin.io.path
                .createTempDirectory("stuck")
                .toFile()
                .apply { deleteOnExit() }
        val child = File(stuck, "child").apply { writeText("x") }
        val missing = missingFile()

        stuck.deleteOrWarn("test")
        missing.deleteOrWarn("test")

        verify(exactly = 1) { Log.w("test", "Could not delete ${stuck.path}") }
        verify(exactly = 1) { Log.w(any(), any<String>()) }
        child.delete()
        stuck.delete()
    }

    private fun tempFile(bytes: ByteArray) =
        File.createTempFile("finisher", ".mp4").apply {
            deleteOnExit()
            writeBytes(bytes)
        }

    private fun missingFile() =
        File.createTempFile("finisher_dest", ".mp4").apply {
            deleteOnExit()
            delete()
        }
}
