package com.studio.audio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studio.audio.core.audio.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val deviceRegistry = AudioDeviceRegistry(application)
    private val diskWriter = AudioDiskWriter()
    private val captureEngine = AudioCaptureEngine(diskWriter)
    private val recoveryManager = SessionRecoveryManager(application)

    private val _availableDevices = MutableStateFlow<List<AudioInputDevice>>(emptyList())
    val availableDevices: StateFlow<List<AudioInputDevice>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioInputDevice?>(null)
    val selectedDevice: StateFlow<AudioInputDevice?> = _selectedDevice.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _interruptedSession = MutableStateFlow<InterruptedSession?>(null)
    val interruptedSession: StateFlow<InterruptedSession?> = _interruptedSession.asStateFlow()

    private var activeRecordingFile: File? = null

    init {
        refreshDevices()
        checkForInterruptedSession()
    }

    fun checkForInterruptedSession() {
        _interruptedSession.value = recoveryManager.getInterruptedSession()
    }

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
            stopSession()
        } else {
            startSession(destination = recoveryManager.createNewTakeFile(), append = false)
        }
    }

    fun resumeInterruptedSession() {
        val session = _interruptedSession.value ?: return
        _interruptedSession.value = null
        startSession(destination = session.audioFile, append = true)
    }

    fun finalizeInterruptedSession() {
        recoveryManager.markSessionCompleted()
        _interruptedSession.value = null
    }

    fun discardInterruptedSession() {
        recoveryManager.discardInterruptedSession()
        _interruptedSession.value = null
    }

    private fun startSession(destination: File, append: Boolean) {
        activeRecordingFile = destination
        val sampleRate = 48000
        recoveryManager.markSessionActive(destination, sampleRate)
        captureEngine.startRecording(
            scope = viewModelScope,
            targetDevice = _selectedDevice.value?.rawDeviceInfo,
            sampleRate = sampleRate,
            destinationFile = destination,
            append = append
        )
        _isRecording.value = true
    }

    private fun stopSession() {
        captureEngine.stopRecording()
        recoveryManager.markSessionCompleted()
        activeRecordingFile = null
        _isRecording.value = false
    }
}