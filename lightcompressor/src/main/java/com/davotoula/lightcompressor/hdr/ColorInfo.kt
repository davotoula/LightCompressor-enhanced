package com.davotoula.lightcompressor.hdr

import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import com.davotoula.lightcompressor.utils.CompressorUtils

/**
 * Colour description of an input video track, read from its [MediaFormat].
 * All fields are null below API 24, where the colour keys are unavailable.
 */
internal class ColorInfo(
    val standard: Int?,
    val transfer: Int?,
    val range: Int?,
    val staticInfo: ByteArray?,
) {
    val isHlg: Boolean get() = transfer == MediaFormat.COLOR_TRANSFER_HLG
    val isPq: Boolean get() = transfer == MediaFormat.COLOR_TRANSFER_ST2084
    val isHdr: Boolean get() = isHlg || isPq

    /** Max content light level (uint16 LE at offset 21), or null if unavailable or zero. */
    val maxContentLightLevel: Int? get() = readStaticUint16(CLL_OFFSET)

    /** Max mastering display luminance (uint16 LE at offset 17), or null if unavailable or zero. */
    val maxMasteringLuminance: Int? get() = readStaticUint16(MASTERING_OFFSET)

    private fun readStaticUint16(offset: Int): Int? {
        val info = staticInfo?.takeIf { it.size == STATIC_INFO_SIZE && it[0].toInt() == 0 }
        val value =
            info?.let {
                (it[offset].toInt() and BYTE_MASK) or
                    ((it[offset + 1].toInt() and BYTE_MASK) shl BYTE_BITS)
            }
        return value?.takeIf { it != 0 }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColorInfo) return false
        return standard == other.standard &&
            transfer == other.transfer &&
            range == other.range &&
            (staticInfo contentEquals other.staticInfo)
    }

    override fun hashCode(): Int {
        var result = standard ?: 0
        result = 31 * result + (transfer ?: 0)
        result = 31 * result + (range ?: 0)
        result = 31 * result + (staticInfo?.contentHashCode() ?: 0)
        return result
    }

    override fun toString(): String =
        "ColorInfo(standard=$standard, transfer=$transfer, range=$range, staticInfo=${staticInfo?.size ?: 0} bytes)"

    companion object {
        private const val BYTE_MASK = 0xFF
        private const val BYTE_BITS = 8
        private const val STATIC_INFO_SIZE = 25
        private const val MASTERING_OFFSET = 17
        private const val CLL_OFFSET = 21
        private const val MIN_COLOR_API = 24

        /**
         * Reads track [trackIndex] of [extractor], filling any colour key it lacks from the file's Dolby Vision
         * track. The HEVC base layer chosen by [CompressorUtils.findVideoTrack] describes the same pictures, but
         * if it came back untagged an HLG/PQ source would plan as SDR and get tagged BT.709 without tone mapping.
         */
        fun forVideoTrack(
            extractor: MediaExtractor,
            trackIndex: Int,
            sdkInt: Int = Build.VERSION.SDK_INT,
        ): ColorInfo {
            val own = from(extractor.getTrackFormat(trackIndex), sdkInt)
            val dolbyVisionIndex =
                (0 until extractor.trackCount).firstOrNull {
                    it != trackIndex &&
                        CompressorUtils.trackMime(extractor, it) == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION
                } ?: return own
            val dv = from(extractor.getTrackFormat(dolbyVisionIndex), sdkInt)
            return ColorInfo(
                own.standard ?: dv.standard,
                own.transfer ?: dv.transfer,
                own.range ?: dv.range,
                own.staticInfo ?: dv.staticInfo,
            )
        }

        fun from(
            format: MediaFormat,
            sdkInt: Int = Build.VERSION.SDK_INT,
        ): ColorInfo {
            if (sdkInt < MIN_COLOR_API) return ColorInfo(null, null, null, null)

            fun int(key: String): Int? = if (format.containsKey(key)) format.getInteger(key) else null
            val staticInfo =
                if (format.containsKey(MediaFormat.KEY_HDR_STATIC_INFO)) {
                    format.getByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO)?.duplicate()?.let { buf ->
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                } else {
                    null
                }
            return ColorInfo(
                int(MediaFormat.KEY_COLOR_STANDARD),
                int(MediaFormat.KEY_COLOR_TRANSFER),
                int(MediaFormat.KEY_COLOR_RANGE),
                staticInfo,
            )
        }
    }
}
