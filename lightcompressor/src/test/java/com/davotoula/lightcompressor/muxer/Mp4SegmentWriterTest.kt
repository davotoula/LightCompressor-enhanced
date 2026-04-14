package com.davotoula.lightcompressor.muxer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Mp4SegmentWriterTest {
    private companion object {
        // Minimal SPS (Baseline profile, level 1.0) + PPS pair in Annex-B form. Both NAL units
        // are required for buildAvcDecoderConfigurationRecord to produce a valid avcC box.
        private val SAMPLE_AVC_CSD =
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
                0x06.toByte(),
                0xE2.toByte(),
            )
    }

    private fun readBoxHeader(bb: ByteBuffer): Pair<Int, String> {
        val size = bb.getInt()
        val typeBytes = ByteArray(4)
        bb.get(typeBytes)
        return size to String(typeBytes, Charsets.US_ASCII)
    }

    /**
     * Build an Annex-B IDR frame of exactly [totalSize] bytes: 4-byte start code, 1-byte NAL header
     * (0x65 = IDR slice), and zero-padded payload. After [Mp4SegmentWriter] runs Annex-B → length-
     * prefixed conversion the output size is identical (4-byte length + (totalSize - 4)-byte NAL),
     * so size-based assertions in the tests below stay aligned.
     */
    private fun fakeAnnexBSample(totalSize: Int): ByteArray {
        require(totalSize >= 5) { "totalSize must be at least 5 bytes (start code + NAL header)" }
        return ByteArray(totalSize).apply {
            this[3] = 0x01
            this[4] = 0x65.toByte()
        }
    }

    @Test
    fun `init segment starts with ftyp box`() {
        val out = ByteArrayOutputStream()
        createVideoOnlyWriter().writeInitSegment(out)
        val bb = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.BIG_ENDIAN)
        val (ftypSize, ftypType) = readBoxHeader(bb)
        assertEquals("ftyp", ftypType)
        assertTrue("ftyp box size should be > 8", ftypSize > 8)
    }

    @Test
    fun `init segment has moov box after ftyp`() {
        val out = ByteArrayOutputStream()
        createVideoOnlyWriter().writeInitSegment(out)
        val bb = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.BIG_ENDIAN)
        val (ftypSize, _) = readBoxHeader(bb)
        bb.position(ftypSize)
        val (_, moovType) = readBoxHeader(bb)
        assertEquals("moov", moovType)
    }

    @Test
    fun `init segment moov contains mvhd, trak, mvex`() {
        val out = ByteArrayOutputStream()
        createVideoOnlyWriter().writeInitSegment(out)
        val childTypes = findChildBoxTypes(out.toByteArray(), "moov")
        assertTrue("moov must contain mvhd", "mvhd" in childTypes)
        assertTrue("moov must contain trak", "trak" in childTypes)
        assertTrue("moov must contain mvex", "mvex" in childTypes)
    }

    /**
     * Linear scan for the first box with the given fourcc and return its content (size and type
     * header stripped). Test inputs are chosen so the 4-byte literal cannot collide with payload
     * data, which lets us avoid a full recursive box walker.
     */
    private fun findFirstBox(
        data: ByteArray,
        fourcc: String,
    ): ByteArray? {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        for (i in 0..data.size - 8) {
            bb.position(i)
            val (size, type) = readBoxHeader(bb)
            if (type == fourcc && size in 8..(data.size - i)) {
                val content = ByteArray(size - 8)
                bb.get(content)
                return content
            }
        }
        return null
    }

    @Test
    fun `init segment avcC round-trips profile, level, SPS, and PPS from csd`() {
        // Payload bytes deliberately avoid the ASCII sequence "avcC" so findFirstBox cannot
        // collide with them on its linear scan.
        val sps =
            byteArrayOf(
                0x67.toByte(), // NAL header: SPS, nal_ref_idc = 3
                0x64.toByte(), // profile_idc = High (100)
                0x00.toByte(), // constraint_set_flags
                0x28.toByte(), // level_idc = 40 (Level 4.0)
                0xAC.toByte(),
                0xD9.toByte(),
            )
        val pps =
            byteArrayOf(
                0x68.toByte(), // NAL header: PPS (type 8)
                0xEB.toByte(),
                0xE3.toByte(),
                0xCB.toByte(),
            )
        val csd =
            byteArrayOf(0x00, 0x00, 0x00, 0x01) + sps +
                byteArrayOf(0x00, 0x00, 0x00, 0x01) + pps
        val writer =
            Mp4SegmentWriter(
                videoCodecConfig = csd,
                videoMimeType = "video/avc",
                videoWidth = 1280,
                videoHeight = 720,
                videoTimescale = 90000,
                audioConfig = null,
            )
        val out = ByteArrayOutputStream()
        writer.writeInitSegment(out)
        val initBytes = out.toByteArray()

        val avcC =
            requireNotNull(findFirstBox(initBytes, "avcC")) {
                "init segment must contain an avcC box"
            }

        // AVCDecoderConfigurationRecord layout per ISO/IEC 14496-15 §5.2.4.1
        assertEquals("configurationVersion", 0x01, avcC[0].toInt() and 0xFF)
        assertEquals("AVCProfileIndication", 0x64, avcC[1].toInt() and 0xFF)
        assertEquals("profile_compatibility", 0x00, avcC[2].toInt() and 0xFF)
        assertEquals("AVCLevelIndication", 0x28, avcC[3].toInt() and 0xFF)
        assertEquals(
            "lengthSizeMinusOne flags byte (0xFC | 3 = 0xFF)",
            0xFF,
            avcC[4].toInt() and 0xFF,
        )
        assertEquals(
            "numOfSequenceParameterSets flags byte (0xE0 | 1)",
            0xE1,
            avcC[5].toInt() and 0xFF,
        )

        val spsLen = ((avcC[6].toInt() and 0xFF) shl 8) or (avcC[7].toInt() and 0xFF)
        assertEquals("SPS length prefix", sps.size, spsLen)
        assertArrayEquals(
            "SPS bytes round-trip verbatim",
            sps,
            avcC.copyOfRange(8, 8 + spsLen),
        )

        val numPpsOffset = 8 + spsLen
        assertEquals("numOfPictureParameterSets", 1, avcC[numPpsOffset].toInt() and 0xFF)
        val ppsLenOffset = numPpsOffset + 1
        val ppsLen =
            ((avcC[ppsLenOffset].toInt() and 0xFF) shl 8) or
                (avcC[ppsLenOffset + 1].toInt() and 0xFF)
        assertEquals("PPS length prefix", pps.size, ppsLen)
        val ppsStart = ppsLenOffset + 2
        assertArrayEquals(
            "PPS bytes round-trip verbatim",
            pps,
            avcC.copyOfRange(ppsStart, ppsStart + ppsLen),
        )

        // High-profile extension fields: chroma_format=4:2:0, bit_depth_luma=8, bit_depth_chroma=8.
        val extOffset = ppsStart + ppsLen
        assertEquals("chroma_format byte", 0xFD, avcC[extOffset].toInt() and 0xFF)
        assertEquals("bit_depth_luma byte", 0xF8, avcC[extOffset + 1].toInt() and 0xFF)
        assertEquals("bit_depth_chroma byte", 0xF8, avcC[extOffset + 2].toInt() and 0xFF)
        assertEquals("numOfSequenceParameterSetExt", 0x00, avcC[extOffset + 3].toInt() and 0xFF)
    }

    @Test
    fun `init segment throws when AVC csd contains no SPS`() {
        // AUD-only csd: NAL type 9 (access unit delimiter), no SPS or PPS. The avcC builder
        // returns null because there is no SPS to copy profile/level bytes from. The writer must
        // fail loudly rather than silently emit a broken AVCDecoderConfigurationRecord.
        val csdWithoutSps =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x09.toByte(), // NAL header: AUD (type 9)
                0x10.toByte(),
            )
        val writer =
            Mp4SegmentWriter(
                videoCodecConfig = csdWithoutSps,
                videoMimeType = "video/avc",
                videoWidth = 640,
                videoHeight = 360,
                videoTimescale = 90000,
                audioConfig = null,
            )
        assertThrows(IllegalStateException::class.java) {
            writer.writeInitSegment(ByteArrayOutputStream())
        }
    }

    @Test
    fun `init segment with audio has two trak boxes`() {
        val writer =
            Mp4SegmentWriter(
                videoCodecConfig = SAMPLE_AVC_CSD,
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
            videoCodecConfig = SAMPLE_AVC_CSD,
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
                    data = fakeAnnexBSample(100),
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
                    data = fakeAnnexBSample(100),
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
        val sampleData = fakeAnnexBSample(256)
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
                    data = fakeAnnexBSample(50),
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
                    data = fakeAnnexBSample(100),
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
                videoCodecConfig = SAMPLE_AVC_CSD,
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
                    data = fakeAnnexBSample(100),
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

    @Test
    fun `media segment converts video Annex-B sample to length-prefixed in mdat`() {
        val writer = createVideoOnlyWriter()
        // Annex-B IDR slice: 4-byte start code + NAL header 0x65 + 3 bytes of payload.
        val annexBSample =
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65.toByte(), 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val samples =
            listOf(
                EncodedSample(
                    data = annexBSample,
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
        // 8 mdat header + 4 length prefix + 4 NAL bytes (0x65 0xAA 0xBB 0xCC)
        assertEquals(8 + 4 + 4, mdatSize)
        // Length prefix is the 4-byte big-endian NAL length (4 bytes).
        assertEquals(0x00, bytes[moofSize + 8].toInt() and 0xFF)
        assertEquals(0x00, bytes[moofSize + 9].toInt() and 0xFF)
        assertEquals(0x00, bytes[moofSize + 10].toInt() and 0xFF)
        assertEquals(0x04, bytes[moofSize + 11].toInt() and 0xFF)
        assertEquals(0x65, bytes[moofSize + 12].toInt() and 0xFF)
        assertEquals(0xAA, bytes[moofSize + 13].toInt() and 0xFF)
        assertEquals(0xBB, bytes[moofSize + 14].toInt() and 0xFF)
        assertEquals(0xCC, bytes[moofSize + 15].toInt() and 0xFF)
    }

    @Test
    fun `init segment then media segments concatenated to one stream produce ftyp moov moof mdat sequence`() {
        // Single-file mode writes init + every media segment to the same OutputStream. The
        // resulting bytes must parse as a contiguous sequence of top-level boxes whose sizes
        // exactly cover the buffer end-to-end.
        val writer = createVideoOnlyWriter()
        val out = ByteArrayOutputStream()
        writer.writeInitSegment(out)
        val initEnd = out.size()

        val firstSamples =
            listOf(
                EncodedSample(
                    data = fakeAnnexBSample(120),
                    presentationTimeUs = 0L,
                    durationUs = 33333L,
                    flags = 1,
                ),
            )
        writer.writeMediaSegment(
            videoSamples = firstSamples,
            audioSamples = emptyList(),
            sequenceNumber = 1,
            baseDecodeTimeUs = 0L,
            output = out,
        )
        val firstSegmentEnd = out.size()

        val secondSamples =
            listOf(
                EncodedSample(
                    data = fakeAnnexBSample(80),
                    presentationTimeUs = 33333L,
                    durationUs = 33333L,
                    flags = 1,
                ),
            )
        writer.writeMediaSegment(
            videoSamples = secondSamples,
            audioSamples = emptyList(),
            sequenceNumber = 2,
            baseDecodeTimeUs = 33333L,
            output = out,
        )

        val bytes = out.toByteArray()
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)

        // Box 1: ftyp at 0
        val (ftypSize, ftypType) = readBoxHeader(bb)
        assertEquals("ftyp", ftypType)

        // Box 2: moov immediately after ftyp
        bb.position(ftypSize)
        val (moovSize, moovType) = readBoxHeader(bb)
        assertEquals("moov", moovType)
        assertEquals("init total = ftyp + moov", ftypSize + moovSize, initEnd)

        // Box 3: first moof immediately after init
        bb.position(initEnd)
        val (firstMoofSize, firstMoofType) = readBoxHeader(bb)
        assertEquals("moof", firstMoofType)

        // Box 4: first mdat immediately after first moof
        bb.position(initEnd + firstMoofSize)
        val (firstMdatSize, firstMdatType) = readBoxHeader(bb)
        assertEquals("mdat", firstMdatType)
        val firstFragmentSize = firstMoofSize + firstMdatSize
        assertEquals(
            "first fragment must end exactly at the end of the first writeMediaSegment call",
            initEnd + firstFragmentSize,
            firstSegmentEnd,
        )

        // Box 5: second moof immediately after first mdat
        bb.position(firstSegmentEnd)
        val (secondMoofSize, secondMoofType) = readBoxHeader(bb)
        assertEquals("moof", secondMoofType)

        // Box 6: second mdat immediately after second moof
        bb.position(firstSegmentEnd + secondMoofSize)
        val (secondMdatSize, secondMdatType) = readBoxHeader(bb)
        assertEquals("mdat", secondMdatType)

        // Sum of all top-level boxes must equal the buffer size — no trailing bytes, no gaps.
        val total = ftypSize + moovSize + firstMoofSize + firstMdatSize + secondMoofSize + secondMdatSize
        assertEquals("top-level box sizes must cover the whole stream", total, bytes.size)
    }

    @Test
    fun `media segment trun video sample size matches converted length-prefixed bytes`() {
        val writer = createVideoOnlyWriter()
        // 3-byte start code → 4-byte length. Original 6 bytes → converted 7 bytes.
        val annexBSample =
            byteArrayOf(0x00, 0x00, 0x01, 0x65.toByte(), 0xAA.toByte(), 0xBB.toByte())
        val samples =
            listOf(
                EncodedSample(
                    data = annexBSample,
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
        // trun lives at offset 68 (see existing test's layout comment).
        bb.position(68)
        val (_, trunType) = readBoxHeader(bb)
        assertEquals("trun", trunType)
        bb.getInt() // version + flags
        bb.getInt() // sample count
        bb.getInt() // data offset
        bb.getInt() // sample duration
        val sampleSize = bb.getInt()
        // Original 6-byte 3-byte-start-code Annex-B → 4-byte length + 3-byte payload = 7 bytes.
        assertEquals(7, sampleSize)
    }
}
