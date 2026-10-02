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

    override fun onAudioFrame(peakDbfs: Float, rmsDbfs: Float, peakLinear: Float) {
        serviceListener?.onAudioFrame(peakDbfs, rmsDbfs, peakLinear)
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
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
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
