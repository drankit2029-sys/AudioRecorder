package com.example.audiorecorder.audio.codec

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavHeaderWriter {
    const val FORMAT_PCM = 0x0001.toShort()
    const val FORMAT_IEEE_FLOAT = 0x0003.toShort()

    /**
     * Writes standard 44-byte RIFF header for PCM (16/24-bit) or IEEE Float (32-bit).
     */
    fun writeHeader(
        out: OutputStream,
        totalAudioBytes: Long,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int
    ) {
        val isFloat = bitDepth == 32
        val formatTag = if (isFloat) FORMAT_IEEE_FLOAT else FORMAT_PCM
        val byteRate = sampleRate * channels * (bitDepth / 8)
        val blockAlign = (channels * (bitDepth / 8)).toShort()
        val totalDataLen = totalAudioBytes + 36

        val header = ByteBuffer.allocate(44).apply {
            order(ByteOrder.LITTLE_ENDIAN)

            // "RIFF"
            put('R'.code.toByte()); put('I'.code.toByte()); put('F'.code.toByte()); put('F'.code.toByte())
            putInt(totalDataLen.toInt())
            // "WAVE"
            put('W'.code.toByte()); put('A'.code.toByte()); put('V'.code.toByte()); put('E'.code.toByte())
            // "fmt "
            put('f'.code.toByte()); put('m'.code.toByte()); put('t'.code.toByte()); put(' '.code.toByte())
            putInt(16) // Subchunk1Size for PCM
            putShort(formatTag)
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign)
            putShort(bitDepth.toShort())
            // "data"
            put('d'.code.toByte()); put('a'.code.toByte()); put('t'.code.toByte()); put('a'.code.toByte())
            putInt(totalAudioBytes.toInt())
        }

        out.write(header.array())
    }
}
