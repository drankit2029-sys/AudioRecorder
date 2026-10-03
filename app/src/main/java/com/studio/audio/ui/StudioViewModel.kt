package com.studio.audio.ui

import android.app.Application
import android.media.AudioDeviceInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studio.audio.core.audio.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val deviceRegistry = AudioDeviceRegistry(application)
    private val diskWriter = AudioDiskWriter()
    private val captureEngine = AudioCaptureEngine(diskWriter)
    private val recoveryManager = SessionRecoveryManager(application)
    private val playerManager = LibraryPlayerManager()

    private val _currentDestination = MutableStateFlow(AppDestination.STUDIO)
    val currentDestination: StateFlow<AppDestination> = _currentDestination.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<AudioInputDevice>>(emptyList())
    val availableDevices: StateFlow<List<AudioInputDevice>> = _availableDevices.asStateFlow()

    private val _selectedDevice = MutableStateFlow<AudioInputDevice?>(null)
    val selectedDevice: StateFlow<AudioInputDevice?> = _selectedDevice.asStateFlow()

    private val _selectedPreset = MutableStateFlow<AudioPreset>(AudioPresetValidator.POPULAR_PRESETS.first())
    val selectedPreset: StateFlow<AudioPreset> = _selectedPreset.asStateFlow()

    private val _customPreset = MutableStateFlow(
        AudioPreset(
            id = "preset_custom",
            name = "Custom Preset",
            description = "User-configured audio attributes",
            sampleRate = 48000,
            channelCount = 2,
            bitDepth = BitDepth.BIT_24,
            format = AudioFormatType.WAV,
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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // Library Player Observable States
    val isPlayingAudio: StateFlow<Boolean> = playerManager.isPlaying
    val currentPlayingFile: StateFlow<File?> = playerManager.currentPlayingFile
    val playbackPositionMs: StateFlow<Long> = playerManager.currentPositionMs
    val playbackDurationMs: StateFlow<Long> = playerManager.totalDurationMs

    private var activeRecordingFile: File? = null

    private val routingManager = AudioRoutingManager(
        context = application,
        onDeviceListChanged = { refreshDevices() },
        onActiveDeviceDisconnected = { disconnectedDev -> handleActiveDeviceDisconnected(disconnectedDev) }
    )

    init {
        routingManager.startMonitoring()
        refreshDevices()
        refreshLibrary()
        checkForInterruptedSession()
    }

    override fun onCleared() {
        super.onCleared()
        routingManager.stopMonitoring()
        playerManager.release()
    }

    // Media Player Control Methods
    fun playRecording(recording: SavedRecording, startPositionMs: Long = 0L) {
        if (_isRecording.value) return
        playerManager.play(viewModelScope, recording.file, startPositionMs)
    }

    fun pausePlayback() {
        playerManager.pause()
    }

    fun seekPlayback(positionMs: Long) {
        playerManager.seekTo(viewModelScope, positionMs)
    }

    fun stopPlayback() {
        playerManager.stop()
    }

    private fun handleActiveDeviceDisconnected(device: AudioDeviceInfo) {
        if (_isRecording.value) {
            val stoppedFile = captureEngine.stopRecording()
            routingManager.teardownRouting()
            _isRecording.value = false
            activeRecordingFile = null

            if (stoppedFile != null && stoppedFile.exists() && stoppedFile.length() > 0) {
                _pendingSaveFile.value = stoppedFile
                _errorMessage.value = "Microphone '${device.productName}' disconnected. Audio saved and ready to rename."
            } else {
                recoveryManager.markSessionCompleted()
                _errorMessage.value = "Microphone '${device.productName}' disconnected before audio could be captured."
            }
        }
        refreshDevices()
    }

    fun dismissError() {
        _errorMessage.value = null
    }

    fun selectPreset(preset: AudioPreset) {
        _selectedPreset.value = preset
    }

    fun updateCustomPreset(
        sampleRate: Int,
        channelCount: Int,
        bitDepth: BitDepth?,
        format: AudioFormatType
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

    fun navigateTo(destination: AppDestination) {
        _currentDestination.value = destination
        if (destination == AppDestination.LIBRARY) {
            refreshLibrary()
        } else {
            playerManager.pause()
        }
    }

    fun refreshLibrary() {
        _savedRecordings.value = recoveryManager.getSavedRecordings()
    }

    fun deleteRecording(recording: SavedRecording) {
        if (playerManager.currentPlayingFile.value?.absolutePath == recording.file.absolutePath) {
            playerManager.stop()
        }
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
            viewModelScope.launch {
                routingManager.activateRoute(device.rawDeviceInfo)
                captureEngine.switchDevice(device.rawDeviceInfo)
            }
        }
    }

    fun toggleRecording() {
        if (_isRecording.value) {
            val stoppedFile = captureEngine.stopRecording()
            routingManager.teardownRouting()
            _isRecording.value = false
            activeRecordingFile = null

            if (stoppedFile != null && stoppedFile.exists() && stoppedFile.length() > 0) {
                _pendingSaveFile.value = stoppedFile
            } else {
                recoveryManager.markSessionCompleted()
                _errorMessage.value = "Recording stopped, but no audio samples were captured."
            }
        } else {
            playerManager.stop()
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
        playerManager.stop()
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
        val device = _selectedDevice.value?.rawDeviceInfo

        viewModelScope.launch {
            val routeSuccess = routingManager.activateRoute(device)
            if (!routeSuccess) {
                _errorMessage.value = "Failed to synchronize Bluetooth audio link. Please ensure your headset is connected and retry."
                return@launch
            }

            activeRecordingFile = destination
            val sampleRate = 48000
            recoveryManager.markSessionActive(destination, sampleRate)

            captureEngine.startRecording(
                scope = viewModelScope,
                targetDevice = device,
                sampleRate = sampleRate,
                destinationFile = destination,
                append = append,
                onError = { err ->
                    val stoppedFile = captureEngine.stopRecording()
                    routingManager.teardownRouting()
                    _isRecording.value = false
                    activeRecordingFile = null

                    if (stoppedFile != null && stoppedFile.exists() && stoppedFile.length() > 0) {
                        _pendingSaveFile.value = stoppedFile
                    } else {
                        recoveryManager.markSessionCompleted()
                    }

                    val msg = when (err) {
                        is RecordingError.InitializationFailed -> err.message
                        is RecordingError.ReadError -> err.message
                        is RecordingError.DeviceDisconnected -> "Mic disconnected: ${err.deviceName}"
                    }
                    _errorMessage.value = msg
                }
            )
            _isRecording.value = true
        }
    }
}