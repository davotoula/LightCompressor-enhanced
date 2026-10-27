package com.davotoula.lightcompressor.hdr

import android.media.MediaFormat
import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.config.HdrMode

internal data class HdrDecision(
    val plan: ColorPlan,
    val reason: String,
)

/** Decides once per job whether to keep HDR, tone map to SDR, or transcode plain SDR. Does not log. */
internal object HdrPolicy {
    private const val HLG_PEAK_NITS = 1000.0

    @Suppress("ReturnCount")
    fun decide(
        info: ColorInfo,
        codec: VideoCodec,
        mode: HdrMode,
        isHls: Boolean,
        caps: HdrCapabilities,
    ): HdrDecision {
        if (!info.isHdr) {
            val tags = "transfer=${info.transfer}, standard=${info.standard}"
            return HdrDecision(ColorPlan.Sdr(info), "SDR or untagged input ($tags) → Sdr")
        }
        val kind = if (info.isHlg) "HLG" else "PQ"
        val prefix = "$kind input, $codec, $mode"
        val toneMap = fallbackFor(info)
        if (mode == HdrMode.TONE_MAP_TO_SDR) {
            return HdrDecision(toneMap, "$prefix → ToneMapToSdr")
        }
        // H.264 can't keep HDR: Android hardware encoders don't encode 10-bit H.264 (High 10), and
        // few hardware decoders could play it.
        if (codec != VideoCodec.H265) {
            return HdrDecision(toneMap, "$prefix, codec is not H265 → ToneMapToSdr")
        }
        if (isHls) return HdrDecision(toneMap, "$prefix, HLS → ToneMapToSdr")
        val standard = info.standard
        if (standard != null && standard != MediaFormat.COLOR_STANDARD_BT2020) {
            return HdrDecision(toneMap, "$prefix, colour standard $standard is not BT.2020 → ToneMapToSdr")
        }
        val encoder = if (info.isHlg) caps.hlgEncoder(codec.mimeType) else caps.pqEncoder(codec.mimeType)
        if (encoder == null) {
            val features = if (info.isHlg) "HlgEditing/HdrEditing" else "HdrEditing"
            return HdrDecision(toneMap, "$prefix, no encoder with $features → ToneMapToSdr")
        }
        val plan =
            ColorPlan.KeepHdr(
                transfer = requireNotNull(info.transfer),
                profile = encoder.profile,
                encoderName = encoder.name,
                staticInfo = if (info.isPq) info.staticInfo else null,
                sourceRange = info.range,
            )
        return HdrDecision(plan, "$prefix, encoder ${encoder.name} → KeepHdr")
    }

    fun fallbackFor(info: ColorInfo): ColorPlan.ToneMapToSdr {
        require(info.isHdr) { "fallbackFor requires HDR input" }
        val peak =
            if (info.isHlg) {
                HLG_PEAK_NITS
            } else {
                (info.maxContentLightLevel ?: info.maxMasteringLuminance)?.toDouble()
            }
        return ColorPlan.ToneMapToSdr(
            sourceTransfer = requireNotNull(info.transfer),
            sourceRange = info.range,
            lw = ToneMapMath.lwFor(peak).toFloat(),
        )
    }
}
