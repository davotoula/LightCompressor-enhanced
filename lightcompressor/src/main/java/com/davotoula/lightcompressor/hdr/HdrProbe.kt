package com.davotoula.lightcompressor.hdr

import com.davotoula.lightcompressor.VideoCodec
import com.davotoula.lightcompressor.VideoCompressor
import com.davotoula.lightcompressor.config.HdrMode

/** Transfer function of a video's colour, as [HdrProbe] reports it. Later versions may add values. */
enum class HdrInput { SDR, HLG, PQ }

/**
 * What [VideoCompressor.probeHdr] found. [input] is [HdrInput.SDR] for SDR, untagged and unreadable
 * video. [canKeepHdr] is true when compressing with [VideoCodec.H265] and [HdrMode.KEEP_IF_SUPPORTED]
 * would keep the HDR on this device; it is always false for [HdrInput.SDR].
 */
data class HdrProbe(
    val input: HdrInput,
    val canKeepHdr: Boolean,
)

/** The probe answer for [info], from the same [HdrPolicy.decide] call compression makes for H.265. */
internal fun hdrProbeOf(
    info: ColorInfo,
    caps: HdrCapabilities,
): HdrProbe {
    val input =
        when {
            info.isHlg -> HdrInput.HLG
            info.isPq -> HdrInput.PQ
            else -> HdrInput.SDR
        }
    val decision = HdrPolicy.decide(info, VideoCodec.H265, HdrMode.KEEP_IF_SUPPORTED, isHls = false, caps = caps)
    return HdrProbe(input, decision.plan is ColorPlan.KeepHdr)
}
