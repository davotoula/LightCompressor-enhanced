package com.davotoula.lightcompressor.hdr

import android.media.MediaFormat
import android.os.Build
import android.util.Log
import java.nio.ByteBuffer

internal enum class RenderShader { Passthrough, YuvToRgb2020, ToneMapHlg, ToneMapPq }

internal data class RenderSpec(
    val gles3: Boolean,
    val tenBit: Boolean,
    val shader: RenderShader,
)

/**
 * The single place encoder colour tags (and, for keep-HDR, profile and static info) are set.
 * Output is never tagged BT.709 unless tone mapped, and never HLG/PQ unless 10-bit.
 */
internal sealed class ColorPlan {
    abstract val render: RenderSpec

    abstract fun applyTo(
        format: MediaFormat,
        sdkInt: Int = Build.VERSION.SDK_INT,
    )

    open fun matchesEncoderOutput(
        format: MediaFormat,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean = true

    /** Plain SDR transcode: copy the input's standard and transfer, default to BT.709, force limited range. */
    class Sdr(
        val info: ColorInfo,
    ) : ColorPlan() {
        override val render = RenderSpec(gles3 = false, tenBit = false, shader = RenderShader.Passthrough)

        override fun applyTo(
            format: MediaFormat,
            sdkInt: Int,
        ) {
            if (sdkInt < MIN_COLOR_API) return
            // Default to BT.709 if input doesn't specify - standard for SDR HD content.
            // Some devices don't populate color metadata from MediaExtractor, causing
            // the encoder to use device-specific defaults (e.g., sRGB instead of BT.709).
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, info.standard ?: MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, info.transfer ?: MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            val targetRange = MediaFormat.COLOR_RANGE_LIMITED
            val inputRange = info.range
            if (inputRange != null && inputRange != targetRange) {
                Log.w(LOG_TAG, "Overriding input color range $inputRange with limited range $targetRange")
            }
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, targetRange)
        }
    }

    /** Keep HDR: 10-bit BT.2020 with the source transfer, an HDR encoder profile and optional static info. */
    class KeepHdr(
        val transfer: Int,
        val profile: Int,
        val encoderName: String,
        val staticInfo: ByteArray?,
        val sourceRange: Int?,
    ) : ColorPlan() {
        override val render = RenderSpec(gles3 = true, tenBit = true, shader = RenderShader.YuvToRgb2020)

        override fun applyTo(
            format: MediaFormat,
            sdkInt: Int,
        ) {
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT2020)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, transfer)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            format.setInteger(MediaFormat.KEY_PROFILE, profile)
            if (staticInfo != null) {
                format.setByteBuffer(MediaFormat.KEY_HDR_STATIC_INFO, ByteBuffer.wrap(staticInfo))
            }
        }

        override fun matchesEncoderOutput(
            format: MediaFormat,
            sdkInt: Int,
        ): Boolean {
            if (sdkInt < MIN_COLOR_API) return true
            val profileOk =
                !format.containsKey(MediaFormat.KEY_PROFILE) ||
                    format.getInteger(MediaFormat.KEY_PROFILE) == profile
            val transferOk =
                !format.containsKey(MediaFormat.KEY_COLOR_TRANSFER) ||
                    format.getInteger(MediaFormat.KEY_COLOR_TRANSFER) == transfer
            return profileOk && transferOk
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeepHdr) return false
            return transfer == other.transfer &&
                profile == other.profile &&
                encoderName == other.encoderName &&
                sourceRange == other.sourceRange &&
                (staticInfo contentEquals other.staticInfo)
        }

        override fun hashCode(): Int {
            var result = transfer
            result = 31 * result + profile
            result = 31 * result + encoderName.hashCode()
            result = 31 * result + (sourceRange ?: 0)
            result = 31 * result + (staticInfo?.contentHashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "KeepHdr(transfer=$transfer, profile=$profile, encoder=$encoderName, " +
                "staticInfo=${staticInfo?.size ?: 0} bytes, sourceRange=$sourceRange)"
    }

    /** Tone map HDR source to 8-bit SDR, tagged BT.709. */
    class ToneMapToSdr(
        val sourceTransfer: Int,
        val sourceRange: Int?,
        val lw: Float,
    ) : ColorPlan() {
        override val render =
            RenderSpec(
                gles3 = true,
                tenBit = false,
                shader =
                    if (sourceTransfer == MediaFormat.COLOR_TRANSFER_HLG) {
                        RenderShader.ToneMapHlg
                    } else {
                        RenderShader.ToneMapPq
                    },
            )

        override fun applyTo(
            format: MediaFormat,
            sdkInt: Int,
        ) {
            if (sdkInt < MIN_COLOR_API) return
            format.setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
        }
    }

    internal companion object {
        const val MIN_COLOR_API = 24
        const val LOG_TAG = "Output file parameters"
    }
}
