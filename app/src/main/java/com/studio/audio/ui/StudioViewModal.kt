package com.studio.audio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studio.audio.core.audio.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppDestination {
    STUDIO,
    LIBRARY
}

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val deviceRegistry = AudioDeviceRegistry(application)
    private val diskWriter = AudioDiskWriter()
    private val captureEngine = AudioCaptureEngine(diskWriter)
    private val recoveryManager = SessionRecoveryManager(application)

    private val _currentDestination = MutableStateFlow(AppDestination.STUDIO)
    val currentDestination: StateFlow<AppDestination> = _currentDestination.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<AudioInputDevice>>(emptyList())
    val availableDevices: StateFlow<List<AudioInputDevice>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioInputDevice?>(null)
    val selectedDevice: StateFlow<AudioInputDevice?> = _selectedDevice.asStateFlow()

    // Preset Selection State (Decoupled from recording engine for now)
    private val _selectedPreset = MutableStateFlow<AudioPreset>(AudioPresetValidator.POPULAR_PRESETS.first())
    val selectedPreset: StateFlow<AudioPreset> = _selectedPreset.asStateFlow()

    private val _customPreset = MutableStateFlow(
        AudioPreset(
            id = "preset_custom",
            name = "Custom Preset",
            description = "User-configured audio attributes",
            sampleRate = 48000,
            channelCount = 2,
            bitDepth = "24-bit",
            format = AudioEncodingFormat.PCM_24BIT,
            isCustom = true
        )
    )
    val customPreset: StateFlow<AudioPreset> = _customPreset.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _interruptedSession = MutableStateFlow<InterruptedSession?>(null)
    val interruptedSession: StateFlow<InterruptedSession?> = _interruptedSession.asStateFlow()

    private val _pendingSaveFile = MutableStateFlow<File?>(null)
    val pendingSaveFile: StateFlow<File?> = _pendingSaveFile.asStateFlow()

    private val _savedRecordings = MutableStateFlow<List<SavedRecording>>(emptyList())
    val savedRecordings: StateFlow<List<SavedRecording>> = _savedRecordings.asStateFlow()

    private var activeRecordingFile: File? = null

    init {
        refreshDevices()
        refreshLibrary()
        checkForInterruptedSession()
    }

    fun selectPreset(preset: AudioPreset) {
        _selectedPreset.value = preset
    }

    fun updateCustomPreset(
        sampleRate: Int,
        channelCount: Int,
        bitDepth: String,
        format: AudioEncodingFormat
    ) {
        val updated = _customPreset.value.copy(
            sampleRate = sampleRate,
            channelCount = channelCount,
            bitDepth = bitDepth,
            format = format
        )
        _customPreset.value = updated
        _selectedPreset.value = updated
    }

    fun validatePreset(preset: AudioPreset): CompatibilityResult {
        return AudioPresetValidator.validate(preset, _selectedDevice.value)
    }

    fun navigateTo(destination: AppDestination) {
        _currentDestination.value = destination
        if (destination == AppDestination.LIBRARY) {
            refreshLibrary()
        }
    }

    fun refreshLibrary() {
        _savedRecordings.value = recoveryManager.getSavedRecordings()
    }

    fun deleteRecording(recording: SavedRecording) {
        recoveryManager.deleteRecording(recording.file)
        refreshLibrary()
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
            val stoppedFile = captureEngine.stopRecording()
            _isRecording.value = false
            activeRecordingFile = null
            if (stoppedFile != null && stoppedFile.exists() && stoppedFile.length() > 0) {
                _pendingSaveFile.value = stoppedFile
            } else {
                recoveryManager.markSessionCompleted()
            }
        } else {
            startSession(destination = recoveryManager.createNewTakeFile(), append = false)
        }
    }

    fun confirmSaveTake(title: String) {
        val file = _pendingSaveFile.value ?: return
        val finalTitle = title.ifBlank {
            "Take_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
        }
        recoveryManager.commitRecording(file, finalTitle)
        _pendingSaveFile.value = null
        refreshLibrary()
    }

    fun discardTake() {
        val file = _pendingSaveFile.value ?: return
        file.delete()
        recoveryManager.markSessionCompleted()
        _pendingSaveFile.value = null
    }

    fun resumeInterruptedSession() {
        val session = _interruptedSession.value ?: return
        _interruptedSession.value = null
        startSession(destination = session.audioFile, append = true)
    }

    fun finalizeInterruptedSession() {
        val session = _interruptedSession.value ?: return
        _interruptedSession.value = null
        _pendingSaveFile.value = session.audioFile
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
}