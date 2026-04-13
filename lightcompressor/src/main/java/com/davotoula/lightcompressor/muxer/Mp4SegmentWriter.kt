package com.davotoula.lightcompressor.muxer

import java.io.OutputStream

/**
 * Audio track configuration for the initialization segment.
 */
internal data class AudioConfig(
    val codecConfig: ByteArray,
    val sampleRate: Int,
    val channelCount: Int,
    val timescale: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioConfig) return false
        return codecConfig.contentEquals(other.codecConfig) &&
            sampleRate == other.sampleRate &&
            channelCount == other.channelCount &&
            timescale == other.timescale
    }

    override fun hashCode(): Int {
        var result = codecConfig.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + channelCount
        result = 31 * result + timescale
        return result
    }
}

/**
 * A single encoded sample (video or audio frame) from MediaCodec.
 */
internal data class EncodedSample(
    val data: ByteArray,
    val presentationTimeUs: Long,
    val durationUs: Long,
    val flags: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedSample) return false
        return data.contentEquals(other.data) &&
            presentationTimeUs == other.presentationTimeUs &&
            durationUs == other.durationUs &&
            flags == other.flags
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + durationUs.hashCode()
        result = 31 * result + flags
        return result
    }
}

/**
 * Writes fragmented MP4 (fMP4) initialization and media segments
 * conforming to ISO 14496-12 for HLS delivery.
 */
@Suppress("TooManyFunctions", "MagicNumber")
internal class Mp4SegmentWriter(
    private val videoCodecConfig: ByteArray,
    private val videoMimeType: String,
    private val videoWidth: Int,
    private val videoHeight: Int,
    private val videoTimescale: Int = DEFAULT_VIDEO_TIMESCALE,
    private val audioConfig: AudioConfig? = null,
) {
    /**
     * Writes the fMP4 initialization segment (ftyp + moov) to [output].
     * This must be emitted once per rendition before any media segments.
     */
    fun writeInitSegment(output: OutputStream) {
        val writer = BoxWriter(output)
        writeFtyp(writer)
        writeMoov(writer)
    }

    /**
     * Writes one fMP4 media segment (moof + mdat) to [output].
     *
     * @param videoSamples encoded video frames for this segment
     * @param audioSamples encoded audio frames for this segment (empty if audio disabled)
     * @param sequenceNumber fragment sequence number (1-based, incrementing)
     * @param baseDecodeTimeUs decode timestamp of the first sample in this segment (microseconds)
     * @param output target stream
     */
    fun writeMediaSegment(
        videoSamples: List<EncodedSample>,
        audioSamples: List<EncodedSample>,
        sequenceNumber: Int,
        baseDecodeTimeUs: Long,
        output: OutputStream,
    ) {
        val writer = BoxWriter(output)
        val hasAudio = audioSamples.isNotEmpty() && audioConfig != null
        val moofSize = computeMoofSize(videoSamples.size, audioSamples.size, hasAudio)
        val videoDataOffset = moofSize + 8 // 8 = mdat box header
        val audioDataOffset = videoDataOffset + videoSamples.sumOf { it.data.size }

        writer.box("moof") {
            writeMfhd(this, sequenceNumber)
            writeVideoTraf(this, videoSamples, baseDecodeTimeUs, videoDataOffset)
            if (hasAudio) {
                writeAudioTraf(this, audioSamples, baseDecodeTimeUs, audioDataOffset)
            }
        }
        writer.box("mdat") {
            for (sample in videoSamples) {
                writeBytes(sample.data)
            }
            if (hasAudio) {
                for (sample in audioSamples) {
                    writeBytes(sample.data)
                }
            }
        }
    }

    private fun writeMfhd(
        scope: BoxWriter.BoxScope,
        sequenceNumber: Int,
    ) {
        scope.fullBox("mfhd", version = 0, flags = 0) {
            writeUInt32(sequenceNumber.toLong())
        }
    }

    private fun writeVideoTraf(
        scope: BoxWriter.BoxScope,
        samples: List<EncodedSample>,
        baseDecodeTimeUs: Long,
        dataOffset: Int,
    ) {
        scope.box("traf") {
            writeTfhd(this, VIDEO_TRACK_ID)
            writeTfdt(this, baseDecodeTimeUs, videoTimescale)
            writeVideoTrun(this, samples, dataOffset)
        }
    }

    private fun writeAudioTraf(
        scope: BoxWriter.BoxScope,
        samples: List<EncodedSample>,
        baseDecodeTimeUs: Long,
        dataOffset: Int,
    ) {
        val audio = audioConfig ?: return
        scope.box("traf") {
            writeTfhd(this, AUDIO_TRACK_ID)
            writeTfdt(this, baseDecodeTimeUs, audio.timescale)
            writeAudioTrun(this, samples, dataOffset)
        }
    }

    private fun writeTfhd(
        scope: BoxWriter.BoxScope,
        trackId: Int,
    ) {
        // flags: 0x020000 = default-base-is-moof
        scope.fullBox("tfhd", version = 0, flags = 0x020000) {
            writeUInt32(trackId.toLong())
        }
    }

    private fun writeTfdt(
        scope: BoxWriter.BoxScope,
        baseDecodeTimeUs: Long,
        timescale: Int,
    ) {
        val baseDecodeTime = baseDecodeTimeUs * timescale / 1_000_000L
        scope.fullBox("tfdt", version = 1, flags = 0) {
            writeUInt64(baseDecodeTime)
        }
    }

    private fun writeVideoTrun(
        scope: BoxWriter.BoxScope,
        samples: List<EncodedSample>,
        dataOffset: Int,
    ) {
        // flags: 0x000001 = data-offset-present
        //        0x000100 = sample-duration-present
        //        0x000200 = sample-size-present
        //        0x000400 = sample-flags-present
        val trunFlags = 0x000001 or 0x000100 or 0x000200 or 0x000400
        scope.fullBox("trun", version = 0, flags = trunFlags) {
            writeUInt32(samples.size.toLong()) // sample count
            writeUInt32(dataOffset.toLong()) // data offset
            for (sample in samples) {
                val duration = sample.durationUs * videoTimescale / 1_000_000L
                writeUInt32(duration) // sample duration
                writeUInt32(sample.data.size.toLong()) // sample size
                val isKeyFrame = sample.flags and KEY_FRAME_FLAG != 0
                val sampleFlags = if (isKeyFrame) 0x02000000 else 0x00010000
                writeUInt32(sampleFlags.toLong()) // sample flags
            }
        }
    }

    private fun writeAudioTrun(
        scope: BoxWriter.BoxScope,
        samples: List<EncodedSample>,
        dataOffset: Int,
    ) {
        val audio = audioConfig ?: return
        val trunFlags = 0x000001 or 0x000100 or 0x000200
        scope.fullBox("trun", version = 0, flags = trunFlags) {
            writeUInt32(samples.size.toLong())
            writeUInt32(dataOffset.toLong()) // data offset
            for (sample in samples) {
                val duration = sample.durationUs * audio.timescale / 1_000_000L
                writeUInt32(duration)
                writeUInt32(sample.data.size.toLong())
            }
        }
    }

    private fun computeMoofSize(
        videoSampleCount: Int,
        audioSampleCount: Int,
        hasAudio: Boolean,
    ): Int {
        val mfhdSize = 16
        val videoTrafSize = 64 + videoSampleCount * 12
        val audioTrafSize = if (hasAudio) 64 + audioSampleCount * 8 else 0
        return 8 + mfhdSize + videoTrafSize + audioTrafSize
    }

    private fun writeFtyp(writer: BoxWriter) {
        writer.box("ftyp") {
            writeFourCC("isom") // major brand
            writeUInt32(0x200L) // minor version
            writeFourCC("isom")
            writeFourCC("iso6")
            writeFourCC("msdh")
            writeFourCC("msix")
        }
    }

    private fun writeMoov(writer: BoxWriter) {
        writer.box("moov") {
            writeMvhd(this)
            writeVideoTrak(this, trackId = VIDEO_TRACK_ID)
            if (audioConfig != null) {
                writeAudioTrak(this, trackId = AUDIO_TRACK_ID)
            }
            writeMvex(this)
        }
    }

    private fun writeMvhd(scope: BoxWriter.BoxScope) {
        scope.fullBox("mvhd", version = 0, flags = 0) {
            writeUInt32(0L) // creation time
            writeUInt32(0L) // modification time
            writeUInt32(videoTimescale.toLong()) // timescale
            writeUInt32(0L) // duration (unknown for fragmented)
            writeUInt32(0x00010000L) // rate = 1.0 (fixed point 16.16)
            writeUInt16(0x0100) // volume = 1.0 (fixed point 8.8)
            writeZeros(10) // reserved
            // Unity matrix (9 x int32)
            writeUInt32(0x00010000L)
            writeUInt32(0L)
            writeUInt32(0L)
            writeUInt32(0L)
            writeUInt32(0x00010000L)
            writeUInt32(0L)
            writeUInt32(0L)
            writeUInt32(0L)
            writeUInt32(0x40000000L)
            writeZeros(24) // pre-defined
            val nextTrackId = if (audioConfig != null) 3L else 2L
            writeUInt32(nextTrackId) // next track ID
        }
    }

    private fun writeVideoTrak(
        scope: BoxWriter.BoxScope,
        trackId: Int,
    ) {
        scope.box("trak") {
            writeTkhd(this, trackId, videoWidth, videoHeight)
            box("mdia") {
                writeMdhd(this, videoTimescale)
                writeHdlr(this, "vide", "VideoHandler")
                box("minf") {
                    fullBox("vmhd", version = 0, flags = 1) {
                        writeUInt16(0) // graphicsmode
                        writeUInt16(0)
                        writeUInt16(0)
                        writeUInt16(0) // opcolor
                    }
                    writeDinf(this)
                    writeVideoStbl(this)
                }
            }
        }
    }

    private fun writeAudioTrak(
        scope: BoxWriter.BoxScope,
        trackId: Int,
    ) {
        val audio = audioConfig ?: return
        scope.box("trak") {
            writeTkhd(this, trackId, 0, 0)
            box("mdia") {
                writeMdhd(this, audio.timescale)
                writeHdlr(this, "soun", "SoundHandler")
                box("minf") {
                    fullBox("smhd", version = 0, flags = 0) {
                        writeUInt16(0) // balance
                        writeUInt16(0) // reserved
                    }
                    writeDinf(this)
                    writeAudioStbl(this)
                }
            }
        }
    }

    private fun writeTkhd(
        scope: BoxWriter.BoxScope,
        trackId: Int,
        width: Int,
        height: Int,
    ) {
        scope.fullBox("tkhd", version = 0, flags = 3) {
            // flags: enabled + in-movie
            writeUInt32(0L) // creation time
            writeUInt32(0L) // modification time
            writeUInt32(trackId.toLong()) // track ID
            writeUInt32(0L) // reserved
            writeUInt32(0L) // duration
            writeZeros(8) // reserved
            writeUInt16(0) // layer
            writeUInt16(0) // alternate group
            writeUInt16(if (width == 0) 0x0100 else 0) // volume (audio=1.0, video=0)
            writeUInt16(0) // reserved
            // Unity matrix
            writeUInt32(0x00010000L)
            writeUInt32(0L)
            writeUInt32(0L)
            writeUInt32(0L)
            writeUInt32(0x00010000L)
            writeUInt32(0L)
            writeUInt32(0L)
            writeUInt32(0L)
            writeUInt32(0x40000000L)
            writeUInt32((width.toLong() shl 16)) // width (16.16 fixed)
            writeUInt32((height.toLong() shl 16)) // height (16.16 fixed)
        }
    }

    private fun writeMdhd(
        scope: BoxWriter.BoxScope,
        timescale: Int,
    ) {
        scope.fullBox("mdhd", version = 0, flags = 0) {
            writeUInt32(0L) // creation time
            writeUInt32(0L) // modification time
            writeUInt32(timescale.toLong())
            writeUInt32(0L) // duration
            writeUInt16(0x55C4) // language = "und"
            writeUInt16(0) // pre-defined
        }
    }

    private fun writeHdlr(
        scope: BoxWriter.BoxScope,
        handlerType: String,
        name: String,
    ) {
        scope.fullBox("hdlr", version = 0, flags = 0) {
            writeUInt32(0L) // pre-defined
            writeFourCC(handlerType)
            writeZeros(12) // reserved
            writeBytes(name.toByteArray(Charsets.US_ASCII))
            writeUInt8(0) // null terminator
        }
    }

    private fun writeDinf(scope: BoxWriter.BoxScope) {
        scope.box("dinf") {
            fullBox("dref", version = 0, flags = 0) {
                writeUInt32(1L) // entry count
                fullBox("url ", version = 0, flags = 1) {} // self-contained flag
            }
        }
    }

    private fun writeVideoStbl(scope: BoxWriter.BoxScope) {
        scope.box("stbl") {
            writeVideoStsd(this)
            writeEmptyTimeToSample(this)
            writeEmptySampleToChunk(this)
            writeEmptySampleSize(this)
            writeEmptyChunkOffset(this)
        }
    }

    private fun writeAudioStbl(scope: BoxWriter.BoxScope) {
        scope.box("stbl") {
            writeAudioStsd(this)
            writeEmptyTimeToSample(this)
            writeEmptySampleToChunk(this)
            writeEmptySampleSize(this)
            writeEmptyChunkOffset(this)
        }
    }

    private fun writeVideoStsd(scope: BoxWriter.BoxScope) {
        scope.fullBox("stsd", version = 0, flags = 0) {
            writeUInt32(1L) // entry count
            val codecBox = if (videoMimeType == "video/hevc") "hev1" else "avc1"
            box(codecBox) {
                writeZeros(6) // reserved
                writeUInt16(1) // data reference index
                writeZeros(16) // pre-defined + reserved
                writeUInt16(videoWidth)
                writeUInt16(videoHeight)
                writeUInt32(0x00480000L) // h-resolution 72dpi (16.16)
                writeUInt32(0x00480000L) // v-resolution 72dpi
                writeUInt32(0L) // reserved
                writeUInt16(1) // frame count
                writeZeros(32) // compressor name
                writeUInt16(0x0018) // depth = 24
                writeUInt16(0xFFFF) // pre-defined = -1
                writeCodecConfigBox(this)
            }
        }
    }

    private fun writeCodecConfigBox(scope: BoxWriter.BoxScope) {
        if (videoMimeType == "video/hevc") {
            scope.box("hvcC") {
                writeBytes(videoCodecConfig)
            }
        } else {
            scope.box("avcC") {
                writeBytes(videoCodecConfig)
            }
        }
    }

    private fun writeAudioStsd(scope: BoxWriter.BoxScope) {
        val audio = audioConfig ?: return
        scope.fullBox("stsd", version = 0, flags = 0) {
            writeUInt32(1L) // entry count
            box("mp4a") {
                writeZeros(6) // reserved
                writeUInt16(1) // data reference index
                writeZeros(8) // reserved
                writeUInt16(audio.channelCount)
                writeUInt16(16) // sample size
                writeUInt16(0) // compression id
                writeUInt16(0) // reserved
                writeUInt32((audio.sampleRate.toLong() shl 16)) // sample rate (16.16)
                writeEsds(this)
            }
        }
    }

    private fun writeEsds(scope: BoxWriter.BoxScope) {
        val audio = audioConfig ?: return
        scope.fullBox("esds", version = 0, flags = 0) {
            val configLen = audio.codecConfig.size
            // ES_Descriptor
            writeUInt8(0x03) // tag
            writeUInt8(23 + configLen) // length
            writeUInt16(1) // ES_ID
            writeUInt8(0) // stream priority
            // DecoderConfigDescriptor
            writeUInt8(0x04) // tag
            writeUInt8(15 + configLen) // length
            writeUInt8(0x40) // objectTypeIndication = AAC
            writeUInt8(0x15) // streamType = audio (5 << 2 | 1)
            writeUInt8(0)
            writeUInt16(0) // bufferSizeDB (3 bytes)
            writeUInt32(0L) // maxBitrate
            writeUInt32(0L) // avgBitrate
            // DecoderSpecificInfo
            writeUInt8(0x05) // tag
            writeUInt8(configLen) // length
            writeBytes(audio.codecConfig)
            // SLConfigDescriptor
            writeUInt8(0x06) // tag
            writeUInt8(1) // length
            writeUInt8(0x02) // predefined = MP4
        }
    }

    private fun writeEmptyTimeToSample(scope: BoxWriter.BoxScope) {
        scope.fullBox("stts", version = 0, flags = 0) {
            writeUInt32(0L) // entry count = 0 (data in fragments)
        }
    }

    private fun writeEmptySampleToChunk(scope: BoxWriter.BoxScope) {
        scope.fullBox("stsc", version = 0, flags = 0) {
            writeUInt32(0L) // entry count = 0
        }
    }

    private fun writeEmptySampleSize(scope: BoxWriter.BoxScope) {
        scope.fullBox("stsz", version = 0, flags = 0) {
            writeUInt32(0L) // sample size = 0 (variable)
            writeUInt32(0L) // sample count = 0
        }
    }

    private fun writeEmptyChunkOffset(scope: BoxWriter.BoxScope) {
        scope.fullBox("stco", version = 0, flags = 0) {
            writeUInt32(0L) // entry count = 0
        }
    }

    private fun writeMvex(scope: BoxWriter.BoxScope) {
        scope.box("mvex") {
            writeTrex(this, VIDEO_TRACK_ID)
            if (audioConfig != null) {
                writeTrex(this, AUDIO_TRACK_ID)
            }
        }
    }

    private fun writeTrex(
        scope: BoxWriter.BoxScope,
        trackId: Int,
    ) {
        scope.fullBox("trex", version = 0, flags = 0) {
            writeUInt32(trackId.toLong())
            writeUInt32(1L) // default sample description index
            writeUInt32(0L) // default sample duration
            writeUInt32(0L) // default sample size
            writeUInt32(0L) // default sample flags
        }
    }

    companion object {
        const val VIDEO_TRACK_ID = 1
        const val AUDIO_TRACK_ID = 2
        const val DEFAULT_VIDEO_TIMESCALE = 90000
        private const val KEY_FRAME_FLAG = 1 // MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
}
