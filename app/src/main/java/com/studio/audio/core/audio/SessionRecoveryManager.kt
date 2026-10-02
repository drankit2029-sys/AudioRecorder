package com.studio.audio.core.audio

import android.content.Context
import org.json.JSONObject
import java.io.File

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
        return File(recordingsDir, "take_${System.currentTimeMillis()}.pcm")
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
    fun getInterruptedSession(): InterruptedSession? {
        if (!recoveryDescriptorFile.exists()) return null

        return try {
            val raw = recoveryDescriptorFile.readText()
            val json = JSONObject(raw)
            val filePath = json.getString("filePath")
            val sampleRate = json.getInt("sampleRate")
            val file = File(filePath)

            if (file.exists() && file.length() > 0) {
                // 16-bit Mono PCM: 2 bytes per sample
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