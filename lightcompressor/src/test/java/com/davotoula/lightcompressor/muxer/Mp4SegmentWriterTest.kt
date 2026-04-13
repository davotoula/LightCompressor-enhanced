package com.davotoula.lightcompressor.muxer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Mp4SegmentWriterTest {
    private fun readBoxHeader(bb: ByteBuffer): Pair<Int, String> {
        val size = bb.getInt()
        val typeBytes = ByteArray(4)
        bb.get(typeBytes)
        return size to String(typeBytes, Charsets.US_ASCII)
    }

    @Test
    fun `init segment starts with ftyp box`() {
        val writer =
            Mp4SegmentWriter(
                videoCodecConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A),
                videoMimeType = "video/avc",
                videoWidth = 640,
                videoHeight = 360,
                videoTimescale = 90000,
                audioConfig = null,
            )
        val out = ByteArrayOutputStream()
        writer.writeInitSegment(out)
        val bytes = out.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val (ftypSize, ftypType) = readBoxHeader(bb)
        assertEquals("ftyp", ftypType)
        assertTrue("ftyp box size should be > 8", ftypSize > 8)
    }

    @Test
    fun `init segment has moov box after ftyp`() {
        val writer =
            Mp4SegmentWriter(
                videoCodecConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A),
                videoMimeType = "video/avc",
                videoWidth = 640,
                videoHeight = 360,
                videoTimescale = 90000,
                audioConfig = null,
            )
        val out = ByteArrayOutputStream()
        writer.writeInitSegment(out)
        val bytes = out.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val (ftypSize, _) = readBoxHeader(bb)
        bb.position(ftypSize)
        val (_, moovType) = readBoxHeader(bb)
        assertEquals("moov", moovType)
    }

    @Test
    fun `init segment moov contains mvhd, trak, mvex`() {
        val writer =
            Mp4SegmentWriter(
                videoCodecConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A),
                videoMimeType = "video/avc",
                videoWidth = 640,
                videoHeight = 360,
                videoTimescale = 90000,
                audioConfig = null,
            )
        val out = ByteArrayOutputStream()
        writer.writeInitSegment(out)
        val bytes = out.toByteArray()
        val childTypes = findChildBoxTypes(bytes, "moov")
        assertTrue("moov must contain mvhd", "mvhd" in childTypes)
        assertTrue("moov must contain trak", "trak" in childTypes)
        assertTrue("moov must contain mvex", "mvex" in childTypes)
    }

    @Test
    fun `init segment with audio has two trak boxes`() {
        val writer =
            Mp4SegmentWriter(
                videoCodecConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A),
                videoMimeType = "video/avc",
                videoWidth = 640,
                videoHeight = 360,
                videoTimescale = 90000,
                audioConfig =
                    AudioConfig(
                        codecConfig = byteArrayOf(0x12, 0x10),
                        sampleRate = 44100,
                        channelCount = 2,
                        timescale = 44100,
                    ),
            )
        val out = ByteArrayOutputStream()
        writer.writeInitSegment(out)
        val bytes = out.toByteArray()
        val childTypes = findChildBoxTypes(bytes, "moov")
        assertEquals("moov should have 2 trak boxes", 2, childTypes.count { it == "trak" })
    }

    /** Finds the top-level box with [parentType] and lists its direct children's types. */
    private fun findChildBoxTypes(
        data: ByteArray,
        parentType: String,
    ): List<String> {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        while (bb.remaining() >= 8) {
            val startPos = bb.position()
            val (size, type) = readBoxHeader(bb)
            if (type == parentType) {
                val children = mutableListOf<String>()
                val endPos = startPos + size
                while (bb.position() + 8 <= endPos) {
                    val childStart = bb.position()
                    val (childSize, childType) = readBoxHeader(bb)
                    children.add(childType)
                    bb.position(childStart + childSize)
                }
                return children
            }
            bb.position(startPos + size)
        }
        return emptyList()
    }

    private fun createVideoOnlyWriter() =
        Mp4SegmentWriter(
            videoCodecConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A),
            videoMimeType = "video/avc",
            videoWidth = 640,
            videoHeight = 360,
            videoTimescale = 90000,
            audioConfig = null,
        )

    @Test
    fun `media segment starts with moof box`() {
        val writer = createVideoOnlyWriter()
        val samples =
            listOf(
                EncodedSample(
                    data = ByteArray(100),
                    presentationTimeUs = 0L,
                    durationUs = 33333L,
                    flags = 1,
                ),
            )
        val out = ByteArrayOutputStream()
        writer.writeMediaSegment(
            videoSamples = samples,
            audioSamples = emptyList(),
            sequenceNumber = 1,
            baseDecodeTimeUs = 0L,
            output = out,
        )
        val bytes = out.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val (_, type) = readBoxHeader(bb)
        assertEquals("moof", type)
    }

    @Test
    fun `media segment has mdat after moof`() {
        val writer = createVideoOnlyWriter()
        val samples =
            listOf(
                EncodedSample(
                    data = ByteArray(100),
                    presentationTimeUs = 0L,
                    durationUs = 33333L,
                    flags = 1,
                ),
            )
        val out = ByteArrayOutputStream()
        writer.writeMediaSegment(
            videoSamples = samples,
            audioSamples = emptyList(),
            sequenceNumber = 1,
            baseDecodeTimeUs = 0L,
            output = out,
        )
        val bytes = out.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val (moofSize, _) = readBoxHeader(bb)
        bb.position(moofSize)
        val (_, mdatType) = readBoxHeader(bb)
        assertEquals("mdat", mdatType)
    }

    @Test
    fun `media segment mdat size equals header plus sample data`() {
        val writer = createVideoOnlyWriter()
        val sampleData = ByteArray(256) { it.toByte() }
        val samples =
            listOf(
                EncodedSample(
                    data = sampleData,
                    presentationTimeUs = 0L,
                    durationUs = 33333L,
                    flags = 1,
                ),
            )
        val out = ByteArrayOutputStream()
        writer.writeMediaSegment(
            videoSamples = samples,
            audioSamples = emptyList(),
            sequenceNumber = 1,
            baseDecodeTimeUs = 0L,
            output = out,
        )
        val bytes = out.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val (moofSize, _) = readBoxHeader(bb)
        bb.position(moofSize)
        val (mdatSize, _) = readBoxHeader(bb)
        assertEquals(8 + 256, mdatSize) // header + sample data
    }

    @Test
    fun `media segment moof contains mfhd and traf`() {
        val writer = createVideoOnlyWriter()
        val samples =
            listOf(
                EncodedSample(
                    data = ByteArray(50),
                    presentationTimeUs = 0L,
                    durationUs = 33333L,
                    flags = 1,
                ),
            )
        val out = ByteArrayOutputStream()
        writer.writeMediaSegment(
            videoSamples = samples,
            audioSamples = emptyList(),
            sequenceNumber = 1,
            baseDecodeTimeUs = 0L,
            output = out,
        )
        val bytes = out.toByteArray()
        val childTypes = findChildBoxTypes(bytes, "moof")
        assertTrue("moof must contain mfhd", "mfhd" in childTypes)
        assertTrue("moof must contain traf", "traf" in childTypes)
    }

    @Test
    fun `media segment trun data offset points past moof into mdat`() {
        val writer = createVideoOnlyWriter()
        val samples =
            listOf(
                EncodedSample(
                    data = ByteArray(100),
                    presentationTimeUs = 0L,
                    durationUs = 33333L,
                    flags = 1,
                ),
            )
        val out = ByteArrayOutputStream()
        writer.writeMediaSegment(
            videoSamples = samples,
            audioSamples = emptyList(),
            sequenceNumber = 1,
            baseDecodeTimeUs = 0L,
            output = out,
        )
        val bytes = out.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val (moofSize, _) = readBoxHeader(bb)
        // Find the trun box inside moof > traf
        // After moof header (8) + mfhd (16) + traf header (8) + tfhd (16) + tfdt (20) = trun at offset 68
        bb.position(68)
        val (_, trunType) = readBoxHeader(bb)
        assertEquals("trun", trunType)
        bb.getInt() // skip version+flags
        bb.getInt() // skip sample count
        val dataOffset = bb.getInt() // data offset
        assertEquals("data offset should point to start of mdat data", moofSize + 8, dataOffset)
    }

    @Test
    fun `media segment with audio has two traf boxes in moof`() {
        val writer =
            Mp4SegmentWriter(
                videoCodecConfig = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x0A),
                videoMimeType = "video/avc",
                videoWidth = 640,
                videoHeight = 360,
                videoTimescale = 90000,
                audioConfig =
                    AudioConfig(
                        codecConfig = byteArrayOf(0x12, 0x10),
                        sampleRate = 44100,
                        channelCount = 2,
                        timescale = 44100,
                    ),
            )
        val videoSamples =
            listOf(
                EncodedSample(
                    data = ByteArray(100),
                    presentationTimeUs = 0L,
                    durationUs = 33333L,
                    flags = 1,
                ),
            )
        val audioSamples =
            listOf(
                EncodedSample(
                    data = ByteArray(50),
                    presentationTimeUs = 0L,
                    durationUs = 23219L,
                    flags = 0,
                ),
            )
        val out = ByteArrayOutputStream()
        writer.writeMediaSegment(
            videoSamples = videoSamples,
            audioSamples = audioSamples,
            sequenceNumber = 1,
            baseDecodeTimeUs = 0L,
            output = out,
        )
        val bytes = out.toByteArray()
        val childTypes = findChildBoxTypes(bytes, "moof")
        assertEquals("moof should have mfhd + 2 trafs", 3, childTypes.size)
        assertEquals("mfhd", childTypes[0])
        assertEquals("traf", childTypes[1])
        assertEquals("traf", childTypes[2])
    }
}
