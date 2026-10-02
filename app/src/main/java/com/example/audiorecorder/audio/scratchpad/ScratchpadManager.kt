package com.example.audiorecorder.audio.scratchpad

import com.example.audiorecorder.audio.codec.WavHeaderWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

class ScratchpadManager(val scratchFile: File) {

    private val tailCacheFile = File(scratchFile.parentFile ?: File("."), "tail_cache.raw")
    private var randomAccessFile: RandomAccessFile? = null
    private var byteBuffer: ByteBuffer? = null
    var isTailShelved = false
        private set

    @Synchronized
    fun openSession() {
        scratchFile.parentFile?.mkdirs()
        if (randomAccessFile == null) {
            randomAccessFile = RandomAccessFile(scratchFile, "rw")
        }
    }

    /**
     * In-place seek to a sample without resetting file structure.
     */
    @Synchronized
    fun seekToSample(sampleIndex: Long, channels: Int) {
        openSession()
        val raf = randomAccessFile ?: return
        val byteOffset = sampleIndex * channels * 4L
        if (byteOffset <= raf.length()) {
            raf.seek(byteOffset)
        }
    }

    /**
     * Shelves downstream audio from [punchOutSample] to EOF into tail cache.
     * Truncates file at [punchInSample] so new incoming audio streams continuously.
     */
    @Synchronized
    fun prepareRangeReplacement(punchInSample: Long, punchOutSample: Long, channels: Int) {
        openSession()
        val raf = randomAccessFile ?: return
        val punchInByte = punchInSample * channels * 4L
        val punchOutByte = punchOutSample * channels * 4L
        val totalBytes = raf.length()

        if (punchOutByte < totalBytes) {
            raf.seek(punchOutByte)
            FileOutputStream(tailCacheFile).use { fos ->
                val buffer = ByteArray(64 * 1024)
                var bytesRemaining = totalBytes - punchOutByte
                while (bytesRemaining > 0) {
                    val read = raf.read(buffer, 0, min(buffer.size.toLong(), bytesRemaining).toInt())
                    if (read == -1) break
                    fos.write(buffer, 0, read)
                    bytesRemaining -= read
                }
            }
            isTailShelved = true
        } else {
            isTailShelved = false
        }

        raf.setLength(punchInByte)
        raf.seek(punchInByte)
    }

    @Synchronized
    fun writeFloats(floats: FloatArray, count: Int) {
        val raf = randomAccessFile ?: return
        val requiredBytes = count * 4

        val currentBuf = byteBuffer
        val targetBuffer: ByteBuffer = if (currentBuf == null || currentBuf.capacity() < requiredBytes) {
            val newBuf = ByteBuffer.allocateDirect(requiredBytes).order(ByteOrder.LITTLE_ENDIAN)
            byteBuffer = newBuf
            newBuf
        } else {
            currentBuf.clear()
            currentBuf
        }

        for (i in 0 until count) {
            targetBuffer.putFloat(floats[i])
        }

        val array = ByteArray(requiredBytes)
        targetBuffer.position(0)
        targetBuffer.get(array)
        raf.write(array)
    }

    @Synchronized
    fun spliceTailBack(sampleRate: Int, channels: Int, crossfadeMs: Int = 5) {
        if (!isTailShelved || !tailCacheFile.exists() || tailCacheFile.length() == 0L) {
            isTailShelved = false
            return
        }

        val raf = randomAccessFile ?: return
        val crossfadeSamples = (sampleRate * crossfadeMs) / 1000
        val crossfadeBytes = crossfadeSamples * channels * 4

        FileInputStream(tailCacheFile).use { tailStream ->
            if (raf.length() >= crossfadeBytes && tailCacheFile.length() >= crossfadeBytes) {
                applyBoundaryCrossfade(raf, tailStream, crossfadeSamples, channels)
            }

            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (tailStream.read(buffer).also { read = it } != -1) {
                raf.write(buffer, 0, read)
            }
        }

        tailCacheFile.delete()
        isTailShelved = false
    }

    private fun applyBoundaryCrossfade(
        targetRaf: RandomAccessFile,
        tailStream: FileInputStream,
        crossfadeSamples: Int,
        channels: Int
    ) {
        val totalFloats = crossfadeSamples * channels
        val byteCount = totalFloats * 4

        val targetStartOffset = targetRaf.length() - byteCount
        targetRaf.seek(targetStartOffset)
        val endBytes = ByteArray(byteCount)
        targetRaf.readFully(endBytes)

        val tailBytes = ByteArray(byteCount)
        var totalRead = 0
        while (totalRead < byteCount) {
            val r = tailStream.read(tailBytes, totalRead, byteCount - totalRead)
            if (r == -1) break
            totalRead += r
        }

        val endBuf = ByteBuffer.wrap(endBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val tailBuf = ByteBuffer.wrap(tailBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

        val blendedArray = ByteArray(byteCount)
        val blendedBuf = ByteBuffer.wrap(blendedArray).order(ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until crossfadeSamples) {
            val alpha = i.toFloat() / crossfadeSamples.toFloat()
            for (ch in 0 until channels) {
                val sampleEnd = endBuf.get()
                val sampleTail = tailBuf.get()
                val blended = (sampleEnd * (1.0f - alpha)) + (sampleTail * alpha)
                blendedBuf.putFloat(blended)
            }
        }

        targetRaf.seek(targetStartOffset)
        targetRaf.write(blendedArray)
    }

    fun sync() {
        randomAccessFile?.fd?.sync()
    }

    fun getTotalAudioBytes(): Long {
        return randomAccessFile?.length() ?: scratchFile.length()
    }

    fun close() {
        try {
            sync()
            randomAccessFile?.close()
        } catch (_: Exception) {}
        randomAccessFile = null
    }

    fun exportToFloatWav(destinationWav: File, sampleRate: Int, channels: Int) {
        close()
        val totalBytes = scratchFile.length()
        FileOutputStream(destinationWav).use { fos ->
            WavHeaderWriter.writeHeader(
                out = fos,
                totalAudioBytes = totalBytes,
                sampleRate = sampleRate,
                channels = channels,
                bitDepth = 32
            )
            FileInputStream(scratchFile).use { fis ->
                val buffer = ByteArray(64 * 1024)
                var read: Int
                while (fis.read(buffer).also { read = it } != -1) {
                    fos.write(buffer, 0, read)
                }
            }
        }
    }
}
