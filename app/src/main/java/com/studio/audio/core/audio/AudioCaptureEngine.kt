package com.studio.audio.core.audio

import android.annotation.SuppressLint
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

sealed class RecordingError {
    data class InitializationFailed(val message: String) : RecordingError()
    data class ReadError(val code: Int, val message: String) : RecordingError()
    data class DeviceDisconnected(val deviceName: String) : RecordingError()
}

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
        destinationFile: File,
        append: Boolean = false,
        onError: (RecordingError) -> Unit
    ) {
        if (isRecording.get()) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_FLOAT

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        if (minBufferSize <= 0) {
            onError(RecordingError.InitializationFailed("Invalid audio hardware parameters: rate=$sampleRate"))
            return
        }

        val bufferSizeBytes = (minBufferSize * 2).coerceAtLeast(8192)

        // Bluetooth SCO / BLE requires VOICE_COMMUNICATION on Android
        val isBluetooth = targetDevice?.let {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: false

        val audioSource = if (isBluetooth) {
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        } else {
            MediaRecorder.AudioSource.MIC
        }

        val record = try {
            AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()
        } catch (e: Exception) {
            onError(RecordingError.InitializationFailed(e.message ?: "Failed to allocate AudioRecord"))
            return
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError(RecordingError.InitializationFailed("AudioRecord returned STATE_UNINITIALIZED from hardware HAL"))
            return
        }

        if (targetDevice != null) {
            record.preferredDevice = targetDevice
        }
        currentDevice = targetDevice

        audioRecord = record
        diskWriter.start(destinationFile, append = append)
        isRecording.set(true)

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            audioRecord = null
            isRecording.set(false)
            onError(RecordingError.InitializationFailed("startRecording() failed: microphone resource locked"))
            return
        }

        recordingJob = scope.launch(Dispatchers.IO) {
            val byteBuffer = ByteBuffer.allocateDirect(bufferSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
            val byteArray = ByteArray(bufferSizeBytes)

            while (isRecording.get() && isActive) {
                byteBuffer.clear()
                val bytesRead = record.read(byteBuffer, bufferSizeBytes, AudioRecord.READ_BLOCKING)

                if (bytesRead > 0) {
                    byteBuffer.position(0)
                    byteBuffer.get(byteArray, 0, bytesRead)
                    diskWriter.write(byteArray, 0, bytesRead)
                } else if (bytesRead < 0) {
                    val errorMsg = when (bytesRead) {
                        AudioRecord.ERROR_INVALID_OPERATION -> "AudioRecord state corrupt (INVALID_OPERATION)"
                        AudioRecord.ERROR_BAD_VALUE -> "Bad parameter passed to HAL (BAD_VALUE)"
                        AudioRecord.ERROR_DEAD_OBJECT -> "Audio HAL died or client preempted (DEAD_OBJECT)"
                        else -> "AudioRecord hardware read failed with code $bytesRead"
                    }
                    withContext(Dispatchers.Main) {
                        onError(RecordingError.ReadError(bytesRead, errorMsg))
                    }
                    break
                }
            }
        }
    }

    fun switchDevice(newDevice: AudioDeviceInfo?): Boolean {
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
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
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