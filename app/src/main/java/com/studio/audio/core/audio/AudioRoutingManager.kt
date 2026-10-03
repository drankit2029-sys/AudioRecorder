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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

class AudioRoutingManager(
    private val context: Context,
    private val onDeviceListChanged: () -> Unit,
    private val onActiveDeviceDisconnected: (AudioDeviceInfo) -> Unit
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeDevice: AudioDeviceInfo? = null
    private var scoActive = false
    private var scoConnectDeferred: CompletableDeferred<Boolean>? = null

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
            if (intent?.action != AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED) return
            val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)

            when (state) {
                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                    scoActive = true
                    scoConnectDeferred?.complete(true)
                }
                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                    if (scoActive) {
                        // Real disconnection while recording was active
                        scoActive = false
                        activeDevice?.let { dev ->
                            if (isBluetoothDevice(dev.type)) {
                                onActiveDeviceDisconnected(dev)
                            }
                        }
                    } else {
                        // Failed negotiation or initial broadcast
                        scoConnectDeferred?.complete(false)
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

    /**
     * Activates routing and suspends until the hardware audio channel is verified.
     */
    suspend fun activateRoute(device: AudioDeviceInfo?): Boolean {
        activeDevice = device
        if (device == null) {
            teardownRouting()
            return true
        }

        if (isBluetoothDevice(device.type)) {
            return activateBluetoothRoute(device)
        } else {
            teardownRouting()
            return true
        }
    }

    private suspend fun activateBluetoothRoute(device: AudioDeviceInfo): Boolean {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val setSuccess = audioManager.setCommunicationDevice(device)
            if (!setSuccess) {
                audioManager.mode = AudioManager.MODE_NORMAL
                return false
            }
            scoActive = true
            return true
        } else {
            val deferred = CompletableDeferred<Boolean>()
            scoConnectDeferred = deferred
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true

            // Wait up to 3000ms for hardware SCO synchronization
            val connected = withTimeoutOrNull(3000L) {
                deferred.await()
            } ?: false

            scoConnectDeferred = null

            if (!connected) {
                teardownRouting()
            }
            return connected
        }
    }

    fun teardownRouting() {
        if (scoActive || audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                audioManager.isBluetoothScoOn = false
                try {
                    audioManager.stopBluetoothSco()
                } catch (_: Exception) {}
            }
            audioManager.mode = AudioManager.MODE_NORMAL
            scoActive = false
        }
        activeDevice = null
    }

    fun isBluetoothDevice(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }
}