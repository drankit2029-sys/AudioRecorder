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
    private val isPaused = AtomicBoolean(false)

    var currentDevice: AudioDeviceInfo? = null
        private set

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        isPaused.set(false)
    }

    fun isPausedState(): Boolean = isPaused.get()

    @SuppressLint("MissingPermission")
    fun startRecording(
        scope: CoroutineScope,
        targetDevice: AudioDeviceInfo?,
        sampleRate: Int,
        channelCount: Int,
        destinationFile: File,
        append: Boolean = false,
        onDbfsUpdate: (Float) -> Unit,
        onError: (RecordingError) -> Unit
    ) {
        if (isRecording.get()) return

        val isBluetooth = targetDevice?.let {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: false

        val effectiveChannels = if (isBluetooth) 1 else channelCount
        val initResult = buildResilientAudioRecord(targetDevice, isBluetooth, sampleRate, effectiveChannels)
        if (initResult == null) {
            onError(RecordingError.InitializationFailed("Microphone initialization failed: HAL rejected $sampleRate Hz track"))
            return
        }

        val (record, hardwareEncoding, hardwareRate, bufferSizeBytes) = initResult

        audioRecord = record
        currentDevice = targetDevice
        diskWriter.start(destinationFile, append = append)
        isRecording.set(true)
        isPaused.set(false)

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
            if (hardwareEncoding == AudioFormat.ENCODING_PCM_FLOAT && hardwareRate == sampleRate) {
                val byteBuffer = ByteBuffer.allocateDirect(bufferSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
                val byteArray = ByteArray(bufferSizeBytes)

                while (isRecording.get() && isActive) {
                    byteBuffer.clear()
                    val bytesRead = record.read(byteBuffer, bufferSizeBytes, AudioRecord.READ_BLOCKING)

                    if (bytesRead > 0) {
                        // Calculate real-time RMS dBFS
                        var sumSquares = 0.0
                        val floatCount = bytesRead / 4
                        for (i in 0 until floatCount) {
                            val sample = byteBuffer.getFloat(i * 4)
                            sumSquares += (sample * sample)
                        }
                        val rms = Math.sqrt(sumSquares / floatCount.coerceAtLeast(1)).toFloat()
                        val dbfs = (20.0 * Math.log10(rms.coerceAtLeast(0.00001f).toDouble()))
                            .toFloat()
                            .coerceIn(-60f, 0f)

                        onDbfsUpdate(if (isPaused.get()) -60f else dbfs)

                        if (!isPaused.get()) {
                            byteBuffer.position(0)
                            byteBuffer.get(byteArray, 0, bytesRead)
                            diskWriter.write(byteArray, 0, bytesRead)
                        }
                    } else if (bytesRead < 0) {
                        handleError(bytesRead, onError)
                        break
                    }
                }
            } else {
                val upsampleFactor = (sampleRate / hardwareRate).coerceAtLeast(1)
                val shortBuffer = ShortArray(bufferSizeBytes / 2)
                val outputFloatsCount = shortBuffer.size * upsampleFactor
                val floatOutputBuffer = ByteBuffer.allocateDirect(outputFloatsCount * 4).order(ByteOrder.LITTLE_ENDIAN)
                val floatByteArray = ByteArray(outputFloatsCount * 4)
                var lastSample = 0.0f

                while (isRecording.get() && isActive) {
                    val shortsRead = record.read(shortBuffer, 0, shortBuffer.size, AudioRecord.READ_BLOCKING)

                    if (shortsRead > 0) {
                        var sumSquares = 0.0
                        for (i in 0 until shortsRead) {
                            val sample = shortBuffer[i] / 32768.0f
                            sumSquares += (sample * sample)
                        }
                        val rms = Math.sqrt(sumSquares / shortsRead.coerceAtLeast(1)).toFloat()
                        val dbfs = (20.0 * Math.log10(rms.coerceAtLeast(0.00001f).toDouble()))
                            .toFloat()
                            .coerceIn(-60f, 0f)

                        onDbfsUpdate(if (isPaused.get()) -60f else dbfs)

                        if (!isPaused.get()) {
                            floatOutputBuffer.clear()
                            for (i in 0 until shortsRead) {
                                val currentSample = shortBuffer[i] / 32768.0f
                                if (upsampleFactor > 1) {
                                    val delta = (currentSample - lastSample) / upsampleFactor.toFloat()
                                    for (step in 1..upsampleFactor) {
                                        floatOutputBuffer.putFloat(lastSample + (delta * step))
                                    }
                                    lastSample = currentSample
                                } else {
                                    floatOutputBuffer.putFloat(currentSample)
                                }
                            }

                            val bytesToWrite = shortsRead * upsampleFactor * 4
                            floatOutputBuffer.position(0)
                            floatOutputBuffer.get(floatByteArray, 0, bytesToWrite)
                            diskWriter.write(floatByteArray, 0, bytesToWrite)
                        }
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
        val sampleRate: Int,
        val bufferSizeBytes: Int
    )

    @SuppressLint("MissingPermission")
    private fun buildResilientAudioRecord(
        targetDevice: AudioDeviceInfo?,
        isBluetooth: Boolean,
        sampleRate: Int,
        channelCount: Int
    ): InitializedRecord? {
        val channelMask = if (channelCount == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO

        val configurations = if (isBluetooth) {
            listOf(
                Triple(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioFormat.ENCODING_PCM_16BIT, sampleRate),
                Triple(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioFormat.ENCODING_PCM_16BIT, 16000),
                Triple(MediaRecorder.AudioSource.VOICE_COMMUNICATION, AudioFormat.ENCODING_PCM_16BIT, 8000)
            )
        } else {
            listOf(
                Triple(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_FLOAT, sampleRate),
                Triple(MediaRecorder.AudioSource.MIC, AudioFormat.ENCODING_PCM_16BIT, sampleRate),
                Triple(MediaRecorder.AudioSource.DEFAULT, AudioFormat.ENCODING_PCM_16BIT, sampleRate)
            )
        }

        for ((source, encoding, rate) in configurations) {
            val minBufferSize = AudioRecord.getMinBufferSize(rate, channelMask, encoding)
            if (minBufferSize <= 0) continue

            val bufferSize = (minBufferSize * 2).coerceAtLeast(8192)

            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(rate)
                .setChannelMask(channelMask)
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
                if (targetDevice != null && !isBluetooth) {
                    try {
                        record.preferredDevice = targetDevice
                    } catch (_: Exception) {}
                }
                return InitializedRecord(record, encoding, rate, bufferSize)
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
        isPaused.set(false)
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