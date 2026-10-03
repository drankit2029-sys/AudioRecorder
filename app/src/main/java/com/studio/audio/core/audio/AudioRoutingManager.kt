package com.studio.audio.core.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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
    private var audioFocusRequest: AudioFocusRequest? = null

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
                        scoActive = false
                        activeDevice?.let { dev ->
                            if (isBluetoothDevice(dev.type)) {
                                onActiveDeviceDisconnected(dev)
                            }
                        }
                    } else {
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

    suspend fun activateRoute(device: AudioDeviceInfo?): Boolean {
        activeDevice = device
        if (device == null) {
            teardownRouting()
            return true
        }

        return if (isBluetoothDevice(device.type)) {
            val success = activateBluetoothRoute()
            if (success) {
                // Allow hardware clock to synchronize
                delay(350L)
            }
            success
        } else {
            teardownRouting()
            true
        }
    }

    private suspend fun activateBluetoothRoute(): Boolean {
        requestCommunicationAudioFocus()
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+ requires a device specifically from availableCommunicationDevices
            val commDevice = audioManager.availableCommunicationDevices.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }

            if (commDevice != null) {
                val setSuccess = audioManager.setCommunicationDevice(commDevice)
                if (setSuccess) {
                    scoActive = true
                    return true
                }
            }
            teardownRouting()
            return false
        } else {
            val deferred = CompletableDeferred<Boolean>()
            scoConnectDeferred = deferred
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true

            val connected = withTimeoutOrNull(3500L) {
                deferred.await()
            } ?: false

            scoConnectDeferred = null
            if (!connected) {
                teardownRouting()
            }
            return connected
        }
    }

    private fun requestCommunicationAudioFocus() {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener { /* handle focus preemption */ }
            .build()

        audioFocusRequest = request
        audioManager.requestAudioFocus(request)
    }

    fun teardownRouting() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            audioManager.isBluetoothScoOn = false
            try {
                audioManager.stopBluetoothSco()
            } catch (_: Exception) {}
        }

        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) {
            audioManager.mode = AudioManager.MODE_NORMAL
        }

        audioFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
            audioFocusRequest = null
        }

        scoActive = false
        activeDevice = null
    }

    fun isBluetoothDevice(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }
}