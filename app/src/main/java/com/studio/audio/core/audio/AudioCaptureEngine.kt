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

        val isBluetooth = targetDevice?.let {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: false

        // Attempt hardware negotiation ladder
        val initResult = buildResilientAudioRecord(targetDevice, isBluetooth, sampleRate)
        if (initResult == null) {
            onError(RecordingError.InitializationFailed("AudioRecord returned STATE_UNINITIALIZED across all hardware fallback profiles"))
            return
        }

        val (record, hardwareEncoding, bufferSizeBytes) = initResult

        audioRecord = record
        currentDevice = targetDevice
        diskWriter.start(destinationFile, append = append)
        isRecording.set(true)

        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            audioRecord = null
            isRecording.set(false)
            onError(RecordingError.InitializationFailed("startRecording() failed: ${e.message}"))
            return
        }

        recordingJob = scope.launch(Dispatchers.IO) {
            if (hardwareEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                // Direct float capture
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
                        handleError(bytesRead, onError)
                        break
                    }
                }
            } else {
                // 16-bit capture -> Real-time conversion to 32-bit Float
                val shortBuffer = ShortArray(bufferSizeBytes / 2)
                val floatOutputBuffer = ByteBuffer.allocateDirect(shortBuffer.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                val floatByteArray = ByteArray(shortBuffer.size * 4)

                while (isRecording.get() && isActive) {
                    val shortsRead = record.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)

                    if (shortsRead > 0) {
                        floatOutputBuffer.clear()
                        for (i in 0 until shortsRead) {
                            val floatVal = shortBuffer[i] / 32768.0f
                            floatOutputBuffer.putFloat(floatVal)
                        }
                        val bytesConverted = shortsRead * 4
                        floatOutputBuffer.position(0)
                        floatOutputBuffer.get(floatByteArray, 0, bytesConverted)
                        diskWriter.write(floatByteArray, 0, bytesConverted)
                    } else if (shortsRead < 0) {
                        handleError(shortsRead, onError)
                        break
                    }
                }
            }
        }
    }

    private data class InitializedRecord(
        val record: AudioRecord,
        val encoding: Int,
        val bufferSizeBytes: Int
    )

    @SuppressLint("MissingPermission")
    private fun buildResilientAudioRecord(
        targetDevice: AudioDeviceInfo?,
        isBluetooth: Boolean,
        sampleRate: Int
    ): InitializedRecord? {
        val channelConfig = AudioFormat.CHANNEL_IN_MONO

        // Fallback strategies: Bluetooth requires 16-bit PCM; built-in/USB tries Float first
        val configurations = if (isBluetooth) {
            listOf(
                Triple(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioFormat.ENCODING_PCM_16BIT, sampleRate),
                Triple(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_16BIT, sampleRate),
                Triple(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_16BIT, 16000)
            )
        } else {
            listOf(
                Triple(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_FLOAT, sampleRate),
                Triple(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_16BIT, sampleRate),
                Triple(MediaRecorder.AudioSource.DEFAULT, AudioFormat.ENCODING_PCM_16BIT, sampleRate)
            )
        }

        for ((source, encoding, rate) in configurations) {
            val minBufferSize = AudioRecord.getMinBufferSize(rate, channelConfig, encoding)
            if (minBufferSize <= 0) continue

            val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)

            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(rate)
                .setChannelMask(channelConfig)
                .build()

            val record = try {
                AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (_: Exception) {
                null
            } ?: continue

            if (record.state == AudioRecord.STATE_INITIALIZED) {
                // Set preferredDevice only on non-Bluetooth inputs (avoid double-routing conflicts with setCommunicationDevice)
                if (targetDevice != null && !isBluetooth) {
                    try {
                        record.preferredDevice = targetDevice
                    } catch (_: Exception) {}
                }
                return InitializedRecord(record, encoding, bufferSize)
            } else {
                record.release()
            }
        }
        return null
    }

    private suspend fun handleError(code: Int, onError: (RecordingError) -> Unit) {
        val errorMsg = when (code) {
            AudioRecord.ERROR_INVALID_OPERATION -> "AudioRecord state corrupt (INVALID_OPERATION)"
            AudioRecord.ERROR_BAD_VALUE -> "Bad parameter passed to HAL (BAD_VALUE)"
            AudioRecord.ERROR_DEAD_OBJECT -> "Audio HAL died or client preempted (DEAD_OBJECT)"
            else -> "AudioRecord hardware read failed with code $code"
        }
        withContext(Dispatchers.Main) {
            onError(RecordingError.ReadError(code, errorMsg))
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