#!/bin/sh
set -e

BASE="app/src/main/java/com/example/audiorecorder"

echo "==> 1. Writing CrashRecoverySentinel.kt..."
cat << 'SENTINEL' > "$BASE/audio/scratchpad/CrashRecoverySentinel.kt"
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
SENTINEL

echo "==> 2. Writing AudioRecordingService.kt..."
cat << 'SERVICE' > "$BASE/audio/engine/AudioRecordingService.kt"
package com.example.audiorecorder.audio.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.audiorecorder.MainActivity
import com.example.audiorecorder.audio.hardware.AudioPreset
import com.example.audiorecorder.audio.hardware.DiscoveredMic
import com.example.audiorecorder.audio.scratchpad.CrashRecoverySentinel
import com.example.audiorecorder.audio.scratchpad.ScratchpadManager
import com.example.audiorecorder.util.TimecodeFormatter
import java.io.File

class AudioRecordingService : Service(), AudioCaptureListener {

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var scratchpadManager: ScratchpadManager
    private lateinit var captureEngine: AudioCaptureEngine
    private lateinit var crashSentinel: CrashRecoverySentinel
    private lateinit var playbackEngine: AudioPlaybackEngine

    private var activePreset: AudioPreset = AudioPreset.STANDARD_PODCAST
    private var activeMic: DiscoveredMic? = null
    private var startTimeMillis = 0L

    var serviceListener: AudioCaptureListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioRecordingService = this@AudioRecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        crashSentinel = CrashRecoverySentinel(this)
        scratchpadManager = ScratchpadManager(crashSentinel.rawFile)
        captureEngine = AudioCaptureEngine(scratchpadManager, this)
        playbackEngine = AudioPlaybackEngine(this)

        acquireWakeLock()
        createNotificationChannel()
    }

    fun startRecording(preset: AudioPreset, mic: DiscoveredMic?): Boolean {
        activePreset = preset
        activeMic = mic
        startTimeMillis = System.currentTimeMillis()

        crashSentinel.saveSessionState(
            sampleRate = preset.sampleRate,
            channels = preset.channels,
            bitDepth = preset.bitDepth
        )

        val success = captureEngine.startCapture(preset.sampleRate, preset.channels, mic)
        if (success) {
            val notification = buildNotification("Recording active...", "00:00.000")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return success
    }

    fun pauseRecording() {
        captureEngine.pauseCapture()
        updateNotification("Recording paused", TimecodeFormatter.formatMillis(getElapsedMillis()))
    }

    fun resumeRecording() {
        captureEngine.resumeCapture()
        updateNotification("Recording active...", TimecodeFormatter.formatMillis(getElapsedMillis()))
    }

    fun stopRecording(): File {
        captureEngine.stopCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        return crashSentinel.rawFile
    }

    fun getElapsedMillis(): Long {
        val totalBytes = scratchpadManager.getTotalAudioBytes()
        val totalSamples = totalBytes / (activePreset.channels * 4L)
        return TimecodeFormatter.samplesToMillis(totalSamples, activePreset.sampleRate)
    }

    fun getScratchpadManager(): ScratchpadManager = scratchpadManager
    fun getPlaybackEngine(): AudioPlaybackEngine = playbackEngine
    fun getCrashSentinel(): CrashRecoverySentinel = crashSentinel

    override fun onDbfsUpdate(peakDbfs: Float, rmsDbfs: Float) {
        serviceListener?.onDbfsUpdate(peakDbfs, rmsDbfs)
    }

    override fun onError(errorMessage: String) {
        serviceListener?.onError(errorMessage)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AudioRecorder::RecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L) // Safety cap: 12 hours
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Studio Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing audio studio recording process"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String, timeText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Studio ($status)")
            .setContentText("Duration: $timeText")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(status: String, timeText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status, timeText))
    }

    override fun onDestroy() {
        super.onDestroy()
        captureEngine.release()
        playbackEngine.release()
        releaseWakeLock()
    }

    companion object {
        const val CHANNEL_ID = "recording_service_channel"
        const val NOTIFICATION_ID = 4041
    }
}
SERVICE

echo "==> 3. Registering AudioRecordingService in AndroidManifest.xml..."
cat << 'MANIFEST' > app/src/main/AndroidManifest.xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />

    <application
        android:allowBackup="true"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.AudioRecorder">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:screenOrientation="portrait">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".audio.engine.AudioRecordingService"
            android:enabled="true"
            android:exported="false"
            android:foregroundServiceType="microphone" />

        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

    </application>

</manifest>
MANIFEST

echo "==> Step 5 generated successfully!"
