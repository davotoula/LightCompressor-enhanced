package com.abedelazizshe.lightcompressorlibrary.video

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import com.coremedia.iso.boxes.*
import com.coremedia.iso.boxes.sampleentry.VisualSampleEntry
import com.googlecode.mp4parser.util.Matrix
import com.mp4parser.iso14496.part15.HevcConfigurationBox
import com.mp4parser.iso14496.part15.HevcDecoderConfigurationRecord
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.*
//import org.mp4parser.boxes.iso14496.part15.HevcConfigurationBox
//import org.mp4parser.boxes.iso14496.part15.HevcDecoderConfigurationRecord
//import org.mp4parser.boxes.sampleentry.VisualSampleEntry

class MP4Builder {

    private lateinit var mdat: Mdat
    private lateinit var currentMp4Movie: Mp4Movie
    private lateinit var fos: FileOutputStream
    private lateinit var fc: FileChannel
    private var dataOffset: Long = 0
    private var wroteSinceLastMdat: Long = 0
    private var writeNewMdat = true
    private val track2SampleSizes = HashMap<Track, LongArray>()
    private lateinit var sizeBuffer: ByteBuffer

    @Throws(Exception::class)
    fun createMovie(mp4Movie: Mp4Movie): MP4Builder {
        currentMp4Movie = mp4Movie

        fos = FileOutputStream(mp4Movie.getCacheFile())
        fc = fos.channel

        val fileTypeBox: FileTypeBox = createFileTypeBox()
        fileTypeBox.getBox(fc)
        dataOffset += fileTypeBox.size
        wroteSinceLastMdat = dataOffset

        mdat = Mdat()
        sizeBuffer = ByteBuffer.allocateDirect(4)

        return this
    }

    @Throws(Exception::class)
    internal fun flushCurrentMdat() {
        val oldPosition = fc.position()
        fc.position(mdat.offset)
        mdat.getBox(fc)
        fc.position(oldPosition)
        mdat.setDataOffset(0)
        mdat.setContentSize(0)
        fos.flush()
    }

    @Throws(Exception::class)
    fun writeSampleData(
        trackIndex: Int,
        byteBuf: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo,
        isAudio: Boolean
    ) {

        if (writeNewMdat) {
            mdat.apply {
                setContentSize(0)
                getBox(fc)
                setDataOffset(dataOffset)
            }
            dataOffset += 16
            wroteSinceLastMdat += 16
            writeNewMdat = false
        }

        val sampleOffset = dataOffset

        val bytesWritten = if (isAudio) {
            writeAudioSample(byteBuf, bufferInfo)
        } else {
            writeVideoSample(byteBuf, bufferInfo)
        }

        mdat.setContentSize(mdat.getContentSize() + bytesWritten)
        wroteSinceLastMdat += bytesWritten

        val adjustedInfo = bufferInfo.copyWithSize(bytesWritten.toInt())
        currentMp4Movie.addSample(trackIndex, sampleOffset, adjustedInfo)

        dataOffset += bytesWritten

        var flush = false
        if (wroteSinceLastMdat >= 32 * 1024) {
            flushCurrentMdat()
            writeNewMdat = true
            flush = true
            wroteSinceLastMdat = 0
        }

        if (flush) {
            fos.flush()
        }
    }

    private fun writeAudioSample(byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo): Long {
        val audioBuffer = byteBuf.duplicate()
        audioBuffer.position(bufferInfo.offset)
        audioBuffer.limit(bufferInfo.offset + bufferInfo.size)
        fc.writeFully(audioBuffer)
        return bufferInfo.size.toLong()
    }

    private fun writeVideoSample(byteBuf: ByteBuffer, bufferInfo: MediaCodec.BufferInfo): Long {
        val sampleBuffer = byteBuf.duplicate()
        sampleBuffer.position(bufferInfo.offset)
        sampleBuffer.limit(bufferInfo.offset + bufferInfo.size)

        val sampleData = ByteArray(bufferInfo.size)
        sampleBuffer.get(sampleData)

        val nalPayloads = extractNalPayloads(sampleData)

        var written = 0L
        for (payload in nalPayloads) {
            sizeBuffer.clear()
            sizeBuffer.putInt(payload.size)
            sizeBuffer.flip()
            written += fc.writeFully(sizeBuffer)
            written += fc.writeFully(ByteBuffer.wrap(payload))
        }
        return written
    }

    private fun extractNalPayloads(sampleData: ByteArray): List<ByteArray> {
        val lengthPrefixed = parseLengthPrefixed(sampleData)
        if (lengthPrefixed != null) {
            return lengthPrefixed
        }
        val startCodePrefixed = parseStartCodePrefixed(sampleData)
        if (startCodePrefixed.isNotEmpty()) {
            return startCodePrefixed
        }
        throw IllegalStateException("Unable to parse NAL units from sample data")
    }

    private fun parseLengthPrefixed(sampleData: ByteArray): List<ByteArray>? {
        var offset = 0
        val payloads = mutableListOf<ByteArray>()
        while (offset + 4 <= sampleData.size) {
            val length = ((sampleData[offset].toInt() and 0xFF) shl 24) or
                ((sampleData[offset + 1].toInt() and 0xFF) shl 16) or
                ((sampleData[offset + 2].toInt() and 0xFF) shl 8) or
                (sampleData[offset + 3].toInt() and 0xFF)
            offset += 4
            if (length <= 0 || offset + length > sampleData.size) {
                return null
            }
            payloads.add(sampleData.copyOfRange(offset, offset + length))
            offset += length
        }
        return if (payloads.isNotEmpty() && offset == sampleData.size) payloads else null
    }

    private fun parseStartCodePrefixed(sampleData: ByteArray): List<ByteArray> {
        val payloads = mutableListOf<ByteArray>()
        var i = 0
        var nalStart = -1
        while (i < sampleData.size) {
            val startCodeLength = startCodeLengthAt(sampleData, i)
            if (startCodeLength > 0) {
                if (nalStart >= 0 && nalStart < i) {
                    payloads.add(sampleData.copyOfRange(nalStart, i))
                }
                nalStart = i + startCodeLength
                i += startCodeLength
            } else {
                i++
            }
        }
        if (nalStart in 0 until sampleData.size) {
            payloads.add(sampleData.copyOfRange(nalStart, sampleData.size))
        }
        return payloads
    }

    private fun startCodeLengthAt(data: ByteArray, index: Int): Int {
        if (index + 3 < data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte()
            && data[index + 2] == 0.toByte() && data[index + 3] == 1.toByte()
        ) {
            return 4
        }
        if (index + 2 < data.size && data[index] == 0.toByte() && data[index + 1] == 0.toByte()
            && data[index + 2] == 1.toByte()
        ) {
            return 3
        }
        return 0
    }

    private fun MediaCodec.BufferInfo.copyWithSize(newSize: Int): MediaCodec.BufferInfo {
        return MediaCodec.BufferInfo().apply {
            offset = 0
            size = newSize
            presentationTimeUs = this@copyWithSize.presentationTimeUs
            flags = this@copyWithSize.flags
        }
    }

    private fun FileChannel.writeFully(buffer: ByteBuffer): Long {
        var written = 0L
        while (buffer.hasRemaining()) {
            written += write(buffer).toLong()
        }
        return written
    }

    fun addTrack(mediaFormat: MediaFormat, isAudio: Boolean): Int {
        if (isAudio) return currentMp4Movie.addTrack(mediaFormat, true)

        val mime = mediaFormat.getString(MediaFormat.KEY_MIME)
        if (mime != null && mime.startsWith("video/hevc")) {
            try {
                val csd0 = mediaFormat.getByteBuffer("csd-0") ?: return currentMp4Movie.addTrack(mediaFormat, false)
                val data = ByteArray(csd0.remaining())
                csd0.get(data)

                val vpsList = mutableListOf<ByteArray>()
                val spsList = mutableListOf<ByteArray>()
                val ppsList = mutableListOf<ByteArray>()

                var i = 0
                while (i + 4 < data.size) {
                    val startCodeLen = when {
                        i + 3 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                                data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte() -> 4
                        i + 2 < data.size && data[i] == 0.toByte() && data[i + 1] == 0.toByte() &&
                                data[i + 2] == 1.toByte() -> 3
                        else -> { i++; continue }
                    }

                    val start = i + startCodeLen
                    var next = start
                    while (next + 3 < data.size && !(data[next] == 0.toByte() &&
                                data[next + 1] == 0.toByte() &&
                                ((data[next + 2] == 1.toByte()) ||
                                        (next + 3 < data.size && data[next + 2] == 0.toByte() && data[next + 3] == 1.toByte())))
                    ) next++

                    val nal = data.copyOfRange(start, next)
                    val nalType = (nal[0].toInt() shr 1) and 0x3F
                    when (nalType) {
                        32 -> vpsList.add(nal) // VPS
                        33 -> spsList.add(nal) // SPS
                        34 -> ppsList.add(nal) // PPS
                    }
                    i = next
                }

                Log.d("MP4Builder", "HEVC hvcC: VPS=${vpsList.size}, SPS=${spsList.size}, PPS=${ppsList.size}")

                val record = HevcDecoderConfigurationRecord().apply {
                    configurationVersion = 1
                    general_profile_idc = 1
                    general_level_idc = 120
                    lengthSizeMinusOne = 3
                    chromaFormat = 1
                    bitDepthLumaMinus8 = 0
                    bitDepthChromaMinus8 = 0
                    avgFrameRate = 60000
                    constantFrameRate = 0
                    numTemporalLayers = 1
                    isTemporalIdNested = false

                    arrays = mutableListOf<HevcDecoderConfigurationRecord.Array>().apply {
                        if (vpsList.isNotEmpty()) add(buildArray(32, vpsList))
                        if (spsList.isNotEmpty()) add(buildArray(33, spsList))
                        if (ppsList.isNotEmpty()) add(buildArray(34, ppsList))
                    }
                }

                val hvcC = HevcConfigurationBox().apply {
                    hevcDecoderConfigurationRecord = record
                }

                val visualEntry = VisualSampleEntry("hvc1").apply {
                    width = mediaFormat.getInteger(MediaFormat.KEY_WIDTH)
                    height = mediaFormat.getInteger(MediaFormat.KEY_HEIGHT)
                    addBox(hvcC)
                }

                val stsd = SampleDescriptionBox().apply { addBox(visualEntry) }

                val trackIndex = currentMp4Movie.addTrack(mediaFormat, false)
                currentMp4Movie.getTracks()[trackIndex].setSampleDescriptionBox(stsd)

                return trackIndex

            } catch (e: Exception) {
                Log.e("MP4Builder", "Failed to construct hvcC", e)
            }
        }

        return currentMp4Movie.addTrack(mediaFormat, isAudio)
    }

    private fun buildArray(type: Int, nals: List<ByteArray>): HevcDecoderConfigurationRecord.Array {
        return HevcDecoderConfigurationRecord.Array().apply {
            array_completeness = true
            nal_unit_type = type
            reserved = false
            this.nalUnits.addAll(nals)
        }
    }

    @Throws(Exception::class)
    fun finishMovie() {
        if (mdat.getContentSize() != 0L) {
            flushCurrentMdat()
        }

        for (track in currentMp4Movie.getTracks()) {
            val samples: List<Sample> = track.getSamples()
            val sizes = LongArray(samples.size)
            for (i in sizes.indices) {
                sizes[i] = samples[i].size
            }
            track2SampleSizes[track] = sizes
        }

        val moov: Box = createMovieBox(currentMp4Movie)
        moov.getBox(fc)

        fos.flush()
        fc.close()
        fos.close()
    }

    private fun createFileTypeBox(): FileTypeBox {
        // completed list can be found at https://www.ftyps.com/
        val compatibleBrands = listOf(
            "isom", "iso2", "mp41"
        )

        return FileTypeBox("isom", 0x200, compatibleBrands)
    }

    private fun gcd(a: Long, b: Long): Long {
        return if (b == 0L) a
        else gcd(b, a % b)
    }

    private fun getTimescale(mp4Movie: Mp4Movie): Long {
        var timescale: Long = 0
        if (mp4Movie.getTracks().isNotEmpty()) {
            timescale = mp4Movie.getTracks().iterator().next().getTimeScale().toLong()
        }

        for (track in mp4Movie.getTracks()) {
            timescale = gcd(
                track.getTimeScale().toLong(),
                timescale
            )
        }

        return timescale
    }

    private fun createMovieBox(movie: Mp4Movie): MovieBox {
        val movieBox = MovieBox()
        val mvhd = MovieHeaderBox()

        mvhd.apply {
            creationTime = Date()
            modificationTime = Date()
            matrix = Matrix.ROTATE_0
        }

        val movieTimeScale = getTimescale(movie)
        var duration: Long = 0

        for (track in movie.getTracks()) {
            val tracksDuration = track.getDuration() * movieTimeScale / track.getTimeScale()
            if (tracksDuration > duration) {
                duration = tracksDuration
            }
        }

        mvhd.duration = duration
        mvhd.timescale = movieTimeScale
        mvhd.nextTrackId = (movie.getTracks().size + 1).toLong()
        movieBox.addBox(mvhd)

        for (track in movie.getTracks()) {
            movieBox.addBox(createTrackBox(track, movie))
        }

        return movieBox
    }

    private fun createTrackBox(track: Track, movie: Mp4Movie): TrackBox {
        val trackBox = TrackBox()
        val tkhd = TrackHeaderBox()
        tkhd.apply {
            isEnabled = true
            isInPreview = true
            isInMovie = true
            matrix = if (track.isAudio()) {
                Matrix.ROTATE_0
            } else {
                movie.getMatrix()
            }
            alternateGroup = 0
            creationTime = track.getCreationTime()
            duration = track.getDuration() * getTimescale(movie) / track.getTimeScale()
            height = track.getHeight().toDouble()
            width = track.getWidth().toDouble()
            layer = 0
            modificationTime = Date()
            trackId = track.getTrackId() + 1
            volume = track.getVolume()
        }
        trackBox.addBox(tkhd)

        val mdia = MediaBox()
        trackBox.addBox(mdia)

        val mdhd = MediaHeaderBox()
        mdhd.apply {
            creationTime = track.getCreationTime()
            duration = track.getDuration()
            timescale = track.getTimeScale().toLong()
            language = "eng"
        }
        mdia.addBox(mdhd)

        val hdlr = HandlerBox()
        hdlr.apply {
            name = if (track.isAudio()) "SoundHandle" else "VideoHandle"
            handlerType = track.getHandler()
        }
        mdia.addBox(hdlr)

        val minf = MediaInformationBox()
        when {
            track.getHandler() == "vide" -> {
                minf.addBox(VideoMediaHeaderBox())
            }

            track.getHandler() == "soun" -> {
                minf.addBox(SoundMediaHeaderBox())
            }

            track.getHandler() == "text" -> {
                minf.addBox(NullMediaHeaderBox())
            }

            track.getHandler() == "subt" -> {
                minf.addBox(SubtitleMediaHeaderBox())
            }

            track.getHandler() == "hint" -> {
                minf.addBox(HintMediaHeaderBox())
            }

            track.getHandler() == "sbtl" -> {
                minf.addBox(NullMediaHeaderBox())
            }
        }

        val dinf = DataInformationBox()
        val dref = DataReferenceBox()
        dinf.addBox(dref)

        val url = DataEntryUrlBox()
        url.flags = 1

        dref.addBox(url)
        minf.addBox(dinf)

        val stbl: Box = createStbl(track)
        minf.addBox(stbl)
        mdia.addBox(minf)

        return trackBox
    }

    private fun createStbl(track: Track): Box {
        val stbl = SampleTableBox()
        createStsd(track, stbl)
        createStts(track, stbl)
        createStss(track, stbl)
        // --- Add CompositionTimeToSampleBox (ctts) ---
        // This ensures playback on Apple decoders doesn’t show black video
        try {
            val sampleCount = track.getSamples().size
            val entries = mutableListOf<CompositionTimeToSample.Entry>()

            // If you don't have separate PTS offsets, just use offset=0 for all samples.
            // This still creates a valid ctts box required by many players.
            entries.add(CompositionTimeToSample.Entry(sampleCount, 0))

            val ctts = CompositionTimeToSample()
            ctts.entries = entries
            stbl.addBox(ctts)

            android.util.Log.d("MP4Builder", "Added ctts box with ${entries.size} entries")
        } catch (e: Exception) {
            android.util.Log.e("MP4Builder", "Failed to add ctts box", e)
        }
        createStsc(track, stbl)
        createStsz(track, stbl)
        createStco(track, stbl)
        return stbl
    }

    private fun createStsd(track: Track, stbl: SampleTableBox) {
        stbl.addBox(track.getSampleDescriptionBox())
    }

    private fun createStts(track: Track, stbl: SampleTableBox) {
        var lastEntry: TimeToSampleBox.Entry? = null
        val entries: MutableList<TimeToSampleBox.Entry> = ArrayList()
        for (delta in track.getSampleDurations()) {
            if (lastEntry != null && lastEntry.delta == delta) {
                lastEntry.count = lastEntry.count + 1
            } else {
                lastEntry = TimeToSampleBox.Entry(1, delta)
                entries.add(lastEntry)
            }
        }
        val stts = TimeToSampleBox()
        stts.entries = entries
        stbl.addBox(stts)
    }

    private fun createStss(track: Track, stbl: SampleTableBox) {
        val syncSamples = track.getSyncSamples()
        if (syncSamples != null && syncSamples.isNotEmpty()) {
            val stss = SyncSampleBox()
            stss.sampleNumber = syncSamples
            stbl.addBox(stss)
        }
    }

    private fun createStsc(track: Track, stbl: SampleTableBox) {
        val stsc = SampleToChunkBox()
        stsc.entries = LinkedList()

        var lastOffset: Long
        var lastChunkNumber = 1
        var lastSampleCount = 0
        var previousWrittenChunkCount = -1

        val samplesCount = track.getSamples().size
        for (a in 0 until samplesCount) {
            val sample = track.getSamples()[a]
            val offset = sample.offset
            val size = sample.size

            lastOffset = offset + size
            lastSampleCount++

            var write = false
            if (a != samplesCount - 1) {
                val nextSample = track.getSamples()[a + 1]
                if (lastOffset != nextSample.offset) {
                    write = true
                }
            } else {
                write = true
            }

            if (write) {
                if (previousWrittenChunkCount != lastSampleCount) {
                    stsc.entries.add(
                        SampleToChunkBox.Entry(
                            lastChunkNumber.toLong(),
                            lastSampleCount.toLong(), 1
                        )
                    )
                    previousWrittenChunkCount = lastSampleCount
                }
                lastSampleCount = 0
                lastChunkNumber++
            }
        }
        stbl.addBox(stsc)
    }

    private fun createStsz(track: Track, stbl: SampleTableBox) {
        val stsz = SampleSizeBox()
        stsz.sampleSizes = track2SampleSizes[track]
        stbl.addBox(stsz)
    }

    private fun createStco(track: Track, stbl: SampleTableBox) {
        val chunksOffsets = ArrayList<Long>()
        var lastOffset: Long = -1
        for (sample in track.getSamples()) {
            val offset = sample.offset
            if (lastOffset != -1L && lastOffset != offset) {
                lastOffset = -1
            }
            if (lastOffset == -1L) {
                chunksOffsets.add(offset)
            }
            lastOffset = offset + sample.size
        }
        val chunkOffsetsLong = LongArray(chunksOffsets.size)
        for (a in chunksOffsets.indices) {
            chunkOffsetsLong[a] = chunksOffsets[a]
        }
        val stco = StaticChunkOffsetBox()
        stco.chunkOffsets = chunkOffsetsLong
        stbl.addBox(stco)
    }
}
