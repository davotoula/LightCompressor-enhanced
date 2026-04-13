package com.davotoula.lightcompressor

/**
 * Standard video resolutions identified by short-side pixel count.
 * Used by both the compression and HLS preparation APIs.
 */
@Suppress("MagicNumber")
enum class Resolution(
    val shortSide: Int,
    val label: String,
) {
    UHD_4K(2160, "4K"),
    FHD_1080(1080, "1080p"),
    HD_720(720, "720p"),
    SD_540(540, "540p"),
    SD_360(360, "360p"),
}
