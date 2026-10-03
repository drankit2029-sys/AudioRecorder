package com.studio.audio.ui

import android.app.Application
import android.media.AudioDeviceInfo
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.studio.audio.core.audio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _recordingTimeMs = MutableStateFlow(0L)
    val recordingTimeMs: StateFlow<Long> = _recordingTimeMs.asStateFlow()

    private val _currentDbfs = MutableStateFlow(-60f)
    val currentDbfs: StateFlow<Float> = _currentDbfs.asStateFlow()

    private val _interruptedSession = MutableStateFlow<InterruptedSession?>(null)
    val interruptedSession: StateFlow<InterruptedSession?> = _interruptedSession.asStateFlow()

    private val _pendingSaveFile = MutableStateFlow<File?>(null)
    val pendingSaveFile: StateFlow<File?> = _pendingSaveFile.asStateFlow()

    private val _conversionProgress = MutableStateFlow<Float?>(null)
    val conversionProgress: StateFlow<Float?> = _conversionProgress.asStateFlow()

    private val _savedRecordings = MutableStateFlow<List<SavedRecording>>(emptyList())
    val savedRecordings: StateFlow<List<SavedRecording>> = _savedRecordings.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    val isPlayingAudio: StateFlow<Boolean> = playerManager.isPlaying
    val currentPlayingFile: StateFlow<File?> = playerManager.currentPlayingFile
    val playbackPositionMs: StateFlow<Long> = playerManager.currentPositionMs
    val playbackDurationMs: StateFlow<Long> = playerManager.totalDurationMs

    private var activeRecordingFile: File? = null
    private var timerJob: Job? = null

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
        timerJob?.cancel()
    }

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
            timerJob?.cancel()
            val stoppedFile = captureEngine.stopRecording()
            routingManager.teardownRouting()
            _isRecording.value = false
            _isPaused.value = false
            _currentDbfs.value = -60f
            activeRecordingFile = null

            if (stoppedFile != null && stoppedFile.exists() && stoppedFile.length() > 0) {
                _pendingSaveFile.value = stoppedFile
                _errorMessage.value = "Microphone '${device.productName}' disconnected. Audio saved and ready to convert."
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
        if (_isRecording.value) return
        _selectedPreset.value = preset
    }

    fun updateCustomPreset(
        sampleRate: Int,
        channelCount: Int,
        bitDepth: BitDepth?,
        format: AudioFormatType
    ) {
        if (_isRecording.value) return
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
        if (_isRecording.value) return
        _selectedDevice.value = device
    }

    fun startRecordingTake() {
        if (_isRecording.value) return
        playerManager.stop()
        startSession(destination = recoveryManager.createNewTakeFile(), append = false)
    }

    fun togglePauseResume() {
        if (!_isRecording.value) return
        if (_isPaused.value) {
            captureEngine.resume()
            _isPaused.value = false
        } else {
            captureEngine.pause()
            _isPaused.value = true
            _currentDbfs.value = -60f
        }
    }

    fun stopAndSaveRecording() {
        if (!_isRecording.value) return
        timerJob?.cancel()
        timerJob = null

        val stoppedFile = captureEngine.stopRecording()
        routingManager.teardownRouting()
        _isRecording.value = false
        _isPaused.value = false
        _currentDbfs.value = -60f
        activeRecordingFile = null

        if (stoppedFile != null && stoppedFile.exists() && stoppedFile.length() > 0) {
            _pendingSaveFile.value = stoppedFile
        } else {
            recoveryManager.markSessionCompleted()
            _errorMessage.value = "Recording stopped, but no audio samples were captured."
        }
    }

    fun confirmSaveTake(title: String) {
        val file = _pendingSaveFile.value ?: return
        val finalTitle = title.ifBlank {
            "Take_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
        }
        val preset = _selectedPreset.value

        _pendingSaveFile.value = null
        _conversionProgress.value = 0f

        viewModelScope.launch(Dispatchers.IO) {
            recoveryManager.commitRecording(
                tempFile = file,
                userTitle = finalTitle,
                sampleRate = preset.sampleRate,
                channelCount = preset.channelCount,
                preset = preset,
                onProgress = { progress ->
                    _conversionProgress.value = progress
                }
            )
            _conversionProgress.value = null
            refreshLibrary()
        }
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
        val preset = _selectedPreset.value

        viewModelScope.launch {
            val routeSuccess = routingManager.activateRoute(device)
            if (!routeSuccess) {
                _errorMessage.value = "Failed to synchronize Bluetooth audio link. Please ensure your headset is connected and retry."
                return@launch
            }

            activeRecordingFile = destination
            recoveryManager.markSessionActive(destination, preset.sampleRate, preset.channelCount)

            captureEngine.startRecording(
                scope = viewModelScope,
                targetDevice = device,
                sampleRate = preset.sampleRate,
                channelCount = preset.channelCount,
                destinationFile = destination,
                append = append,
                onDbfsUpdate = { level ->
                    _currentDbfs.value = level
                },
                onError = { err ->
                    timerJob?.cancel()
                    val stoppedFile = captureEngine.stopRecording()
                    routingManager.teardownRouting()
                    _isRecording.value = false
                    _isPaused.value = false
                    _currentDbfs.value = -60f
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
            _isPaused.value = false
            _recordingTimeMs.value = 0L

            timerJob?.cancel()
            timerJob = viewModelScope.launch {
                while (_isRecording.value && isActive) {
                    delay(100L)
                    if (!_isPaused.value) {
                        _recordingTimeMs.value += 100L
                    }
                }
            }
        }
    }
}