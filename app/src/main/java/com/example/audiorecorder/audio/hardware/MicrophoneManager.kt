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
