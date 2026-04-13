package com.davotoula.lightcompressor.hls

import com.davotoula.lightcompressor.Resolution
import com.davotoula.lightcompressor.VideoCodec

/**
 * A single rendition in the HLS encoding ladder.
 */
data class Rendition(
    val resolution: Resolution,
    val bitrateKbps: Int,
)

/**
 * An ordered list of renditions forming the HLS adaptive bitrate ladder.
 * Renditions are always sorted lowest-to-highest by [Resolution.shortSide].
 */
class HlsLadder(
    val renditions: List<Rendition>,
) {
    /** Remove renditions by [Resolution.label]. */
    fun drop(vararg labels: String): HlsLadder {
        val labelSet = labels.toSet()
        return HlsLadder(renditions.filter { it.resolution.label !in labelSet })
    }

    /** Add a rendition, maintaining sort order. */
    fun add(rendition: Rendition): HlsLadder = HlsLadder((renditions + rendition).sortedBy { it.resolution.shortSide })

    /** Filter to only renditions whose short side <= [sourceShortSide]. */
    fun forSource(sourceShortSide: Int): HlsLadder =
        HlsLadder(renditions.filter { it.resolution.shortSide <= sourceShortSide })

    companion object {
        @Suppress("MagicNumber")
        fun default(): HlsLadder =
            HlsLadder(
                listOf(
                    Rendition(Resolution.SD_360, 500),
                    Rendition(Resolution.SD_540, 1200),
                    Rendition(Resolution.HD_720, 2500),
                    Rendition(Resolution.FHD_1080, 5000),
                    Rendition(Resolution.UHD_4K, 15000),
                ),
            )
    }
}

/**
 * Configuration for HLS video preparation.
 */
data class HlsConfig(
    val ladder: HlsLadder = HlsLadder.default(),
    val codec: VideoCodec = VideoCodec.H264,
    val segmentDurationSeconds: Int = 6,
    val disableAudio: Boolean = false,
)
