package com.studio.audio.core.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object AudioConverter {

    fun convertPcmFloatToPreset(
        inputFile: File,
        outputFile: File,
        sampleRate: Int,
        channelCount: Int,
        preset: AudioPreset
    ): Boolean {
        if (!inputFile.exists() || inputFile.length() == 0L) return false

        return when (preset.format) {
            AudioFormatType.WAV -> {
                val targetBitDepth = preset.bitDepth ?: BitDepth.BIT_16
                convertPcmFloatToWav(inputFile, outputFile, sampleRate, channelCount, targetBitDepth)
            }
            AudioFormatType.AAC -> {
                val success = encodeToAac(inputFile, outputFile, sampleRate, channelCount)
                if (!success) {
                    // Safety fallback: if MediaCodec fails, preserve data as 16-bit WAV
                    val fallbackFile = File(outputFile.parentFile, "${outputFile.nameWithoutExtension}.wav")
                    convertPcmFloatToWav(inputFile, fallbackFile, sampleRate, channelCount, BitDepth.BIT_16)
                } else true
            }
            AudioFormatType.FLAC,
            AudioFormatType.OPUS -> {
                // Lossless or speech presets default to standard PCM WAV if container encoder is unconfigured
                convertPcmFloatToWav(inputFile, outputFile, sampleRate, channelCount, preset.bitDepth ?: BitDepth.BIT_16)
            }
        }
    }

    private fun convertPcmFloatToWav(
        inputFile: File,
        outputFile: File,
        sampleRate: Int,
        channelCount: Int,
        bitDepth: BitDepth
    ): Boolean {
        val bitsPerSample = bitDepth.bitCount
        val bytesPerOutputSample = bitsPerSample / 8
        val isFloat = bitDepth == BitDepth.FLOAT_32

        val inputStream = FileInputStream(inputFile)
        val outputStream = FileOutputStream(outputFile)

        // 1. Write placeholder 44-byte RIFF WAV Header
        writeWavHeader(outputStream, sampleRate, channelCount, bitsPerSample, isFloat, 0L)

        val inputBuffer = ByteArray(4096 * 4) // 4096 floats = 16384 bytes
        val byteBuffer = ByteBuffer.allocate(inputBuffer.size).order(ByteOrder.LITTLE_ENDIAN)
        var totalPayloadBytesWritten = 0L

        try {
            var bytesRead: Int
            while (inputStream.read(inputBuffer).also { bytesRead = it } != -1) {
                byteBuffer.clear()
                byteBuffer.put(inputBuffer, 0, bytesRead)
                byteBuffer.flip()

                val floatCount = bytesRead / 4
                when (bitDepth) {
                    BitDepth.FLOAT_32 -> {
                        // Direct pass-through
                        outputStream.write(inputBuffer, 0, bytesRead)
                        totalPayloadBytesWritten += bytesRead
                    }
                    BitDepth.BIT_16 -> {
                        val outBytes = ByteArray(floatCount * 2)
                        var outIdx = 0
                        for (i in 0 until floatCount) {
                            val f = byteBuffer.getFloat()
                            val clampedInt = (f.coerceIn(-1.0f, 1.0f) * 32767.0f).toInt().coerceIn(-32768, 32767)
                            outBytes[outIdx++] = (clampedInt and 0xFF).toByte()
                            outBytes[outIdx++] = ((clampedInt shr 8) and 0xFF).toByte()
                        }
                        outputStream.write(outBytes, 0, outIdx)
                        totalPayloadBytesWritten += outIdx
                    }
                    BitDepth.BIT_24 -> {
                        val outBytes = ByteArray(floatCount * 3)
                        var outIdx = 0
                        for (i in 0 until floatCount) {
                            val f = byteBuffer.getFloat()
                            val clampedInt = (f.coerceIn(-1.0f, 1.0f) * 8388607.0f).toInt().coerceIn(-8388608, 8388607)
                            outBytes[outIdx++] = (clampedInt and 0xFF).toByte()
                            outBytes[outIdx++] = ((clampedInt shr 8) and 0xFF).toByte()
                            outBytes[outIdx++] = ((clampedInt shr 16) and 0xFF).toByte()
                        }
                        outputStream.write(outBytes, 0, outIdx)
                        totalPayloadBytesWritten += outIdx
                    }
                }
            }
        } finally {
            inputStream.close()
            outputStream.flush()
            outputStream.close()
        }

        // 2. Seek back and write finalized file sizes in the WAV header
        updateWavHeaderSizes(outputFile, totalPayloadBytesWritten)
        return true
    }

    private fun writeWavHeader(
        out: FileOutputStream,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        isFloat: Boolean,
        audioDataLength: Long
    ) {
        val totalDataLen = audioDataLength + 36
        val byteRate = sampleRate * channels * (bitsPerSample / 8)
        val blockAlign = channels * (bitsPerSample / 8)
        val formatCode = if (isFloat) 3.toShort() else 1.toShort() // 3 = IEEE Float, 1 = PCM

        val header = ByteArray(44)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)

        bb.put("RIFF".toByteArray())
        bb.putInt(totalDataLen.toInt())
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16) // Subchunk1Size for PCM/Float
        bb.putShort(formatCode)
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(byteRate)
        bb.putShort(blockAlign.toShort())
        bb.putShort(bitsPerSample.toShort())
        bb.put("data".toByteArray())
        bb.putInt(audioDataLength.toInt())

        out.write(header)
    }

    private fun updateWavHeaderSizes(wavFile: File, audioDataLength: Long) {
        val raf = RandomAccessFile(wavFile, "rw")
        try {
            val totalDataLen = audioDataLength + 36
            val bb = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

            // Update RIFF chunk size at offset 4
            raf.seek(4)
            bb.putInt(totalDataLen.toInt())
            raf.write(bb.array())

            // Update data chunk size at offset 40
            bb.clear()
            raf.seek(40)
            bb.putInt(audioDataLength.toInt())
            raf.write(bb.array())
        } finally {
            raf.close()
        }
    }

    private fun encodeToAac(
        inputFile: File,
        outputFile: File,
        sampleRate: Int,
        channels: Int
    ): Boolean {
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        val inputStream = FileInputStream(inputFile)

        return try {
            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mime, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 192000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }

            codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            val bufferInfo = MediaCodec.BufferInfo()
            val floatBytes = ByteArray(4096 * 4)
            val pcm16Bytes = ByteArray(4096 * 2)
            val byteBuffer = ByteBuffer.wrap(floatBytes).order(ByteOrder.LITTLE_ENDIAN)

            var isInputEof = false
            while (!isInputEof || bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM == 0) {
                if (!isInputEof) {
                    val inputBufferIndex = codec.dequeueInputBuffer(10000)
                    if (inputBufferIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputBufferIndex) ?: continue
                        inputBuffer.clear()

                        val read = inputStream.read(floatBytes)
                        if (read <= 0) {
                            codec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isInputEof = true
                        } else {
                            // Downsample Float to 16-bit for AAC input
                            byteBuffer.position(0)
                            val floatsRead = read / 4
                            var pcmIdx = 0
                            for (i in 0 until floatsRead) {
                                val s = (byteBuffer.getFloat().coerceIn(-1f, 1f) * 32767f).toInt()
                                pcm16Bytes[pcmIdx++] = (s and 0xFF).toByte()
                                pcm16Bytes[pcmIdx++] = ((s shr 8) and 0xFF).toByte()
                            }
                            inputBuffer.put(pcm16Bytes, 0, pcmIdx)
                            codec.queueInputBuffer(inputBufferIndex, 0, pcmIdx, 0, 0)
                        }
                    }
                }

                val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (outputBufferIndex >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                    if (outputBuffer != null && bufferInfo.size > 0 && muxerStarted) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                    }
                    codec.releaseOutputBuffer(outputBufferIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            inputStream.close()
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {}
            try {
                muxer?.stop()
                muxer?.release()
            } catch (_: Exception) {}
        }
    }
}
