package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.VideoCodec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

@Suppress("LargeClass")
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

    @Test
    fun `splitHevcCsdNalUnits separates VPS SPS PPS from concatenated Annex-B`() {
        val csd = buildHevcSyntheticCsd()

        val result = splitHevcCsdNalUnits(csd)

        assertEquals(1, result.vpsUnits.size)
        assertEquals(1, result.spsUnits.size)
        assertEquals(1, result.ppsUnits.size)
        // VPS NAL header is 2 bytes
        assertEquals(2, result.vpsUnits[0].size)
        // SPS NAL: 2-byte header + 13-byte RBSP
        assertEquals(HEVC_SPS_NAL_SIZE, result.spsUnits[0].size)
        assertEquals(2, result.ppsUnits[0].size)
    }

    @Test
    fun `buildHevcDecoderConfigurationRecord emits valid record for Main profile SPS`() {
        val record = buildHevcDecoderConfigurationRecord(buildHevcSyntheticCsd())!!

        // Layout per ISO/IEC 14496-15 §8.3.3.1:
        //   bytes 0..22   fixed HEVCDecoderConfigurationRecord header
        //   bytes 23..29  VPS array (header 3B + NAL length 2B + 2-byte VPS NAL)
        //   bytes 30..49  SPS array (header 3B + NAL length 2B + 15-byte SPS NAL)
        //   bytes 50..56  PPS array (header 3B + NAL length 2B + 2-byte PPS NAL)
        // The packed byte at offset 21 = constantFrameRate(2)=0 | numTemporalLayers(3)=1 |
        //                                temporalIdNested(1)=1 | lengthSizeMinusOne(2)=3 → 0x0F.
        val expected =
            byteArrayOf(
                0x01,
                0x01,
                0x60.toByte(),
                0x00,
                0x00,
                0x00,
                0x90.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x5D,
                0xF0.toByte(),
                0x00,
                0xFC.toByte(),
                0xFD.toByte(),
                0xF8.toByte(),
                0xF8.toByte(),
                0x00,
                0x00,
                0x0F,
                0x03,
                // VPS array: completeness|type=32 → 0xA0, numNalus=1, nalLen=2, NAL 0x40 0x01
                0xA0.toByte(),
                0x00,
                0x01,
                0x00,
                0x02,
                0x40,
                0x01,
                // SPS array: completeness|type=33 → 0xA1, numNalus=1, nalLen=15, then 15-byte SPS
                0xA1.toByte(),
                0x00,
                0x01,
                0x00,
                0x0F,
                0x42,
                0x01,
                0x01,
                0x01,
                0x60.toByte(),
                0x00,
                0x00,
                0x00,
                0x90.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x5D,
                // PPS array: completeness|type=34 → 0xA2, numNalus=1, nalLen=2, NAL 0x44 0x01
                0xA2.toByte(),
                0x00,
                0x01,
                0x00,
                0x02,
                0x44,
                0x01,
            )
        assertArrayEquals(expected, record)
        assertEquals(HEVC_EXPECTED_RECORD_SIZE, record.size)
    }

    @Test
    fun `buildHevcDecoderConfigurationRecord returns null when VPS missing`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x42.toByte(),
                0x01,
                0x01,
                0x01,
                0x60.toByte(),
                0x00,
                0x00,
                0x00,
                0x90.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x5D,
                0x00,
                0x00,
                0x00,
                0x01,
                0x44.toByte(),
                0x01,
            )

        assertEquals(null, buildHevcDecoderConfigurationRecord(csd))
    }

    @Test
    fun `buildHevcDecoderConfigurationRecord returns null when SPS missing`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x40.toByte(),
                0x01,
                0x00,
                0x00,
                0x00,
                0x01,
                0x44.toByte(),
                0x01,
            )

        assertEquals(null, buildHevcDecoderConfigurationRecord(csd))
    }

    @Test
    fun `buildHevcDecoderConfigurationRecord returns null when PPS missing`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x40.toByte(),
                0x01,
                0x00,
                0x00,
                0x00,
                0x01,
                0x42.toByte(),
                0x01,
                0x01,
                0x01,
                0x60.toByte(),
                0x00,
                0x00,
                0x00,
                0x90.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00,
                0x5D,
            )

        assertEquals(null, buildHevcDecoderConfigurationRecord(csd))
    }

    @Test
    fun `buildHevcDecoderConfigurationRecord returns null when SPS too short for PTL`() {
        val csd =
            byteArrayOf(
                0x00,
                0x00,
                0x00,
                0x01,
                0x40.toByte(),
                0x01,
                // SPS with only 4 RBSP bytes — not enough for profile_tier_level
                0x00,
                0x00,
                0x00,
                0x01,
                0x42.toByte(),
                0x01,
                0x01,
                0x01,
                0x60.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x01,
                0x44.toByte(),
                0x01,
            )

        assertEquals(null, buildHevcDecoderConfigurationRecord(csd))
    }

    @Test
    fun `buildHevcDecoderConfigurationRecord strips emulation prevention bytes from SPS RBSP`() {
        // SPS with profile_compatibility_flags = 0x00000001 — the byte sequence
        // 0x00 0x00 0x01 in the stream would be ambiguous with a start code, so the
        // encoder inserts an emulation prevention byte (0x03) after the two zeros.
        // The builder must strip it when copying profile_tier_level bytes.
        //
        // Target PTL layout:
        //   profile_space/tier/profile_idc = 0x01
        //   compat flags = 0x00 0x00 0x00 0x01  (← EP byte precedes the 0x01)
        //   constraint flags = 0x90 0x00 0x00 0x00 0x00 0x00
        //   level_idc = 0x5D
        //
        // Raw RBSP bytes (with EP byte): 0x01 0x01 0x00 0x00 0x03 0x00 0x01 0x90 0x00 ...
        val csd =
            byteArrayOf(
                // VPS
                0x00,
                0x00,
                0x00,
                0x01,
                0x40.toByte(),
                0x01,
                // SPS header + RBSP (with emulation prevention byte at index 4)
                0x00,
                0x00,
                0x00,
                0x01,
                0x42.toByte(),
                0x01,
                0x01, // rbsp[0]: first SPS byte (vps_id=0, maxSub=0, nested=1)
                0x01, // rbsp[1]: profile_space=0, tier=0, profile_idc=1
                0x00,
                0x00,
                0x03,
                0x00,
                0x01, // rbsp[2..5]: compat flags 0x00000001 (EP stripped)
                0x90.toByte(),
                0x00,
                0x00,
                0x00,
                0x00,
                0x00, // rbsp[6..11]: constraint flags
                0x5D, // rbsp[12]: level_idc
                // PPS
                0x00,
                0x00,
                0x00,
                0x01,
                0x44.toByte(),
                0x01,
            )

        val record = buildHevcDecoderConfigurationRecord(csd)!!

        assertEquals(0x01, record[1].toInt() and 0xFF)
        assertEquals(0x00, record[2].toInt() and 0xFF)
        assertEquals(0x00, record[3].toInt() and 0xFF)
        assertEquals(0x00, record[4].toInt() and 0xFF)
        assertEquals(0x01, record[5].toInt() and 0xFF) // compat flag bit reassembled past EP
        assertEquals(0x90, record[6].toInt() and 0xFF)
        assertEquals(0x5D, record[12].toInt() and 0xFF)
    }

    private fun buildHevcSyntheticCsd(): ByteArray =
        byteArrayOf(
            // VPS NAL (type=32 → header byte 0x40); second byte = 0x01 (layer=0, temporal+1=1)
            0x00,
            0x00,
            0x00,
            0x01,
            0x40.toByte(),
            0x01,
            // SPS NAL (type=33 → header byte 0x42); second byte = 0x01
            0x00,
            0x00,
            0x00,
            0x01,
            0x42.toByte(),
            0x01,
            // SPS RBSP bytes 0..12:
            // byte 0: vps_id=0, max_sub_layers_minus1=0, temporal_id_nesting=1 → 0x01
            0x01,
            // byte 1: profile_space=0, tier=0, profile_idc=1 (Main) → 0x01
            0x01,
            // bytes 2..5: general_profile_compatibility_flags = 0x60000000 (Main compat bit)
            0x60.toByte(),
            0x00,
            0x00,
            0x00,
            // bytes 6..11: general_constraint_indicator_flags = 0x900000000000
            0x90.toByte(),
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            // byte 12: general_level_idc = 93 (Level 3.1)
            0x5D,
            // PPS NAL (type=34 → header byte 0x44)
            0x00,
            0x00,
            0x00,
            0x01,
            0x44.toByte(),
            0x01,
        )

    companion object {
        private const val HEVC_SPS_NAL_SIZE = 15
        private const val HEVC_EXPECTED_RECORD_SIZE = 57
    }
}
