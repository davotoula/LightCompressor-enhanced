package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class CodecStringParserTest {
    @Test
    fun `buildAvcCodecString parses profile constraints level from 4-byte start code SPS`() {
        // Start code (4 bytes) + NAL header 0x67 (SPS, nal_ref_idc=3, type=7) + profile=0x64
        // (High) + constraints=0x00 + level=0x28 (level 4.0) + trailing data.
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x64.toByte(),
                0x00,
                0x28,
                0x12,
                0x34,
            )

        assertEquals("avc1.640028", buildAvcCodecString(csd))
    }

    @Test
    fun `buildAvcCodecString parses profile constraints level from 3-byte start code SPS`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x42.toByte(),
                0xC0.toByte(),
                0x1F.toByte(),
            )

        assertEquals("avc1.42C01F", buildAvcCodecString(csd))
    }

    @Test
    fun `buildAvcCodecString skips non-SPS NAL units to find the SPS`() {
        // AUD NAL (type 9) first, then SPS, both with 4-byte start codes.
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x09.toByte(),
                0x10,
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x4D.toByte(),
                0x40.toByte(),
                0x1E.toByte(),
            )

        assertEquals("avc1.4D401E", buildAvcCodecString(csd))
    }

    @Test
    fun `buildAvcCodecString falls back when csd has no SPS NAL`() {
        // Only a PPS NAL (type 8).
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x68.toByte(),
                0xEB.toByte(),
                0xEF.toByte(),
                0x20,
            )

        assertEquals(DEFAULT_AVC_CODEC, buildAvcCodecString(csd))
    }

    @Test
    fun `buildAvcCodecString falls back on null input`() {
        assertEquals(DEFAULT_AVC_CODEC, buildAvcCodecString(null))
    }

    @Test
    fun `buildAvcCodecString falls back on empty input`() {
        assertEquals(DEFAULT_AVC_CODEC, buildAvcCodecString(ByteArray(0)))
    }

    @Test
    fun `buildAvcCodecString falls back when SPS NAL is truncated`() {
        // Start code + SPS NAL header but only one byte of SPS payload (needs three).
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x64.toByte(),
            )

        assertEquals(DEFAULT_AVC_CODEC, buildAvcCodecString(csd))
    }

    @Test
    fun `buildAvcCodecString preserves non-zero constraint flags`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x42.toByte(),
                0xE0.toByte(),
                0x1F.toByte(),
            )

        assertEquals("avc1.42E01F", buildAvcCodecString(csd))
    }

    @Test
    fun `buildCodecString returns HEVC default for H265`() {
        assertEquals(DEFAULT_HEVC_CODEC, buildCodecString(null, VideoCodec.H265))
        assertEquals(
            DEFAULT_HEVC_CODEC,
            buildCodecString(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x40), VideoCodec.H265),
        )
    }

    @Test
    fun `buildCodecString delegates to SPS parser for H264`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x64.toByte(),
                0x00,
                0x28,
            )

        assertEquals("avc1.640028", buildCodecString(csd, VideoCodec.H264))
    }

    @Test
    fun `splitAvcCsdNalUnits extracts SPS and PPS stripping start codes`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x64.toByte(),
                0x00,
                0x28,
                0xAA.toByte(),
                0xBB.toByte(),
                0x00,
                0x00,
                0x00,
                0x01,
                0x68.toByte(),
                0xEB.toByte(),
                0xEF.toByte(),
                0x20,
            )

        val result = splitAvcCsdNalUnits(csd)

        assertEquals(1, result.spsUnits.size)
        assertEquals(1, result.ppsUnits.size)
        assertArrayEquals(
            byteArrayOf(0x67.toByte(), 0x64.toByte(), 0x00, 0x28, 0xAA.toByte(), 0xBB.toByte()),
            result.spsUnits[0],
        )
        assertArrayEquals(
            byteArrayOf(0x68.toByte(), 0xEB.toByte(), 0xEF.toByte(), 0x20),
            result.ppsUnits[0],
        )
    }

    @Test
    fun `splitAvcCsdNalUnits handles 3-byte start codes`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x42.toByte(),
                0xC0.toByte(),
                0x1F.toByte(),
                0x00,
                0x00,
                0x01,
                0x68.toByte(),
                0xCE.toByte(),
            )

        val result = splitAvcCsdNalUnits(csd)

        assertEquals(1, result.spsUnits.size)
        assertEquals(1, result.ppsUnits.size)
        assertEquals(4, result.spsUnits[0].size)
        assertEquals(2, result.ppsUnits[0].size)
    }

    @Test
    fun `splitAvcCsdNalUnits ignores non-SPS non-PPS NAL units`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x09.toByte(),
                0x10,
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x42.toByte(),
                0xC0.toByte(),
                0x1F.toByte(),
                0x00,
                0x00,
                0x00,
                0x01,
                0x68.toByte(),
                0xCE.toByte(),
            )

        val result = splitAvcCsdNalUnits(csd)

        assertEquals(1, result.spsUnits.size)
        assertEquals(1, result.ppsUnits.size)
    }

    @Test
    fun `buildAvcDecoderConfigurationRecord emits valid record for High profile SPS`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x64.toByte(),
                0x00,
                0x28,
                0xAA.toByte(),
                0x00,
                0x00,
                0x00,
                0x01,
                0x68.toByte(),
                0xCE.toByte(),
                0x38.toByte(),
                0x80.toByte(),
            )

        val record = buildAvcDecoderConfigurationRecord(csd)!!

        assertEquals(0x01, record[0].toInt() and 0xFF)
        assertEquals(0x64, record[1].toInt() and 0xFF)
        assertEquals(0x00, record[2].toInt() and 0xFF)
        assertEquals(0x28, record[3].toInt() and 0xFF)
        assertEquals(0xFF, record[4].toInt() and 0xFF)
        assertEquals(0xE1, record[5].toInt() and 0xFF)
        assertEquals(0x00, record[6].toInt() and 0xFF)
        assertEquals(0x05, record[7].toInt() and 0xFF)
        assertEquals(0x67, record[8].toInt() and 0xFF)
        assertEquals(0x64, record[9].toInt() and 0xFF)
        assertEquals(0x00, record[10].toInt() and 0xFF)
        assertEquals(0x28, record[11].toInt() and 0xFF)
        assertEquals(0xAA, record[12].toInt() and 0xFF)
        assertEquals(0x01, record[13].toInt() and 0xFF)
        assertEquals(0x00, record[14].toInt() and 0xFF)
        assertEquals(0x04, record[15].toInt() and 0xFF)
        assertEquals(0x68, record[16].toInt() and 0xFF)
        assertEquals(0xCE, record[17].toInt() and 0xFF)
        assertEquals(0x38, record[18].toInt() and 0xFF)
        assertEquals(0x80, record[19].toInt() and 0xFF)
        assertEquals(0xFD, record[20].toInt() and 0xFF)
        assertEquals(0xF8, record[21].toInt() and 0xFF)
        assertEquals(0xF8, record[22].toInt() and 0xFF)
        assertEquals(0x00, record[23].toInt() and 0xFF)
        assertEquals(24, record.size)
    }

    @Test
    fun `buildAvcDecoderConfigurationRecord omits extension for Baseline profile`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x42.toByte(),
                0xC0.toByte(),
                0x1F.toByte(),
                0x00,
                0x00,
                0x00,
                0x01,
                0x68.toByte(),
                0xCE.toByte(),
            )

        val record = buildAvcDecoderConfigurationRecord(csd)!!

        // Header(6) + SPS[len(2) + 4] + numPps(1) + PPS[len(2) + 2] = 17, no extension.
        assertEquals(17, record.size)
        assertEquals(0x42, record[1].toInt() and 0xFF)
        assertEquals(0xC0, record[2].toInt() and 0xFF)
        assertEquals(0x1F, record[3].toInt() and 0xFF)
    }

    @Test
    fun `buildAvcDecoderConfigurationRecord returns null when SPS missing`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x68.toByte(),
                0xCE.toByte(),
            )

        assertEquals(null, buildAvcDecoderConfigurationRecord(csd))
    }

    @Test
    fun `buildAvcDecoderConfigurationRecord returns null when PPS missing`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x64.toByte(),
                0x00,
                0x28,
            )

        assertEquals(null, buildAvcDecoderConfigurationRecord(csd))
    }

    @Test
    fun `mergeCsdBuffers returns empty when both buffers are null`() {
        assertArrayEquals(ByteArray(0), mergeCsdBuffers(null, null))
    }

    @Test
    fun `mergeCsdBuffers returns csd0 bytes when csd1 is null`() {
        val csd0 = ByteBuffer.wrap(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67.toByte()))

        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67.toByte()),
            mergeCsdBuffers(csd0, null),
        )
    }

    @Test
    fun `mergeCsdBuffers returns csd1 bytes when csd0 is null`() {
        val csd1 = ByteBuffer.wrap(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68.toByte()))

        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68.toByte()),
            mergeCsdBuffers(null, csd1),
        )
    }

    @Test
    fun `mergeCsdBuffers concatenates csd0 followed by csd1`() {
        // Pixel encoders split SPS and PPS into separate csd buffers. Each is its own
        // Annex-B stream, so simple concatenation produces a valid combined Annex-B stream.
        val csd0 =
            ByteBuffer.wrap(
                byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67.toByte(), 0x64.toByte(), 0x00, 0x28),
            )
        val csd1 =
            ByteBuffer.wrap(byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68.toByte(), 0xCE.toByte()))

        val merged = mergeCsdBuffers(csd0, csd1)

        assertArrayEquals(
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x64.toByte(),
                0x00,
                0x28,
                0x00,
                0x00,
                0x00,
                0x01,
                0x68.toByte(),
                0xCE.toByte(),
            ),
            merged,
        )
    }

    @Test
    fun `mergeCsdBuffers result feeds into buildAvcDecoderConfigurationRecord successfully`() {
        // End-to-end check: a Pixel-style split csd (SPS in csd-0, PPS in csd-1) must
        // produce a valid AVCDecoderConfigurationRecord after merging.
        val csd0 =
            ByteBuffer.wrap(
                byteArrayOf(
                    0x00,
                    0x00,
                    0x00,
                    0x01,
                    0x67.toByte(),
                    0x64.toByte(),
                    0x00,
                    0x28,
                    0xAA.toByte(),
                ),
            )
        val csd1 =
            ByteBuffer.wrap(
                byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x68.toByte(), 0xCE.toByte(), 0x38.toByte()),
            )

        val record = buildAvcDecoderConfigurationRecord(mergeCsdBuffers(csd0, csd1))

        assertEquals(0x01, record!![0].toInt() and 0xFF)
        assertEquals(0x64, record[1].toInt() and 0xFF)
        assertEquals(0x00, record[2].toInt() and 0xFF)
        assertEquals(0x28, record[3].toInt() and 0xFF)
    }

    @Test
    fun `convertAnnexBToAvcLengthPrefixed converts single 4-byte start code NAL`() {
        val annexB = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65.toByte(), 0xAA.toByte(), 0xBB.toByte())

        val converted = convertAnnexBToAvcLengthPrefixed(annexB)

        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x03, 0x65.toByte(), 0xAA.toByte(), 0xBB.toByte()),
            converted,
        )
    }

    @Test
    fun `convertAnnexBToAvcLengthPrefixed converts single 3-byte start code NAL`() {
        val annexB = byteArrayOf(0x00, 0x00, 0x01, 0x65.toByte(), 0xAA.toByte(), 0xBB.toByte())

        val converted = convertAnnexBToAvcLengthPrefixed(annexB)

        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x03, 0x65.toByte(), 0xAA.toByte(), 0xBB.toByte()),
            converted,
        )
    }

    @Test
    fun `convertAnnexBToAvcLengthPrefixed concatenates multiple NAL units`() {
        // Two NAL units back to back: SEI (type 6) then IDR slice (type 5)
        val annexB =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x06,
                0x05,
                0x00,
                0x00,
                0x00,
                0x01,
                0x65.toByte(),
                0xAA.toByte(),
                0xBB.toByte(),
                0xCC.toByte(),
            )

        val converted = convertAnnexBToAvcLengthPrefixed(annexB)

        assertArrayEquals(
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x02,
                0x06,
                0x05,
                0x00,
                0x00,
                0x00,
                0x04,
                0x65.toByte(),
                0xAA.toByte(),
                0xBB.toByte(),
                0xCC.toByte(),
            ),
            converted,
        )
    }

    @Test
    fun `convertAnnexBToAvcLengthPrefixed returns empty for empty input`() {
        assertArrayEquals(ByteArray(0), convertAnnexBToAvcLengthPrefixed(ByteArray(0)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `convertAnnexBToAvcLengthPrefixed throws when input has bytes but no start codes`() {
        convertAnnexBToAvcLengthPrefixed(byteArrayOf(0x65.toByte(), 0xAA.toByte(), 0xBB.toByte()))
    }

    @Test
    fun `buildAvcDecoderConfigurationRecord returns null when SPS too short`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x67.toByte(),
                0x64.toByte(),
                0x00,
                0x00,
                0x00,
                0x01,
                0x68.toByte(),
                0xCE.toByte(),
            )

        assertEquals(null, buildAvcDecoderConfigurationRecord(csd))
    }
}
