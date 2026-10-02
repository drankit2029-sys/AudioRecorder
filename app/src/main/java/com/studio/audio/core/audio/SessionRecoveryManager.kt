package com.studio.audio.core.audio

import android.content.Context
import org.json.JSONObject
import java.io.File

data class SavedRecording(
    val file: File,
    val name: String,
    val durationSeconds: Long,
    val sizeBytes: Long,
    val lastModified: Long
)

data class InterruptedSession(
    val audioFile: File,
    val sampleRate: Int,
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
    fun markSessionActive(file: File, sampleRate: Int) {
        val json = JSONObject().apply {
            put("filePath", file.absolutePath)
            put("sampleRate", sampleRate)
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
    fun commitRecording(tempFile: File, userTitle: String, sampleRate: Int = 48000): File {
        markSessionCompleted()
        val sanitizedTitle = userTitle.trim().replace(Regex("[^a-zA-Z0-9._ -]"), "_").ifBlank {
            "Take_${System.currentTimeMillis()}"
        }

        var destination = File(recordingsDir, "$sanitizedTitle.pcm")
        var counter = 1
        while (destination.exists()) {
            destination = File(recordingsDir, "${sanitizedTitle}_($counter).pcm")
            counter++
        }

        tempFile.renameTo(destination)
        return destination
    }

    @Synchronized
    fun getSavedRecordings(sampleRate: Int = 48000): List<SavedRecording> {
        val files = recordingsDir.listFiles { file ->
            file.isFile && file.extension == "pcm" && !file.name.startsWith("temp_take_")
        } ?: emptyArray()

        // 32-bit Float Mono = 4 bytes per sample
        val bytesPerSec = sampleRate * 4L

        return files.map { file ->
            val durationSec = if (bytesPerSec > 0) file.length() / bytesPerSec else 0L
            SavedRecording(
                file = file,
                name = file.nameWithoutExtension,
                durationSeconds = durationSec,
                sizeBytes = file.length(),
                lastModified = file.lastModified()
            )
        }.sortedByDescending { it.lastModified }
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
            val file = File(filePath)

            if (file.exists() && file.length() > 0) {
                val bytesPerSec = sampleRate * 4L
                val durationSec = file.length() / bytesPerSec
                InterruptedSession(
                    audioFile = file,
                    sampleRate = sampleRate,
                    bytesWritten = file.length(),
                    durationSeconds = durationSec
                )
            } else {
                markSessionCompleted()
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
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