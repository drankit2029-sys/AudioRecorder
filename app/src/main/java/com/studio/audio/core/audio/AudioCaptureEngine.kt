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
        append: Boolean = false
    ) {
        if (isRecording.get()) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val encoding = AudioFormat.ENCODING_PCM_FLOAT // 32-bit IEEE 754 Float

        val format = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
        val bufferSizeBytes = (minBufferSize * 2).coerceAtLeast(8192)

        val record = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(format)
            .setBufferSizeInBytes(bufferSizeBytes)
            .build()

        record.preferredDevice = targetDevice
        currentDevice = targetDevice

        audioRecord = record
        diskWriter.start(destinationFile, append = append)
        isRecording.set(true)
        record.startRecording()

        recordingJob = scope.launch(Dispatchers.IO) {
            // Using a direct ByteBuffer to stream float bytes directly to disk
            val byteBuffer = ByteBuffer.allocateDirect(bufferSizeBytes).order(ByteOrder.LITTLE_ENDIAN)
            val byteArray = ByteArray(bufferSizeBytes)

            while (isRecording.get() && isActive) {
                byteBuffer.clear()
                val bytesRead = record.read(byteBuffer, bufferSizeBytes, AudioRecord.READ_BLOCKING)

                if (bytesRead > 0) {
                    byteBuffer.position(0)
                    byteBuffer.get(byteArray, 0, bytesRead)
                    diskWriter.write(byteArray, 0, bytesRead)
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