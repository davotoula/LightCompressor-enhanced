package com.abedelazizshe.lightcompressorlibrary.video

import android.media.MediaCodec
import android.media.MediaFormat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer

class MP4BuilderTest {

    @Before
    fun setUp() {
        mockkStatic(MediaFormat::class)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun writesLengthPrefixedNalsVerbatim() {
        val (builder, movie, tempFile) = buildFixture()

        val sampleBytes = ByteBuffer.allocate(4 + 2 + 4 + 3).apply {
            putInt(2)
            put(byteArrayOf(0x01.toByte(), 0x02.toByte()))
            putInt(3)
            put(byteArrayOf(0x03.toByte(), 0x04.toByte(), 0x05.toByte()))
        }.array()

        val bufferInfo = MediaCodec.BufferInfo().apply {
            offset = 0
            size = sampleBytes.size
            presentationTimeUs = 0
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
        }

        builder.writeSampleData(0, ByteBuffer.wrap(sampleBytes), bufferInfo, false)
        builder.flushCurrentMdat()

        val writtenBytes = tempFile.readBytes()
        val tail = writtenBytes.copyOfRange(writtenBytes.size - sampleBytes.size, writtenBytes.size)
        assertArrayEquals(sampleBytes, tail)
    }

    @Test
    fun convertsStartCodePrefixedNalsToLengthPrefixed() {
        val (builder, _, tempFile) = buildFixture()

        val sampleBytes = byteArrayOf(
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x01.toByte(), 0x02.toByte(), 0x03.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
            0x04.toByte(), 0x05.toByte(), 0x06.toByte(), 0x07.toByte()
        )

        val bufferInfo = MediaCodec.BufferInfo().apply {
            offset = 0
            size = sampleBytes.size
            presentationTimeUs = 0
            flags = MediaCodec.BUFFER_FLAG_KEY_FRAME
        }

        builder.writeSampleData(0, ByteBuffer.wrap(sampleBytes), bufferInfo, false)
        builder.flushCurrentMdat()

        val writtenBytes = tempFile.readBytes()
        val expected = byteArrayOf(
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x03.toByte(),
            0x01.toByte(), 0x02.toByte(), 0x03.toByte(),
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(),
            0x04.toByte(), 0x05.toByte(), 0x06.toByte(), 0x07.toByte()
        )

        val tail = writtenBytes.copyOfRange(writtenBytes.size - expected.size, writtenBytes.size)
        assertArrayEquals(expected, tail)
    }

    private fun buildFixture(): Triple<MP4Builder, Mp4Movie, File> {
        val tempFile = File.createTempFile("mp4builder", ".mp4").apply { deleteOnExit() }
        val movie = mockk<Mp4Movie>(relaxed = true)

        every { movie.getCacheFile() } returns tempFile
        every { movie.addTrack(any(), any()) } returns 0
        every { movie.addSample(any(), any(), any()) } returns Unit
        every { movie.getTracks() } returns arrayListOf()

        val builder = MP4Builder()
        builder.createMovie(movie)
        val mediaFormat = mockk<MediaFormat>(relaxed = true)
        every { mediaFormat.getString(MediaFormat.KEY_MIME) } returns "video/avc"
        builder.addTrack(mediaFormat, false)

        return Triple(builder, movie, tempFile)
    }
}
