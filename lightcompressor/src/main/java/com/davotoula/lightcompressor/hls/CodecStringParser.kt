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
