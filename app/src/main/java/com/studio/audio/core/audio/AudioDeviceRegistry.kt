package com.studio.audio.core.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

data class AudioInputDevice(
    val id: Int,
    val name: String,
    val typeLabel: String,
    val sampleRates: List<Int>,
    val rawDeviceInfo: AudioDeviceInfo? // null indicates the system-calibrated default mic array
)

class AudioDeviceRegistry(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Whitelist only actual user-recordable input hardware
    private val allowedRecordingTypes = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL
    )

    fun getAvailableInputDevices(): List<AudioInputDevice> {
        val allHardwareInputs = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type in allowedRecordingTypes }

        val devices = mutableListOf<AudioInputDevice>()

        // 1. Consolidate internal mic endpoints into a single clean "Built-in Microphone"
        val internalMics = allHardwareInputs.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (internalMics.isNotEmpty()) {
            val primaryMic = internalMics.first()
            val reportedRates = primaryMic.sampleRates.toList()
            val effectiveRates = if (reportedRates.isEmpty()) listOf(44100, 48000) else reportedRates

            devices.add(
                AudioInputDevice(
                    id = primaryMic.id,
                    name = "Built-in Microphone",
                    typeLabel = "Internal",
                    sampleRates = effectiveRates,
                    rawDeviceInfo = null // Passing null to AudioRecord engages the calibrated onboard array
                )
            )
        }

        // 2. Add connected external peripherals (USB interfaces, Bluetooth headsets, Wired mics)
        val externalInputs = allHardwareInputs.filter { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
        for (device in externalInputs) {
            val reportedRates = device.sampleRates.toList()
            val effectiveRates = if (reportedRates.isEmpty()) listOf(44100, 48000) else reportedRates

            devices.add(
                AudioInputDevice(
                    id = device.id,
                    name = resolveFriendlyName(device),
                    typeLabel = mapDeviceTypeToString(device.type),
                    sampleRates = effectiveRates,
                    rawDeviceInfo = device
                )
            )
        }

        return devices
    }

    private fun resolveFriendlyName(device: AudioDeviceInfo): String {
        val rawName = device.productName.toString().trim()
        val isGenericModel = rawName.isBlank() || rawName.equals(Build.MODEL, ignoreCase = true)

        return when (device.type) {
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> if (isGenericModel) "Wired Headset Mic" else rawName
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET -> if (isGenericModel) "Bluetooth Microphone" else rawName
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET -> if (isGenericModel) "USB Audio Device" else rawName
            else -> if (isGenericModel) "External Input #${device.id}" else rawName
        }
    }

    private fun mapDeviceTypeToString(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (SCO)"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth (BLE)"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            else -> "External"
        }
    }
}