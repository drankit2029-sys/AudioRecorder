package com.studio.audio.core.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

class AudioRoutingManager(
    private val context: Context,
    private val onDeviceListChanged: () -> Unit,
    private val onActiveDeviceDisconnected: (AudioDeviceInfo) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeDevice: AudioDeviceInfo? = null
    private var isScoStarted = false

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            mainHandler.post { onDeviceListChanged() }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            mainHandler.post {
                onDeviceListChanged()
                val current = activeDevice ?: return@post
                val wasRemoved = removedDevices?.any { it.id == current.id } == true
                if (wasRemoved) {
                    onActiveDeviceDisconnected(current)
                }
            }
        }
    }

    private val scoReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
            if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED && isScoStarted) {
                activeDevice?.let { dev ->
                    if (isBluetoothDevice(dev.type)) {
                        onActiveDeviceDisconnected(dev)
                    }
                }
            }
        }
    }

    fun startMonitoring() {
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        context.registerReceiver(scoReceiver, filter)
    }

    fun stopMonitoring() {
        try {
            audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
        } catch (_: Exception) {}
        try {
            context.unregisterReceiver(scoReceiver)
        } catch (_: Exception) {}
        teardownRouting()
    }

    fun setupRoutingForDevice(device: AudioDeviceInfo?): Boolean {
        activeDevice = device
        if (device == null) {
            teardownRouting()
            return true
        }

        if (isBluetoothDevice(device.type)) {
            return activateBluetoothRoute(device)
        } else {
            teardownRouting()
        }
        return true
    }

    private fun activateBluetoothRoute(device: AudioDeviceInfo): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val success = audioManager.setCommunicationDevice(device)
            isScoStarted = success
            success
        } else {
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            isScoStarted = true
            true
        }
    }

    fun teardownRouting() {
        if (isScoStarted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                audioManager.mode = AudioManager.MODE_NORMAL
            }
            isScoStarted = false
        }
        activeDevice = null
    }

    private fun isBluetoothDevice(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
    }
}
