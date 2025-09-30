package com.abedelazizshe.lightcompressorlibrary.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.coremedia.iso.boxes.SampleDescriptionBox
import com.coremedia.iso.boxes.sampleentry.AudioSampleEntry
import com.coremedia.iso.boxes.sampleentry.VisualSampleEntry
import com.googlecode.mp4parser.boxes.mp4.ESDescriptorBox
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.AudioSpecificConfig
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.DecoderConfigDescriptor
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.ESDescriptor
import com.googlecode.mp4parser.boxes.mp4.objectdescriptors.SLConfigDescriptor
import com.mp4parser.iso14496.part15.AvcConfigurationBox
import com.mp4parser.iso14496.part15.HevcConfigurationBox
import com.mp4parser.iso14496.part15.HevcDecoderConfigurationRecord
import java.nio.ByteBuffer
import java.util.*

class Track(id: Int, format: MediaFormat, audio: Boolean) {

    private var trackId: Long = 0
    private val samples = ArrayList<Sample>()
    private var duration: Long = 0
    private lateinit var handler: String
    private lateinit var sampleDescriptionBox: SampleDescriptionBox
    private var syncSamples: LinkedList<Int>? = null
    private var timeScale = 0
    private val creationTime = Date()
    private var height = 0
    private var width = 0
    private var volume = 0f
    private val sampleDurations = ArrayList<Long>()
    private val isAudio = audio
    private var samplingFrequencyIndexMap: Map<Int, Int> = HashMap()
    private var lastPresentationTimeUs: Long = 0
    private var first = true

    init {
        samplingFrequencyIndexMap = createSamplingFrequencyIndexMap()
        trackId = id.toLong()

        if (!isAudio) {
            initializeVideoTrack(format)
        } else {
            initializeAudioTrack(format)
        }
    }

    /**
     * Initializes video track properties and creates appropriate sample description box
     * based on the video codec (AVC, HEVC, or MPEG-4).
     */
    private fun initializeVideoTrack(format: MediaFormat) {
        sampleDurations.add(3015.toLong())
        duration = 3015
        width = format.getInteger(MediaFormat.KEY_WIDTH)
        height = format.getInteger(MediaFormat.KEY_HEIGHT)
        timeScale = 90000
        syncSamples = LinkedList()
        handler = "vide"

        sampleDescriptionBox = SampleDescriptionBox()
        val mime = format.getString(MediaFormat.KEY_MIME)

        when (mime) {
            "video/avc" -> createAvcTrack(format)
            "video/hevc" -> createHevcTrack(format)
            "video/mp4v" -> createMpeg4Track()
        }
    }

    /**
     * Creates an AVC (H.264) video track configuration.
     * Extracts SPS/PPS from format and configures the AVC configuration box.
     */
    private fun createAvcTrack(format: MediaFormat) {
        val visualSampleEntry = VisualSampleEntry(VisualSampleEntry.TYPE3).setup(width, height)
        val avcConfigurationBox = AvcConfigurationBox()

        configureSpsAndPps(format, avcConfigurationBox)
        configureAvcLevel(format, avcConfigurationBox)
        configureAvcProfile(avcConfigurationBox)

        visualSampleEntry.addBox(avcConfigurationBox)
        sampleDescriptionBox.addBox(visualSampleEntry)
    }

    /**
     * Extracts SPS (Sequence Parameter Set) and PPS (Picture Parameter Set) from the format
     * and configures them in the AVC configuration box.
     */
    private fun configureSpsAndPps(format: MediaFormat, avcConfigurationBox: AvcConfigurationBox) {
        val spsBuff = format.getByteBuffer("csd-0") ?: return

        spsBuff.position(4)
        val spsBytes = ByteArray(spsBuff.remaining())
        spsBuff[spsBytes]

        val ppsBuff = format.getByteBuffer("csd-1")
        ppsBuff?.let {
            it.position(4)
            val ppsBytes = ByteArray(it.remaining())
            it[ppsBytes]

            avcConfigurationBox.sequenceParameterSets = arrayListOf(spsBytes)
            avcConfigurationBox.pictureParameterSets = arrayListOf(ppsBytes)
        }
    }

    /**
     * Configures the AVC level indication based on the format's level value.
     * Uses a lookup map for cleaner code than a massive when statement.
     */
    private fun configureAvcLevel(format: MediaFormat, avcConfigurationBox: AvcConfigurationBox) {
        val defaultLevel = 13

        if (!format.containsKey("level")) {
            avcConfigurationBox.avcLevelIndication = defaultLevel
            return
        }

        val levelValue = format.getInteger("level")
        avcConfigurationBox.avcLevelIndication = avcLevelMap[levelValue] ?: defaultLevel
    }

    /**
     * Configures AVC profile and related properties.
     */
    private fun configureAvcProfile(avcConfigurationBox: AvcConfigurationBox) {
        avcConfigurationBox.apply {
            avcProfileIndication = 100
            bitDepthLumaMinus8 = -1
            bitDepthChromaMinus8 = -1
            chromaFormat = -1
            configurationVersion = 1
            lengthSizeMinusOne = 3
            profileCompatibility = 0
        }
    }

    /**
     * Creates an HEVC (H.265) video track configuration.
     * Extracts VPS/SPS/PPS parameter sets from format and configures the HEVC configuration box.
     */
    private fun createHevcTrack(format: MediaFormat) {
        android.util.Log.i("Track", "Creating HEVC track for H.265 video")

        val visualSampleEntry = VisualSampleEntry("hvc1").setup(width, height)
        visualSampleEntry.compressorname = "HEVC Coding"

        val hevcConfigurationBox = HevcConfigurationBox()
        hevcConfigurationBox.hevcDecoderConfigurationRecord = HevcDecoderConfigurationRecord()

        extractHevcParameterSets(format, hevcConfigurationBox)
        configureHevcRecord(hevcConfigurationBox.hevcDecoderConfigurationRecord)

        visualSampleEntry.addBox(hevcConfigurationBox)
        sampleDescriptionBox.addBox(visualSampleEntry)
    }

    /**
     * Extracts HEVC parameter sets (VPS, SPS, PPS) from the csd-0 buffer and
     * configures them in the HEVC configuration box.
     */
    private fun extractHevcParameterSets(format: MediaFormat, hevcConfigurationBox: HevcConfigurationBox) {
        val csd0 = format.getByteBuffer("csd-0")
        if (csd0 == null) {
            android.util.Log.w("Track", "No csd-0 found in MediaFormat for HEVC")
            return
        }

        android.util.Log.i("Track", "Parsing HEVC NAL units from csd-0, size: ${csd0.remaining()}")
        val nalUnits = parseNalUnits(csd0)

        val vpsArray = createHevcArray(32) // VPS NAL type
        val spsArray = createHevcArray(33) // SPS NAL type
        val ppsArray = createHevcArray(34) // PPS NAL type

        distributeNalUnitsByType(nalUnits, vpsArray, spsArray, ppsArray)

        android.util.Log.i(
            "Track",
            "HEVC parameter sets - VPS: ${vpsArray.nalUnits.size}, SPS: ${spsArray.nalUnits.size}, PPS: ${ppsArray.nalUnits.size}"
        )

        hevcConfigurationBox.hevcDecoderConfigurationRecord.arrays = listOf(vpsArray, spsArray, ppsArray)
    }

    /**
     * Creates an HEVC array structure for a specific NAL unit type.
     */
    private fun createHevcArray(nalUnitType: Int): HevcDecoderConfigurationRecord.Array {
        return HevcDecoderConfigurationRecord.Array().apply {
            array_completeness = false
            nal_unit_type = nalUnitType
            nalUnits = ArrayList()
        }
    }

    /**
     * Distributes parsed NAL units into their respective arrays (VPS, SPS, PPS)
     * based on NAL unit type.
     */
    private fun distributeNalUnitsByType(
        nalUnits: List<ByteArray>,
        vpsArray: HevcDecoderConfigurationRecord.Array,
        spsArray: HevcDecoderConfigurationRecord.Array,
        ppsArray: HevcDecoderConfigurationRecord.Array
    ) {
        for (nalUnit in nalUnits) {
            if (nalUnit.size < 2) continue

            val nalType = (nalUnit[0].toInt() and 0x7E) shr 1
            when (nalType) {
                32 -> {
                    vpsArray.nalUnits.add(nalUnit)
                    android.util.Log.i("Track", "Found VPS, size: ${nalUnit.size}")
                }
                33 -> {
                    spsArray.nalUnits.add(nalUnit)
                    android.util.Log.i("Track", "Found SPS, size: ${nalUnit.size}")
                }
                34 -> {
                    ppsArray.nalUnits.add(nalUnit)
                    android.util.Log.i("Track", "Found PPS, size: ${nalUnit.size}")
                }
                else -> {
                    android.util.Log.d("Track", "Skipping NAL type: $nalType")
                }
            }
        }
    }

    /**
     * Configures the HEVC decoder configuration record with standard values.
     */
    private fun configureHevcRecord(record: HevcDecoderConfigurationRecord) {
        record.apply {
            configurationVersion = 1
            lengthSizeMinusOne = 3
            general_profile_idc = 1
            general_level_idc = 120
        }
    }

    /**
     * Creates an MPEG-4 video track configuration.
     */
    private fun createMpeg4Track() {
        val visualSampleEntry = VisualSampleEntry(VisualSampleEntry.TYPE1).setup(width, height)
        sampleDescriptionBox.addBox(visualSampleEntry)
    }

    /**
     * Initializes audio track properties and creates the audio sample description box
     * with AAC codec configuration.
     */
    private fun initializeAudioTrack(format: MediaFormat) {
        sampleDurations.add(1024.toLong())
        duration = 1024
        volume = 1f
        timeScale = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        handler = "soun"
        sampleDescriptionBox = SampleDescriptionBox()

        val audioSampleEntry = AudioSampleEntry(AudioSampleEntry.TYPE3).setup(format)
        val esds = createEsDescriptorBox(audioSampleEntry)

        audioSampleEntry.addBox(esds)
        sampleDescriptionBox.addBox(audioSampleEntry)
    }

    /**
     * Creates an ES Descriptor Box with audio codec configuration.
     */
    private fun createEsDescriptorBox(audioSampleEntry: AudioSampleEntry): ESDescriptorBox {
        val descriptor = ESDescriptor().apply {
            esId = 0
            slConfigDescriptor = SLConfigDescriptor().apply {
                predefined = 2
            }
            decoderConfigDescriptor = DecoderConfigDescriptor().setup().apply {
                audioSpecificInfo = createAudioSpecificConfig(audioSampleEntry)
            }
        }

        val data = descriptor.serialize()
        return ESDescriptorBox().apply {
            esDescriptor = descriptor
            this.data = data
        }
    }

    /**
     * Creates audio specific configuration with sampling frequency and channel info.
     */
    private fun createAudioSpecificConfig(audioSampleEntry: AudioSampleEntry): AudioSpecificConfig {
        return AudioSpecificConfig().apply {
            setAudioObjectType(2)
            setSamplingFrequencyIndex(
                samplingFrequencyIndexMap[audioSampleEntry.sampleRate.toInt()]!!
            )
            setChannelConfiguration(audioSampleEntry.channelCount)
        }
    }

    fun getTrackId(): Long = trackId

    fun addSample(offset: Long, bufferInfo: MediaCodec.BufferInfo) {
        val isSyncFrame = !isAudio && bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0

        samples.add(Sample(offset, bufferInfo.size.toLong()))

        if (syncSamples != null && isSyncFrame) {
            syncSamples?.add(samples.size)
        }
        var delta = bufferInfo.presentationTimeUs - lastPresentationTimeUs
        lastPresentationTimeUs = bufferInfo.presentationTimeUs
        delta = (delta * timeScale + 500000L) / 1000000L
        if (!first) {
            sampleDurations.add(sampleDurations.size - 1, delta)
            duration += delta
        }
        first = false
    }

    fun getSamples(): ArrayList<Sample> = samples

    fun getDuration(): Long = duration

    fun getHandler(): String = handler

    fun getSampleDescriptionBox(): SampleDescriptionBox = sampleDescriptionBox

    fun getSyncSamples(): LongArray? {
        if (syncSamples == null || syncSamples!!.isEmpty()) {
            return null
        }
        val returns = LongArray(syncSamples!!.size)
        for (i in syncSamples!!.indices) {
            returns[i] = syncSamples!![i].toLong()
        }
        return returns
    }

    fun getTimeScale(): Int = timeScale

    fun getCreationTime(): Date = creationTime

    fun getWidth(): Int = width

    fun getHeight(): Int = height

    fun getVolume(): Float = volume

    fun getSampleDurations(): ArrayList<Long> = sampleDurations

    fun isAudio(): Boolean = isAudio

    private fun DecoderConfigDescriptor.setup(): DecoderConfigDescriptor = apply {
        objectTypeIndication = 0x40
        streamType = 5
        bufferSizeDB = 1536
        maxBitRate = 96000
        avgBitRate = 96000
    }

    private fun VisualSampleEntry.setup(w: Int, h: Int): VisualSampleEntry = apply {
        dataReferenceIndex = 1
        depth = 24
        frameCount = 1
        horizresolution = 72.0
        vertresolution = 72.0
        width = w
        height = h
        compressorname = "AVC Coding"
    }

    private fun AudioSampleEntry.setup(format: MediaFormat): AudioSampleEntry = apply {
        channelCount =
            if (format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1) 2 else format.getInteger(
                MediaFormat.KEY_CHANNEL_COUNT
            )
        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE).toLong()
        dataReferenceIndex = 1
        sampleSize = 16
    }

    /**
     * Parses NAL units from a ByteBuffer containing length-prefixed NAL data.
     */
    private fun parseNalUnits(buffer: ByteBuffer): List<ByteArray> {
        val nalUnits = mutableListOf<ByteArray>()
        buffer.rewind()

        while (buffer.remaining() > 4) {
            val nalLength = buffer.int
            if (nalLength > 0 && nalLength <= buffer.remaining()) {
                val nalData = ByteArray(nalLength)
                buffer.get(nalData)
                nalUnits.add(nalData)
            } else {
                break
            }
        }

        return nalUnits
    }

    companion object {
        /**
         * Creates a map of sampling frequencies to their AAC index values.
         */
        private fun createSamplingFrequencyIndexMap(): Map<Int, Int> {
            return mapOf(
                96000 to 0x0,
                88200 to 0x1,
                64000 to 0x2,
                48000 to 0x3,
                44100 to 0x4,
                32000 to 0x5,
                24000 to 0x6,
                22050 to 0x7,
                16000 to 0x8,
                12000 to 0x9,
                11025 to 0xa,
                8000 to 0xb,
            )
        }

        /**
         * Maps MediaCodec AVC profile level constants to their numeric level indication values.
         * This replaces a large when statement with a cleaner lookup table.
         */
        private val avcLevelMap = mapOf(
            MediaCodecInfo.CodecProfileLevel.AVCLevel1 to 1,
            MediaCodecInfo.CodecProfileLevel.AVCLevel2 to 2,
            MediaCodecInfo.CodecProfileLevel.AVCLevel11 to 11,
            MediaCodecInfo.CodecProfileLevel.AVCLevel12 to 12,
            MediaCodecInfo.CodecProfileLevel.AVCLevel13 to 13,
            MediaCodecInfo.CodecProfileLevel.AVCLevel21 to 21,
            MediaCodecInfo.CodecProfileLevel.AVCLevel22 to 22,
            MediaCodecInfo.CodecProfileLevel.AVCLevel3 to 3,
            MediaCodecInfo.CodecProfileLevel.AVCLevel31 to 31,
            MediaCodecInfo.CodecProfileLevel.AVCLevel32 to 32,
            MediaCodecInfo.CodecProfileLevel.AVCLevel4 to 4,
            MediaCodecInfo.CodecProfileLevel.AVCLevel41 to 41,
            MediaCodecInfo.CodecProfileLevel.AVCLevel42 to 42,
            MediaCodecInfo.CodecProfileLevel.AVCLevel5 to 5,
            MediaCodecInfo.CodecProfileLevel.AVCLevel51 to 51,
            MediaCodecInfo.CodecProfileLevel.AVCLevel52 to 52,
            MediaCodecInfo.CodecProfileLevel.AVCLevel1b to 0x1b,
        )
    }
}
