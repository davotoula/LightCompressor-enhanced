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

        /**
         * Shortcut for `default().forSource(sourceShortSide)`. Returns the default encoding
         * ladder filtered to renditions whose short side is at most [sourceShortSide].
         */
        fun defaultForSource(sourceShortSide: Int): HlsLadder = default().forSource(sourceShortSide)
    }
}

/**
 * Configuration for HLS video preparation.
 *
 * @property ladder encoding ladder of renditions to produce
 * @property codec video codec for all renditions
 * @property segmentDurationSeconds target keyframe-aligned segment length
 * @property disableAudio drop the audio track from every rendition
 * @property singleFilePerRendition when true (default), emit one fMP4 file per rendition (init +
 *   every media segment concatenated) and reference each segment by `#EXT-X-BYTERANGE` in the
 *   playlist. The listener receives a single [HlsListener.onSegmentReady] callback per rendition
 *   with [HlsSegment.isCombinedRendition] set. Set to false for the multi-file layout (init.mp4 +
 *   per-segment .m4s files).
 */
data class HlsConfig(
    val ladder: HlsLadder = HlsLadder.default(),
    val codec: VideoCodec = VideoCodec.H264,
    val segmentDurationSeconds: Int = 6,
    val disableAudio: Boolean = false,
    val singleFilePerRendition: Boolean = true,
)
