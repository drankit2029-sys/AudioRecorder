package com.studio.audio.core.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

data class AudioInputDevice(
    val id: Int,
    val name: String,
    val typeLabel: String,
    val sampleRates: List<Int>,     // Empty list explicitly means unconstrained / arbitrary
    val channelCounts: List<Int>,   // Empty list explicitly means unconstrained / arbitrary
    val isUnconstrained: Boolean,   // True when HAL reports open/arbitrary rates
    val rawDeviceInfo: AudioDeviceInfo?
)

class AudioDeviceRegistry(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

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

        // 1. Built-in Microphone Consolidated Array
        val internalMics = allHardwareInputs.filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
        if (internalMics.isNotEmpty()) {
            val primaryMic = internalMics.first()
            val rawRates = primaryMic.sampleRates.toList()
            val rawChannels = primaryMic.channelCounts.toList()

            devices.add(
                AudioInputDevice(
                    id = primaryMic.id,
                    name = "Built-in Microphone",
                    typeLabel = "Internal",
                    sampleRates = rawRates,
                    channelCounts = rawChannels,
                    isUnconstrained = rawRates.isEmpty(),
                    rawDeviceInfo = null
                )
            )
        }

        // 2. External Peripherals (USB, Bluetooth, etc.)
        val externalInputs = allHardwareInputs.filter { it.type != AudioDeviceInfo.TYPE_BUILTIN_MIC }
        for (device in externalInputs) {
            val rawRates = device.sampleRates.toList()
            val rawChannels = device.channelCounts.toList()

            devices.add(
                AudioInputDevice(
                    id = device.id,
                    name = resolveFriendlyName(device),
                    typeLabel = mapDeviceTypeToString(device.type),
                    sampleRates = rawRates,
                    channelCounts = rawChannels,
                    isUnconstrained = rawRates.isEmpty(),
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