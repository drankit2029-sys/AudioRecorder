package com.studio.audio.core.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

data class AudioInputDevice(
    val id: Int,
    val name: String,
    val typeLabel: String,
    val sampleRates: List<Int>,
    val rawDeviceInfo: AudioDeviceInfo
)

class AudioDeviceRegistry(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun getAvailableInputDevices(): List<AudioInputDevice> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return devices.map { device ->
            val reportedRates = device.sampleRates.toList()
            // If the hardware HAL returns an empty array, standard Android rates (44.1k/48k) are supported via AudioFlinger
            val effectiveRates = if (reportedRates.isEmpty()) listOf(44100, 48000) else reportedRates

            AudioInputDevice(
                id = device.id,
                name = device.productName.toString().ifBlank { "Input Device #${device.id}" },
                typeLabel = mapDeviceTypeToString(device.type),
                sampleRates = effectiveRates,
                rawDeviceInfo = device
            )
        }
    }

    private fun mapDeviceTypeToString(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Mic"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset (SCO)"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLE Headset"
            else -> "External Device ($type)"
        }
    }
}
