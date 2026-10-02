package com.studio.audio.core.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AudioCaptureEngine(
    private val diskWriter: AudioDiskWriter
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val isRecording = AtomicBoolean(false)

    var currentDevice: AudioDeviceInfo? = null
        private set

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        targetDevice: AudioDeviceInfo?,
        sampleRate: Int = 48000,
        destinationFile: File
    ) {
        if (isRecording.get()) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(4096)

        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (targetDevice != null) {
            record.preferredDevice = targetDevice
            currentDevice = targetDevice
        }

        audioRecord = record
        diskWriter.start(destinationFile)
        isRecording.set(true)
        record.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            val audioBuffer = ByteArray(bufferSize)
            while (isRecording.get() && isActive) {
                val bytesRead = record.read(audioBuffer, 0, audioBuffer.size)
                if (bytesRead > 0) {
                    diskWriter.write(audioBuffer, 0, bytesRead)
                }
            }
        }
    }

    /**
     * Dynamically switches the hardware mic input without dropping the recording session.
     */
    fun switchDevice(newDevice: AudioDeviceInfo): Boolean {
        currentDevice = newDevice
        val record = audioRecord ?: return false
        return record.setPreferredDevice(newDevice)
    }

    fun stopRecording(): File? {
        if (!isRecording.get()) return null
        isRecording.set(false)
        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        audioRecord = null
        return diskWriter.stop()
    }

    fun isCurrentlyRecording(): Boolean = isRecording.get()
}