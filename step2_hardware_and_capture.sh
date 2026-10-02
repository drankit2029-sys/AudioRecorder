#!/bin/sh
set -e

BASE="app/src/main/java/com/example/audiorecorder"

echo "==> 1. Writing AudioPreset.kt..."
cat << 'PRESET' > "$BASE/audio/hardware/AudioPreset.kt"
package com.example.audiorecorder.audio.hardware

enum class PresetType {
    STANDARD_PODCAST,
    STUDIO_MASTER,
    VOICE_MEMO,
    CUSTOM
}

data class AudioPreset(
    val type: PresetType,
    val name: String,
    val sampleRate: Int,
    val bitDepth: Int,
    val channels: Int, // 1 for Mono, 2 for Stereo
    val containerFormat: String // "WAV", "FLAC", "AAC", "MP3"
) {
    companion object {
        val STANDARD_PODCAST = AudioPreset(
            type = PresetType.STANDARD_PODCAST,
            name = "Standard Podcast",
            sampleRate = 48000,
            bitDepth = 24,
            channels = 2,
            containerFormat = "WAV"
        )

        val STUDIO_MASTER = AudioPreset(
            type = PresetType.STUDIO_MASTER,
            name = "Studio Master (Raw Float)",
            sampleRate = 48000, // May scale to 96000 if hardware supports it
            bitDepth = 32,
            channels = 2,
            containerFormat = "WAV"
        )

        val VOICE_MEMO = AudioPreset(
            type = PresetType.VOICE_MEMO,
            name = "Voice Memo",
            sampleRate = 16000,
            bitDepth = 16,
            channels = 1,
            containerFormat = "AAC"
        )

        fun getDefaults(): List<AudioPreset> = listOf(
            STANDARD_PODCAST,
            STUDIO_MASTER,
            VOICE_MEMO
        )
    }
}
PRESET

echo "==> 2. Writing MicrophoneManager.kt..."
cat << 'MIC_MGR' > "$BASE/audio/hardware/MicrophoneManager.kt"
package com.example.audiorecorder.audio.hardware

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class DiscoveredMic(
    val id: Int,
    val name: String,
    val typeName: String,
    val isBluetooth: Boolean,
    val isUsb: Boolean,
    val supportedSampleRates: List<Int>,
    val supportedChannelCounts: List<Int>,
    val rawDeviceInfo: AudioDeviceInfo
)

class MicrophoneManager(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /**
     * Enumerates all physical audio input devices currently available to the OS.
     */
    fun enumerateMicrophones(): List<DiscoveredMic> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        val micList = mutableListOf<DiscoveredMic>()

        for (device in devices) {
            val (typeName, isBt, isUsb) = parseDeviceType(device.type)

            // Extract sample rates reported by hardware HAL (can be empty if device supports any standard rate)
            val rates = device.sampleRates.toList()
            val channels = device.channelCounts.toList()

            val label = when {
                device.productName.isNotBlank() -> device.productName.toString()
                else -> typeName
            }

            micList.add(
                DiscoveredMic(
                    id = device.id,
                    name = label,
                    typeName = typeName,
                    isBluetooth = isBt,
                    isUsb = isUsb,
                    supportedSampleRates = rates,
                    supportedChannelCounts = channels,
                    rawDeviceInfo = device
                )
            )
        }
        return micList
    }

    /**
     * Verifies if the target preset can run on the selected microphone.
     * Returns a Pair: (Boolean isSupported, String reasonIfBlocked).
     */
    fun validatePresetCompatibility(mic: DiscoveredMic, preset: AudioPreset): Pair<Boolean, String?> {
        // Bluetooth SCO hardware profiles are strictly constrained to 16 kHz Mono
        if (mic.isBluetooth) {
            if (preset.sampleRate > 16000 || preset.channels > 1) {
                return Pair(
                    false,
                    "Bluetooth SCO profile is constrained to 16 kHz Mono. Select Voice Memo or custom 16 kHz Mono."
                )
            }
        }

        // If hardware enumerates explicit sample rates, verify membership
        if (mic.supportedSampleRates.isNotEmpty() && !mic.supportedSampleRates.contains(preset.sampleRate)) {
            return Pair(
                false,
                "Microphone '${mic.name}' does not support ${preset.sampleRate} Hz (Supports: ${mic.supportedSampleRates.joinToString()} Hz)"
            )
        }

        // Check channel support if strictly enumerated
        if (mic.supportedChannelCounts.isNotEmpty() && !mic.supportedChannelCounts.contains(preset.channels)) {
            val channelName = if (preset.channels == 2) "Stereo" else "Mono"
            return Pair(
                false,
                "Microphone '${mic.name}' does not support $channelName input."
            )
        }

        return Pair(true, null)
    }

    private fun parseDeviceType(type: Int): Triple<String, Boolean, Boolean> {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> Triple("Built-in Microphone", false, false)
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> Triple("Bluetooth SCO Headset", true, false)
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> Triple("Wired Headset Mic", false, false)
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> Triple("USB Audio Interface", false, true)
            else -> Triple("Audio Input ($type)", false, false)
        }
    }
}
MIC_MGR

echo "==> 3. Writing AudioCaptureEngine.kt..."
cat << 'CAPTURE' > "$BASE/audio/engine/AudioCaptureEngine.kt"
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

interface AudioCaptureListener {
    fun onDbfsUpdate(peakDbfs: Float, rmsDbfs: Float)
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

    /**
     * Initializes and launches the AudioRecord float capture thread.
     */
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

        if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
            listener?.onError("Invalid hardware audio buffer configuration.")
            return false
        }

        // Allocate a working buffer of at least 2x minBufferSize for stability
        val bufferSize = minBufferSize * 2

        try {
            val recordInstance = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            // Route audio to specific physical endpoint if requested
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

            // Start capture thread with high audio priority
            recordingThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                captureLoop(bufferSize / 4) // 4 bytes per float
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

            // Blocking read for 32-bit IEEE float samples
            val readCount = record.read(floatBuffer, 0, floatChunkSize, AudioRecord.READ_BLOCKING)

            if (readCount > 0) {
                // 1. Direct synchronous write to the scratchpad
                scratchpadManager.writeFloats(floatBuffer, readCount)

                // 2. Throttle UI metrics updates to ~60 Hz (every 16 ms)
                val now = System.currentTimeMillis()
                if (now - lastMetricsPostTime >= 16) {
                    lastMetricsPostTime = now
                    val peak = DbfsCalculator.calculatePeakDbfs(floatBuffer, readCount)
                    val rms = DbfsCalculator.calculateRmsDbfs(floatBuffer, readCount)
                    listener?.onDbfsUpdate(peak, rms)
                }
            } else if (readCount < 0) {
                listener?.onError("AudioRecord read error code: $readCount")
                break
            }
        }
    }

    fun pauseCapture() {
        isPaused.set(true)
    }

    fun resumeCapture() {
        isPaused.set(false)
    }

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
CAPTURE

echo "==> Step 2 code generation complete!"
