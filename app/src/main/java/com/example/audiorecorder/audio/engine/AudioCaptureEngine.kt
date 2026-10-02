package com.example.audiorecorder.audio.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.example.audiorecorder.audio.dsp.DbfsCalculator
import com.example.audiorecorder.audio.hardware.DiscoveredMic
import com.example.audiorecorder.audio.scratchpad.ScratchpadManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

interface AudioCaptureListener {
    fun onAudioFrame(peakDbfs: Float, rmsDbfs: Float, peakLinear: Float)
    fun onError(errorMessage: String)
}

class AudioCaptureEngine(
    private val scratchpadManager: ScratchpadManager,
    private val listener: AudioCaptureListener? = null
) {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun startCapture(
        sampleRate: Int,
        channels: Int,
        targetMic: DiscoveredMic? = null
    ): Boolean {
        if (isRecording.get()) return true

        val channelConfig = if (channels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        if (minBufferSize <= 0) {
            listener?.onError("Invalid hardware audio buffer configuration.")
            return false
        }

        val bufferSize = minBufferSize * 2

        try {
            val recordInstance = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (targetMic != null) {
                recordInstance.preferredDevice = targetMic.rawDeviceInfo
            }

            if (recordInstance.state != AudioRecord.STATE_INITIALIZED) {
                listener?.onError("Failed to initialize AudioRecord instance.")
                recordInstance.release()
                return false
            }

            audioRecord = recordInstance
            scratchpadManager.openSession()
            recordInstance.startRecording()
            isRecording.set(true)
            isPaused.set(false)

            recordingThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                captureLoop(bufferSize / 4)
            }, "AudioCaptureThread").apply {
                start()
            }

            return true
        } catch (e: Exception) {
            listener?.onError("Error starting audio capture: ${e.message}")
            release()
            return false
        }
    }

    private fun captureLoop(floatChunkSize: Int) {
        val record = audioRecord ?: return
        val floatBuffer = FloatArray(floatChunkSize)
        var lastMetricsPostTime = 0L

        while (isRecording.get()) {
            if (isPaused.get()) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            val readCount = record.read(floatBuffer, 0, floatChunkSize, AudioRecord.READ_BLOCKING)

            if (readCount > 0) {
                scratchpadManager.writeFloats(floatBuffer, readCount)

                val now = System.currentTimeMillis()
                // Throttle emission to ~40Hz (every 25ms) for visualizer stability
                if (now - lastMetricsPostTime >= 25) {
                    lastMetricsPostTime = now

                    var peakLinear = 0.0f
                    for (i in 0 until readCount) {
                        val sampleAbs = abs(floatBuffer[i])
                        if (sampleAbs > peakLinear) {
                            peakLinear = sampleAbs
                        }
                    }

                    val peakDbfs = DbfsCalculator.calculatePeakDbfs(floatBuffer, readCount)
                    val rmsDbfs = DbfsCalculator.calculateRmsDbfs(floatBuffer, readCount)
                    listener?.onAudioFrame(peakDbfs, rmsDbfs, peakLinear)
                }
            } else if (readCount < 0) {
                listener?.onError("AudioRecord read error code: $readCount")
                break
            }
        }
    }

    fun pauseCapture() { isPaused.set(true) }
    fun resumeCapture() { isPaused.set(false) }

    fun stopCapture() {
        isRecording.set(false)
        isPaused.set(false)

        try {
            recordingThread?.join(1000)
        } catch (_: InterruptedException) {}
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}

        scratchpadManager.sync()
        release()
    }

    fun release() {
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    fun isRecording(): Boolean = isRecording.get()
    fun isPaused(): Boolean = isPaused.get()
}
