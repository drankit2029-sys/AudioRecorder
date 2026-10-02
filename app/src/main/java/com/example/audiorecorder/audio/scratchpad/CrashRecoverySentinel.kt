package com.example.audiorecorder.audio.scratchpad

import android.content.Context
import com.example.audiorecorder.audio.codec.WavHeaderWriter
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

data class SessionMetadata(
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val timestamp: Long,
    val sourceTitle: String
)

class CrashRecoverySentinel(private val context: Context) {

    private val stagingDir: File
        get() = File(context.filesDir, "staging").apply { if (!exists()) mkdirs() }

    val rawFile: File
        get() = File(stagingDir, "staging_session.raw")

    val metaFile: File
        get() = File(stagingDir, "staging_session.json")

    /**
     * Persists atomic session configuration to disk on recording start.
     */
    fun saveSessionState(sampleRate: Int, channels: Int, bitDepth: Int = 32, title: String = "Recovered Recording") {
        try {
            val json = JSONObject().apply {
                put("sampleRate", sampleRate)
                put("channels", channels)
                put("bitDepth", bitDepth)
                put("timestamp", System.currentTimeMillis())
                put("sourceTitle", title)
            }
            metaFile.writeText(json.toString())
        } catch (_: Exception) {}
    }

    /**
     * Checks if an uncommitted recording session exists from an unexpected termination.
     */
    fun hasOrphanedSession(): Boolean {
        return rawFile.exists() && rawFile.length() > 0L && metaFile.exists()
    }

    /**
     * Reads saved session configuration.
     */
    fun getSessionMetadata(): SessionMetadata? {
        if (!metaFile.exists()) return null
        return try {
            val json = JSONObject(metaFile.readText())
            SessionMetadata(
                sampleRate = json.optInt("sampleRate", 48000),
                channels = json.optInt("channels", 2),
                bitDepth = json.optInt("bitDepth", 32),
                timestamp = json.optLong("timestamp", System.currentTimeMillis()),
                sourceTitle = json.optString("sourceTitle", "Recovered Recording")
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Converts orphaned raw PCM float data into a fully headered WAV file in target directory.
     */
    fun recoverSession(destinationWav: File): Boolean {
        val meta = getSessionMetadata() ?: return false
        if (!rawFile.exists() || rawFile.length() == 0L) return false

        try {
            val totalBytes = rawFile.length()
            FileOutputStream(destinationWav).use { fos ->
                WavHeaderWriter.writeHeader(
                    out = fos,
                    totalAudioBytes = totalBytes,
                    sampleRate = meta.sampleRate,
                    channels = meta.channels,
                    bitDepth = meta.bitDepth
                )
                FileInputStream(rawFile).use { fis ->
                    val buffer = ByteArray(64 * 1024)
                    var read: Int
                    while (fis.read(buffer).also { read = it } != -1) {
                        fos.write(buffer, 0, read)
                    }
                }
            }
            clearSession()
            return true
        } catch (_: Exception) {
            return false
        }
    }

    /**
     * Clears staging directory after successful session commitment or intentional discard.
     */
    fun clearSession() {
        try {
            if (rawFile.exists()) rawFile.delete()
            if (metaFile.exists()) metaFile.delete()
            val tailCache = File(stagingDir, "tail_cache.raw")
            if (tailCache.exists()) tailCache.delete()
        } catch (_: Exception) {}
    }
}
