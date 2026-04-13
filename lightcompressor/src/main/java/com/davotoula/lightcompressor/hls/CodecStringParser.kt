package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.VideoCodec
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

private const val NAL_UNIT_TYPE_MASK = 0x1F
private const val NAL_UNIT_TYPE_SPS = 7
private const val NAL_UNIT_TYPE_PPS = 8
private const val SPS_PROFILE_OFFSET = 1
private const val SPS_CONSTRAINTS_OFFSET = 2
private const val SPS_LEVEL_OFFSET = 3
private const val BYTE_MASK = 0xFF
private const val START_CODE_LENGTH_3 = 3
private const val START_CODE_LENGTH_4 = 4

// AVCDecoderConfigurationRecord constants per ISO/IEC 14496-15 §5.2.4.1.
private const val AVC_CONFIG_VERSION = 0x01

// lengthSizeMinusOne = 3 → 4-byte NAL length prefix (6 reserved bits + 2 bits).
private const val AVC_LENGTH_SIZE_FLAGS_BYTE = 0xFF

// 3 reserved bits set (0xE0) OR'd with numOfSequenceParameterSets (5 bits).
private const val AVC_NUM_SPS_FLAGS_BYTE = 0xE0

// chroma_format_idc = 1 (4:2:0) with 6 reserved bits set.
private const val AVC_CHROMA_FORMAT_420 = 0xFD

// bit_depth_luma_minus8 = 0 / bit_depth_chroma_minus8 = 0 with 5 reserved bits set.
private const val AVC_BIT_DEPTH_8 = 0xF8

// H.264 profile_idc values that require the decoder-config extension fields.
private const val H264_PROFILE_HIGH = 100
private const val H264_PROFILE_HIGH_10 = 110
private const val H264_PROFILE_HIGH_422 = 122
private const val H264_PROFILE_HIGH_444 = 244
private val PROFILES_WITH_EXTENSION =
    setOf(H264_PROFILE_HIGH, H264_PROFILE_HIGH_10, H264_PROFILE_HIGH_422, H264_PROFILE_HIGH_444)

private const val UINT16_SHIFT = 8
private const val UINT24_SHIFT = 16
private const val UINT32_SHIFT = 24

// HEVC NAL unit type extraction: HEVC has a 2-byte NAL header where the first byte
// is `forbidden_zero_bit(1) | nal_unit_type(6) | nuh_layer_id(1 msb)`; masking with 0x7E
// and shifting right by 1 yields the 6-bit nal_unit_type.
private const val HEVC_NAL_UNIT_TYPE_MASK = 0x7E
private const val HEVC_NAL_UNIT_TYPE_SHIFT = 1
private const val HEVC_NAL_UNIT_TYPE_VPS = 32
private const val HEVC_NAL_UNIT_TYPE_SPS = 33
private const val HEVC_NAL_UNIT_TYPE_PPS = 34

// HEVCDecoderConfigurationRecord constants per ISO/IEC 14496-15 §8.3.3.1.
private const val HEVC_CONFIG_VERSION = 0x01
private const val HEVC_LENGTH_SIZE_MINUS_ONE = 3
private const val HEVC_NAL_HEADER_BYTES = 2

// profile_tier_level() top block: profile_space(2)+tier(1)+profile_idc(5) +
// profile_compatibility_flags(32) + constraint_indicator_flags(48) + level_idc(8) = 12 bytes.
private const val HEVC_PTL_BYTES = 12
private const val HEVC_SPS_RBSP_BYTES_NEEDED = HEVC_PTL_BYTES + 1

// Bit positions/masks for the first SPS RBSP byte:
//   vps_id(4) | max_sub_layers_minus1(3) | temporal_id_nesting_flag(1)
private const val SPS_MAX_SUB_LAYERS_SHIFT = 1
private const val SPS_MAX_SUB_LAYERS_MASK = 0x07

// Fixed reserved-bit patterns in the HEVCDecoderConfigurationRecord layout.
private const val HEVC_RESERVED_MIN_SPATIAL_HI = 0xF0
private const val HEVC_RESERVED_PARALLELISM_MIXED = 0xFC
private const val HEVC_RESERVED_CHROMA_420 = 0xFD
private const val HEVC_RESERVED_BIT_DEPTH_8 = 0xF8

// Shifts used to build the packed byte at record offset 21
// (constantFrameRate/numTemporalLayers/temporalIdNested/lengthSizeMinusOne).
private const val NUM_TEMPORAL_LAYERS_SHIFT = 3
private const val TEMPORAL_ID_NESTED_SHIFT = 2

// numOfArrays = 3 because we always emit exactly VPS, SPS, PPS entries.
private const val HEVC_NUM_OF_ARRAYS = 3

// Per-array leading byte: array_completeness(1=set) | reserved(1=0) | NAL_unit_type(6).
private const val HEVC_ARRAY_COMPLETENESS_BIT = 0x80
private const val HEVC_NAL_UNIT_TYPE_6BIT_MASK = 0x3F

// Emulation prevention: a 0x03 byte is inserted after two consecutive 0x00s to disambiguate
// the RBSP from Annex-B start codes. We strip it when copying from raw NAL bytes.
private const val EMULATION_PREVENTION_BYTE = 0x03
private const val EMULATION_PREVENTION_ZERO_RUN = 2

internal const val DEFAULT_AVC_CODEC = "avc1.640028"
internal const val DEFAULT_HEVC_CODEC = "hev1.1.6.L93.B0"

/**
 * Build an RFC 6381 `CODECS` attribute value from the `csd-0` bytes emitted by a MediaCodec
 * video encoder.
 *
 * For H.264, profile_idc, constraint_set_flags, and level_idc are parsed directly from the
 * SPS NAL unit inside the `csd-0` payload. This is necessary because Android's
 * `MediaFormat.KEY_PROFILE` and `KEY_LEVEL` expose MediaCodec's own `AVCProfile*`/`AVCLevel*`
 * constants, which are bit flags that do **not** match the H.264 bitstream bytes required by
 * HLS playlists (e.g. `AVCProfileHigh` is `0x08` but must be emitted as `0x64`). On top of that,
 * many encoders don't populate `KEY_LEVEL` at all, so the level defaults to 0 and Media3
 * rejects every variant as unplayable.
 */
internal fun buildAvcCodecString(csdBytes: ByteArray?): String {
    if (csdBytes == null || csdBytes.isEmpty()) return DEFAULT_AVC_CODEC
    return parseAvcCodecStringFromSps(csdBytes) ?: DEFAULT_AVC_CODEC
}

internal fun buildCodecString(
    csdBytes: ByteArray?,
    codec: VideoCodec,
): String =
    when (codec) {
        VideoCodec.H264 -> buildAvcCodecString(csdBytes)
        VideoCodec.H265 -> DEFAULT_HEVC_CODEC
    }

/**
 * Concatenate the encoder's `csd-0` and `csd-1` buffers into a single Annex-B byte array.
 *
 * Some MediaCodec AVC encoders (notably Pixel devices) emit the SPS in `csd-0` and the PPS
 * in `csd-1` rather than bundling both into `csd-0`. Each buffer is independently a valid
 * Annex-B stream, so simple byte concatenation produces a combined Annex-B stream that
 * [splitAvcCsdNalUnits] can decompose into both NAL units.
 */
internal fun mergeCsdBuffers(
    csd0: ByteBuffer?,
    csd1: ByteBuffer?,
): ByteArray {
    val bytes0 = csd0?.toByteArray() ?: ByteArray(0)
    val bytes1 = csd1?.toByteArray() ?: ByteArray(0)
    return bytes0 + bytes1
}

private fun ByteBuffer.toByteArray(): ByteArray = ByteArray(remaining()).also { get(it) }

/**
 * Parse `avc1.PPCCLL` from the first SPS NAL unit found in [csd]. Returns `null` if no SPS
 * NAL can be located or the NAL is truncated.
 */
internal fun parseAvcCodecStringFromSps(csd: ByteArray): String? {
    val spsHeaderIndex = findSpsNalHeaderIndex(csd)
    val levelIndex = spsHeaderIndex?.plus(SPS_LEVEL_OFFSET)
    return if (spsHeaderIndex == null || levelIndex == null || levelIndex >= csd.size) {
        null
    } else {
        val profile = csd[spsHeaderIndex + SPS_PROFILE_OFFSET].toInt() and BYTE_MASK
        val constraints = csd[spsHeaderIndex + SPS_CONSTRAINTS_OFFSET].toInt() and BYTE_MASK
        val level = csd[levelIndex].toInt() and BYTE_MASK
        "avc1.%02X%02X%02X".format(profile, constraints, level)
    }
}

/**
 * Split an H.264 Annex-B byte stream (as emitted by MediaCodec's `csd-0`) into SPS and PPS
 * NAL unit payloads, with start codes stripped.
 */
internal data class AvcCsdNalUnits(
    val spsUnits: List<ByteArray>,
    val ppsUnits: List<ByteArray>,
)

internal fun splitAvcCsdNalUnits(csd: ByteArray): AvcCsdNalUnits {
    val sps = mutableListOf<ByteArray>()
    val pps = mutableListOf<ByteArray>()
    for (nalBytes in iterateNalUnits(csd)) {
        when (nalBytes[0].toInt() and NAL_UNIT_TYPE_MASK) {
            NAL_UNIT_TYPE_SPS -> sps.add(nalBytes)
            NAL_UNIT_TYPE_PPS -> pps.add(nalBytes)
            else -> Unit
        }
    }
    return AvcCsdNalUnits(sps, pps)
}

/**
 * Convert an H.264 Annex-B byte stream (as emitted by Android's MediaCodec video encoders)
 * into the length-prefixed format required inside an `mdat` box when the surrounding `avcC`
 * declares `lengthSizeMinusOne = 3`. Each NAL unit's start code (3 or 4 bytes) is replaced
 * with a 4-byte big-endian length of the NAL payload.
 *
 * Returns an empty array for empty input. Throws [IllegalArgumentException] if the input is
 * non-empty but contains no Annex-B start codes — that indicates a programming error upstream
 * since every MediaCodec H.264 frame begins with at least one start code.
 */
internal fun convertAnnexBToAvcLengthPrefixed(annexB: ByteArray): ByteArray {
    if (annexB.isEmpty()) return ByteArray(0)
    val nals = iterateNalUnits(annexB)
    require(nals.isNotEmpty()) { "Annex-B input has no NAL start codes" }
    val out = ByteArrayOutputStream()
    for (nal in nals) {
        out.write((nal.size ushr UINT32_SHIFT) and BYTE_MASK)
        out.write((nal.size ushr UINT24_SHIFT) and BYTE_MASK)
        out.write((nal.size ushr UINT16_SHIFT) and BYTE_MASK)
        out.write(nal.size and BYTE_MASK)
        out.write(nal)
    }
    return out.toByteArray()
}

private fun iterateNalUnits(csd: ByteArray): List<ByteArray> {
    val nals = mutableListOf<ByteArray>()
    var cursor = 0
    var done = false
    while (cursor < csd.size && !done) {
        val startCodeLength = startCodeLengthAt(csd, cursor)
        if (startCodeLength == 0) {
            cursor++
        } else {
            val nalStart = cursor + startCodeLength
            if (nalStart >= csd.size) {
                done = true
            } else {
                val nalEnd = nextNalStart(csd, nalStart + 1) ?: csd.size
                nals.add(csd.copyOfRange(nalStart, nalEnd))
                cursor = nalEnd
            }
        }
    }
    return nals
}

/**
 * Build an `AVCDecoderConfigurationRecord` (ISO/IEC 14496-15 §5.2.4.1) from an H.264 Annex-B
 * `csd-0` payload. This is the exact bytes that go inside an `avcC` box in the fMP4 init
 * segment.
 *
 * For High profile and above, the chroma_format / bit_depth extension fields are emitted
 * hardcoded to 4:2:0 / 8-bit, which matches the output of every Android MediaCodec AVC
 * encoder we ship with. If the encoder ever produces 10-bit or 4:2:2 output this will need
 * to parse the SPS's chroma_format_idc / bit_depth_*_minus8 with Exp-Golomb decoding.
 *
 * Returns `null` if [csdAnnexB] does not contain both an SPS and a PPS NAL unit, or if the
 * SPS is too short to parse profile/constraints/level bytes.
 */
internal fun buildAvcDecoderConfigurationRecord(csdAnnexB: ByteArray): ByteArray? {
    val nals = splitAvcCsdNalUnits(csdAnnexB)
    val sps = nals.spsUnits.firstOrNull()
    val hasValidInputs =
        sps != null && nals.ppsUnits.isNotEmpty() && sps.size > SPS_LEVEL_OFFSET
    return if (!hasValidInputs) {
        null
    } else {
        encodeAvcDecoderConfigurationRecord(sps, nals)
    }
}

private fun encodeAvcDecoderConfigurationRecord(
    sps: ByteArray,
    nals: AvcCsdNalUnits,
): ByteArray {
    val profileIdc = sps[SPS_PROFILE_OFFSET].toInt() and BYTE_MASK
    val constraintFlags = sps[SPS_CONSTRAINTS_OFFSET].toInt() and BYTE_MASK
    val levelIdc = sps[SPS_LEVEL_OFFSET].toInt() and BYTE_MASK

    val out = ByteArrayOutputStream()
    out.write(AVC_CONFIG_VERSION)
    out.write(profileIdc)
    out.write(constraintFlags)
    out.write(levelIdc)
    out.write(AVC_LENGTH_SIZE_FLAGS_BYTE)
    out.write(AVC_NUM_SPS_FLAGS_BYTE or nals.spsUnits.size)
    writeLengthPrefixedNals(out, nals.spsUnits)
    out.write(nals.ppsUnits.size)
    writeLengthPrefixedNals(out, nals.ppsUnits)
    if (profileIdc in PROFILES_WITH_EXTENSION) {
        out.write(AVC_CHROMA_FORMAT_420)
        out.write(AVC_BIT_DEPTH_8)
        out.write(AVC_BIT_DEPTH_8)
        out.write(0) // numOfSequenceParameterSetExt
    }
    return out.toByteArray()
}

private fun writeLengthPrefixedNals(
    out: ByteArrayOutputStream,
    nals: List<ByteArray>,
) {
    for (nal in nals) {
        out.write((nal.size ushr UINT16_SHIFT) and BYTE_MASK)
        out.write(nal.size and BYTE_MASK)
        out.write(nal)
    }
}

/**
 * Scan [csd] for Annex-B NAL start codes and return the index of the NAL header byte for the
 * first SPS (nal_unit_type 7). Handles both 4-byte (`00 00 00 01`) and 3-byte (`00 00 01`)
 * start codes.
 */
private fun findSpsNalHeaderIndex(csd: ByteArray): Int? {
    var i = 0
    var result: Int? = null
    while (i < csd.size && result == null) {
        val startCodeLength = startCodeLengthAt(csd, i)
        if (startCodeLength == 0) {
            i++
        } else {
            val nalHeaderIndex = i + startCodeLength
            if (nalHeaderIndex >= csd.size) break
            val nalUnitType = csd[nalHeaderIndex].toInt() and NAL_UNIT_TYPE_MASK
            if (nalUnitType == NAL_UNIT_TYPE_SPS) {
                result = nalHeaderIndex
            } else {
                i = nalHeaderIndex + 1
            }
        }
    }
    return result
}

private fun nextNalStart(
    csd: ByteArray,
    from: Int,
): Int? {
    var i = from
    while (i < csd.size) {
        if (startCodeLengthAt(csd, i) > 0) return i
        i++
    }
    return null
}

private fun startCodeLengthAt(
    csd: ByteArray,
    offset: Int,
): Int {
    val fourByte =
        offset + START_CODE_LENGTH_4 - 1 < csd.size &&
            csd[offset] == 0.toByte() &&
            csd[offset + 1] == 0.toByte() &&
            csd[offset + 2] == 0.toByte() &&
            csd[offset + START_CODE_LENGTH_4 - 1] == 1.toByte()
    val threeByte =
        !fourByte &&
            offset + START_CODE_LENGTH_3 - 1 < csd.size &&
            csd[offset] == 0.toByte() &&
            csd[offset + 1] == 0.toByte() &&
            csd[offset + START_CODE_LENGTH_3 - 1] == 1.toByte()
    return when {
        fourByte -> START_CODE_LENGTH_4
        threeByte -> START_CODE_LENGTH_3
        else -> 0
    }
}

/**
 * Split an H.265/HEVC Annex-B byte stream into VPS, SPS, and PPS NAL unit payloads with
 * start codes stripped. Each NAL includes its 2-byte HEVC header so downstream code can
 * re-inspect the nal_unit_type.
 */
internal data class HevcCsdNalUnits(
    val vpsUnits: List<ByteArray>,
    val spsUnits: List<ByteArray>,
    val ppsUnits: List<ByteArray>,
)

internal fun splitHevcCsdNalUnits(csd: ByteArray): HevcCsdNalUnits {
    val vps = mutableListOf<ByteArray>()
    val sps = mutableListOf<ByteArray>()
    val pps = mutableListOf<ByteArray>()
    for (nalBytes in iterateNalUnits(csd)) {
        if (nalBytes.isEmpty()) continue
        val nalType = (nalBytes[0].toInt() and HEVC_NAL_UNIT_TYPE_MASK) ushr HEVC_NAL_UNIT_TYPE_SHIFT
        when (nalType) {
            HEVC_NAL_UNIT_TYPE_VPS -> vps.add(nalBytes)
            HEVC_NAL_UNIT_TYPE_SPS -> sps.add(nalBytes)
            HEVC_NAL_UNIT_TYPE_PPS -> pps.add(nalBytes)
            else -> Unit
        }
    }
    return HevcCsdNalUnits(vps, sps, pps)
}

/**
 * Build an `HEVCDecoderConfigurationRecord` (ISO/IEC 14496-15 §8.3.3.1) from an H.265
 * Annex-B `csd-0` payload. These are the bytes that go inside an `hvcC` box in the fMP4
 * init segment.
 *
 * The 13 bytes of profile_tier_level (1 prefix byte + 12 PTL bytes) are copied from the
 * first SPS NAL unit's RBSP after stripping emulation prevention bytes. chroma_format_idc,
 * bit_depth_*_minus8, and parallelismType are emitted hardcoded to the defaults produced
 * by Android MediaCodec HEVC encoders (4:2:0, 8-bit, mixed parallelism); if the encoder
 * ever produces 10-bit or 4:2:2 output this will need to parse the SPS with Exp-Golomb.
 *
 * Returns `null` if [csdAnnexB] does not contain all of VPS, SPS, PPS, or if the SPS is
 * too short to hold the profile_tier_level block.
 */
internal fun buildHevcDecoderConfigurationRecord(csdAnnexB: ByteArray): ByteArray? {
    val nals = splitHevcCsdNalUnits(csdAnnexB)
    val hasAllNals =
        nals.vpsUnits.isNotEmpty() && nals.spsUnits.isNotEmpty() && nals.ppsUnits.isNotEmpty()
    val spsRbsp =
        if (hasAllNals) {
            readRbspBytes(
                nal = nals.spsUnits.first(),
                offset = HEVC_NAL_HEADER_BYTES,
                length = HEVC_SPS_RBSP_BYTES_NEEDED,
            )
        } else {
            null
        }
    return spsRbsp?.let { encodeHevcDecoderConfigurationRecord(nals, it) }
}

private fun encodeHevcDecoderConfigurationRecord(
    nals: HevcCsdNalUnits,
    spsRbsp: ByteArray,
): ByteArray {
    // First SPS RBSP byte: sps_video_parameter_set_id(4) | sps_max_sub_layers_minus1(3) |
    // sps_temporal_id_nesting_flag(1). The latter two feed the record's packed byte 21.
    val spsFirstByte = spsRbsp[0].toInt() and BYTE_MASK
    val maxSubLayersMinus1 = (spsFirstByte ushr SPS_MAX_SUB_LAYERS_SHIFT) and SPS_MAX_SUB_LAYERS_MASK
    val temporalIdNested = spsFirstByte and 0x01

    val out = ByteArrayOutputStream()
    out.write(HEVC_CONFIG_VERSION)
    // Bytes 1-12: profile_tier_level top block, copied verbatim from SPS RBSP bytes 1..12.
    out.write(spsRbsp, 1, HEVC_PTL_BYTES)
    // Byte 13: reserved(4='1111') | min_spatial_segmentation_idc hi(4)
    out.write(HEVC_RESERVED_MIN_SPATIAL_HI)
    // Byte 14: min_spatial_segmentation_idc lo(8) = 0
    out.write(0)
    // Byte 15: reserved(6='111111') | parallelismType(2) = 0 (mixed)
    out.write(HEVC_RESERVED_PARALLELISM_MIXED)
    // Byte 16: reserved(6='111111') | chroma_format_idc(2) = 1 (4:2:0)
    out.write(HEVC_RESERVED_CHROMA_420)
    // Byte 17: reserved(5='11111') | bit_depth_luma_minus8(3) = 0 (8-bit)
    out.write(HEVC_RESERVED_BIT_DEPTH_8)
    // Byte 18: reserved(5='11111') | bit_depth_chroma_minus8(3) = 0 (8-bit)
    out.write(HEVC_RESERVED_BIT_DEPTH_8)
    // Bytes 19-20: avgFrameRate(16) = 0 (unknown)
    out.write(0)
    out.write(0)
    // Byte 21: constantFrameRate(2)=0 | numTemporalLayers(3) | temporalIdNested(1) |
    //          lengthSizeMinusOne(2)
    val numTemporalLayers = maxSubLayersMinus1 + 1
    val packedByte =
        (numTemporalLayers shl NUM_TEMPORAL_LAYERS_SHIFT) or
            (temporalIdNested shl TEMPORAL_ID_NESTED_SHIFT) or
            HEVC_LENGTH_SIZE_MINUS_ONE
    out.write(packedByte)
    // Byte 22: numOfArrays (VPS, SPS, PPS)
    out.write(HEVC_NUM_OF_ARRAYS)
    writeHevcNalArray(out, HEVC_NAL_UNIT_TYPE_VPS, nals.vpsUnits)
    writeHevcNalArray(out, HEVC_NAL_UNIT_TYPE_SPS, nals.spsUnits)
    writeHevcNalArray(out, HEVC_NAL_UNIT_TYPE_PPS, nals.ppsUnits)
    return out.toByteArray()
}

private fun writeHevcNalArray(
    out: ByteArrayOutputStream,
    nalUnitType: Int,
    nals: List<ByteArray>,
) {
    // array_completeness(1)=1 | reserved(1)=0 | NAL_unit_type(6)
    out.write(HEVC_ARRAY_COMPLETENESS_BIT or (nalUnitType and HEVC_NAL_UNIT_TYPE_6BIT_MASK))
    out.write((nals.size ushr UINT16_SHIFT) and BYTE_MASK)
    out.write(nals.size and BYTE_MASK)
    writeLengthPrefixedNals(out, nals)
}

/**
 * Copy up to [length] RBSP bytes from [nal] starting at raw offset [offset], stripping
 * emulation prevention bytes (0x03 following two consecutive 0x00s). Returns `null` if
 * the NAL doesn't contain enough post-strip bytes to satisfy the request.
 */
private fun readRbspBytes(
    nal: ByteArray,
    offset: Int,
    length: Int,
): ByteArray? {
    val out = ByteArray(length)
    var produced = 0
    var i = offset
    var zeros = 0
    while (i < nal.size && produced < length) {
        val b = nal[i].toInt() and BYTE_MASK
        if (zeros >= EMULATION_PREVENTION_ZERO_RUN && b == EMULATION_PREVENTION_BYTE) {
            zeros = 0
        } else {
            out[produced++] = b.toByte()
            zeros = if (b == 0) zeros + 1 else 0
        }
        i++
    }
    return if (produced == length) out else null
}
