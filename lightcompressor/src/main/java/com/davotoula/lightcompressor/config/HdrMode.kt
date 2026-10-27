package com.davotoula.lightcompressor.config

/**
 * How HDR (HLG or PQ) input video is handled. SDR input is never affected by this setting.
 *
 * - [KEEP_IF_SUPPORTED]: for H.265 output (not HLS) on a device whose HEVC encoder supports
 *   10-bit HDR editing, HLG and PQ input stays 10-bit HEVC Main10, tagged BT.2020 with its
 *   original transfer (HLG or ST 2084) and limited range. In every other case (H.264 output,
 *   HLS, an unsupported device, or input tagged with a colour standard other than BT.2020)
 *   the video is tone mapped to 8-bit SDR and tagged BT.709.
 * - [TONE_MAP_TO_SDR]: HLG and PQ input is always tone mapped to 8-bit SDR, tagged BT.709.
 *
 * HLS preparation always tone maps HDR input to SDR, whatever the mode.
 */
enum class HdrMode {
    KEEP_IF_SUPPORTED,
    TONE_MAP_TO_SDR,
}
