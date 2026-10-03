package com.studio.audio.core.audio

import android.content.Context
import org.json.JSONObject
import java.io.File

data class SavedRecording(
    val file: File,
    val name: String,
    val durationSeconds: Long,
    val sizeBytes: Long,
    val lastModified: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val formatLabel: String
)

data class InterruptedSession(
    val audioFile: File,
    val sampleRate: Int,
    val channelCount: Int,
    val bytesWritten: Long,
    val durationSeconds: Long
)

class SessionRecoveryManager(private val context: Context) {

    private val recordingsDir: File
        get() = File(context.filesDir, "recordings").apply { if (!exists()) mkdirs() }

    private val recoveryDescriptorFile: File
        get() = File(recordingsDir, "active_session.json")

    fun getRecordingsDirectory(): File = recordingsDir

    fun createNewTakeFile(): File {
        return File(recordingsDir, "temp_take_${System.currentTimeMillis()}.pcm")
    }

    @Synchronized
    fun markSessionActive(file: File, sampleRate: Int, channelCount: Int) {
        val json = JSONObject().apply {
            put("filePath", file.absolutePath)
            put("sampleRate", sampleRate)
            put("channelCount", channelCount)
            put("timestamp", System.currentTimeMillis())
        }
        recoveryDescriptorFile.writeText(json.toString())
    }

    @Synchronized
    fun markSessionCompleted() {
        if (recoveryDescriptorFile.exists()) {
            recoveryDescriptorFile.delete()
        }
    }

    @Synchronized
    fun commitRecording(
        tempFile: File,
        userTitle: String,
        sampleRate: Int,
        channelCount: Int,
        preset: AudioPreset
    ): File {
        markSessionCompleted()

        val sanitizedTitle = userTitle.trim().replace(Regex("[^a-zA-Z0-9._ -]"), "_").ifBlank {
            "Take_${System.currentTimeMillis()}"
        }

        val extension = preset.format.extension
        var destination = File(recordingsDir, "$sanitizedTitle.$extension")
        var counter = 1
        while (destination.exists()) {
            destination = File(recordingsDir, "${sanitizedTitle}_($counter).$extension")
            counter++
        }

        // Convert the 32-bit Float PCM to the preset format (WAV 16/24/32 float or AAC)
        val success = AudioConverter.convertPcmFloatToPreset(
            inputFile = tempFile,
            outputFile = destination,
            sampleRate = sampleRate,
            channelCount = channelCount,
            preset = preset
        )

        // Delete temporary float file once converted
        tempFile.delete()

        return if (success) destination else tempFile
    }

    @Synchronized
    fun getSavedRecordings(): List<SavedRecording> {
        val files = recordingsDir.listFiles { file ->
            file.isFile &&
                !file.name.startsWith("temp_take_") &&
                file.name != "active_session.json"
        } ?: emptyArray()

        return files.map { file ->
            parseSavedRecording(file)
        }.sortedByDescending { it.lastModified }
    }

    private fun parseSavedRecording(file: File): SavedRecording {
        var durationSec = 0L
        var sampleRate = 48000
        var channelCount = 1
        var formatLabel = file.extension.uppercase()

        if (file.extension.equals("wav", ignoreCase = true) && file.length() >= 44L) {
            try {
                val raf = java.io.RandomAccessFile(file, "r")
                val header = ByteArray(44)
                raf.readFully(header)
                raf.close()

                val bb = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                val formatTag = bb.getShort(20).toInt()
                channelCount = bb.getShort(22).toInt()
                sampleRate = bb.getInt(24)
                val byteRate = bb.getInt(28)
                val bitsPerSample = bb.getShort(34).toInt()

                if (byteRate > 0) {
                    durationSec = (file.length() - 44L) / byteRate
                }
                formatLabel = if (formatTag == 3) "32-bit Float WAV" else "$bitsPerSample-bit WAV"
            } catch (_: Exception) {}
        } else {
            // General estimate based on file size
            durationSec = file.length() / (48000 * 2)
        }

        return SavedRecording(
            file = file,
            name = file.nameWithoutExtension,
            durationSeconds = durationSec,
            sizeBytes = file.length(),
            lastModified = file.lastModified(),
            sampleRate = sampleRate,
            channelCount = channelCount,
            formatLabel = formatLabel
        )
    }

    @Synchronized
    fun deleteRecording(file: File): Boolean {
        return file.delete()
    }

    @Synchronized
    fun getInterruptedSession(): InterruptedSession? {
        if (!recoveryDescriptorFile.exists()) return null

        return try {
            val raw = recoveryDescriptorFile.readText()
            val json = JSONObject(raw)
            val filePath = json.getString("filePath")
            val sampleRate = json.getInt("sampleRate")
            val channelCount = json.optInt("channelCount", 1)
            val file = File(filePath)

            if (file.exists() && file.length() > 0) {
                // Internal temp takes are ALWAYS 32-bit Float PCM = 4 bytes per sample per channel
                val bytesPerSec = sampleRate * channelCount * 4L
                val durationSec = if (bytesPerSec > 0) file.length() / bytesPerSec else 0L

                InterruptedSession(
                    audioFile = file,
                    sampleRate = sampleRate,
                    channelCount = channelCount,
                    bytesWritten = file.length(),
                    durationSeconds = durationSec
                )
            } else {
                markSessionCompleted()
                null
            }
        } catch (_: Exception) {
            markSessionCompleted()
            null
        }
    }

    @Synchronized
    fun discardInterruptedSession() {
        val session = getInterruptedSession()
        session?.audioFile?.delete()
        markSessionCompleted()
    }
}
