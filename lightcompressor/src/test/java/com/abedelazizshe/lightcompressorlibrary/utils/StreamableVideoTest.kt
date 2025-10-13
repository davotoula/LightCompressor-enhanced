package com.abedelazizshe.lightcompressorlibrary.utils

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StreamableVideoTest {

    @Test
    fun movesMoovAheadAndAdjustsOffsetsPerOriginalLocation() {
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.wtf(any<String>(), any<String>()) } returns 0

        try {
            val input = File.createTempFile("streamable_in", ".mp4")
            val output = File.createTempFile("streamable_out", ".mp4")
            input.deleteOnExit()
            output.deleteOnExit()

            val ftyp = atom("ftyp", ByteArray(4) { 0x01.toByte() })
            val audioPayload = ByteArray(8) { 0x0A }
            val audioMdat = atom("mdat", audioPayload)
            val stcoOffsets = intArrayOf(
                ftyp.size + ATOM_HEADER_SIZE,
                ftyp.size + audioMdat.size + computeMoovSize(entryCount = 2) + ATOM_HEADER_SIZE
            )
            val moov = atom(
                "moov",
                stcoAtom(stcoOffsets)
            )
            val videoPayload = ByteArray(8) { 0x0B }
            val videoMdat = atom("mdat", videoPayload)
            val moovSize = moov.size

            val sourceBytes = ftyp + audioMdat + moov + videoMdat
            input.writeBytes(sourceBytes)

            val conversionResult = StreamableVideo.start(input, output)
            assertTrue("Conversion should have moved moov atom", conversionResult)

            val resultBytes = output.readBytes()
            val topLevelAtoms = parseAtoms(resultBytes)
            assertEquals(listOf("ftyp", "moov", "mdat", "mdat"), topLevelAtoms.map { it.type })

            val expectedMoovOffset = ftyp.size
            val expectedAudioMdatOffset = expectedMoovOffset + moov.size
            assertEquals(expectedMoovOffset, topLevelAtoms[1].offset)
            assertEquals(expectedAudioMdatOffset, topLevelAtoms[2].offset)

            val stcoEntries = readStcoEntries(resultBytes, topLevelAtoms[1].offset)
            val expectedAudioOffset = ftyp.size + moovSize + ATOM_HEADER_SIZE
            val expectedVideoOffset = ftyp.size + audioMdat.size + moovSize + ATOM_HEADER_SIZE
            assertEquals(listOf(expectedAudioOffset, expectedVideoOffset), stcoEntries)
        } finally {
            unmockkStatic(Log::class)
        }
    }

    private data class Atom(val type: String, val offset: Int, val size: Int)

    private fun atom(type: String, payload: ByteArray = byteArrayOf()): ByteArray {
        require(type.length == 4) { "Atom type must be exactly 4 characters" }
        val size = ATOM_HEADER_SIZE + payload.size
        val buffer = ByteBuffer.allocate(size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(size)
        buffer.put(type.toByteArray(Charsets.US_ASCII))
        if (payload.isNotEmpty()) {
            buffer.put(payload)
        }
        return buffer.array()
    }

    private fun stcoAtom(offsets: IntArray): ByteArray {
        val entries = offsets.size
        val payloadSize = 4 + 4 + entries * 4 // version/flags + entry count + entries
        val buffer = ByteBuffer.allocate(ATOM_HEADER_SIZE + payloadSize)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(ATOM_HEADER_SIZE + payloadSize)
        buffer.put("stco".toByteArray(Charsets.US_ASCII))
        buffer.putInt(0) // version(1) + flags(3)
        buffer.putInt(entries)
        offsets.forEach { buffer.putInt(it) }
        return buffer.array()
    }

    private fun parseAtoms(bytes: ByteArray): List<Atom> {
        val atoms = mutableListOf<Atom>()
        var offset = 0
        while (offset + ATOM_HEADER_SIZE <= bytes.size) {
            val size = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
            if (size <= 0 || offset + size > bytes.size) break
            val typeBytes = bytes.copyOfRange(offset + 4, offset + 8)
            val type = typeBytes.toString(Charsets.US_ASCII)
            atoms += Atom(type, offset, size)
            offset += size
        }
        return atoms
    }

    private fun readStcoEntries(bytes: ByteArray, moovOffset: Int): List<Int> {
        val moovSize = ByteBuffer.wrap(bytes, moovOffset, 4).order(ByteOrder.BIG_ENDIAN).int
        var cursor = moovOffset + ATOM_HEADER_SIZE
        val end = moovOffset + moovSize
        while (cursor + ATOM_HEADER_SIZE <= end) {
            val size = ByteBuffer.wrap(bytes, cursor, 4).order(ByteOrder.BIG_ENDIAN).int
            if (size <= 0 || cursor + size > end) {
                cursor++
                continue
            }
            val type = String(bytes, cursor + 4, 4, Charsets.US_ASCII)
            if (type == "stco") {
                val payloadStart = cursor + ATOM_HEADER_SIZE
                val entryCount = ByteBuffer.wrap(bytes, payloadStart + 4, 4)
                    .order(ByteOrder.BIG_ENDIAN)
                    .int
                if (entryCount <= 0) {
                    break
                }
                val entries = mutableListOf<Int>()
                var entryCursor = payloadStart + 8
                repeat(entryCount) {
                    entries += ByteBuffer.wrap(bytes, entryCursor, 4)
                        .order(ByteOrder.BIG_ENDIAN)
                        .int
                    entryCursor += 4
                }
                return entries
            }
            cursor++
        }
        return emptyList()
    }

    private companion object {
        const val ATOM_HEADER_SIZE = 8

        private fun computeMoovSize(entryCount: Int): Int {
            val stcoPayload = 4 + 4 + entryCount * 4
            val stcoSize = ATOM_HEADER_SIZE + stcoPayload
            return ATOM_HEADER_SIZE + stcoSize
        }
    }
}
