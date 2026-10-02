package com.studio.audio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studio.audio.core.audio.AudioCaptureEngine
import com.studio.audio.core.audio.AudioDeviceRegistry
import com.studio.audio.core.audio.AudioDiskWriter
import com.studio.audio.core.audio.AudioInputDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val deviceRegistry = AudioDeviceRegistry(application)
    private val diskWriter = AudioDiskWriter()
    private val captureEngine = AudioCaptureEngine(diskWriter)

    private val _availableDevices = MutableStateFlow<List<AudioInputDevice>>(emptyList())
    val availableDevices: StateFlow<List<AudioInputDevice>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioInputDevice?>(null)
    val selectedDevice: StateFlow<AudioInputDevice?> = _selectedDevice.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    fun refreshDevices() {
        val devices = deviceRegistry.getAvailableInputDevices()
        _availableDevices.value = devices
        if (_selectedDevice.value == null || devices.none { it.id == _selectedDevice.value?.id }) {
            _selectedDevice.value = devices.firstOrNull()
        }
    }

    fun selectDevice(device: AudioInputDevice) {
        _selectedDevice.value = device
        if (_isRecording.value) {
            captureEngine.switchDevice(device.rawDeviceInfo)
        }
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            captureEngine.stopRecording()
            _isRecording.value = false
        } else {
            val destination = File(
                getApplication<Application>().filesDir,
                "session_${System.currentTimeMillis()}.pcm"
            )
            val chosenDevice = _selectedDevice.value?.rawDeviceInfo
            captureEngine.startRecording(
                scope = viewModelScope,
                targetDevice = chosenDevice,
                sampleRate = 48000,
                destinationFile = destination
            )
            _isRecording.value = true
        }
    }
}