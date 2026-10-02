package com.example.audiorecorder.ui.studio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.audiorecorder.audio.hardware.AudioPreset
import com.example.audiorecorder.audio.hardware.DiscoveredMic
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

enum class StudioState { IDLE, RECORDING, PAUSED, PREVIEWING }
enum class PunchMode { PREVIEW, REPLACE }

class StudioViewModel(application: Application) : AndroidViewModel(application) {

    private val _studioState = MutableStateFlow(StudioState.IDLE)
    val studioState: StateFlow<StudioState> = _studioState.asStateFlow()

    private val _punchMode = MutableStateFlow(PunchMode.PREVIEW)
    val punchMode: StateFlow<PunchMode> = _punchMode.asStateFlow()

    private val _selectedMic = MutableStateFlow<DiscoveredMic?>(null)
    val selectedMic: StateFlow<DiscoveredMic?> = _selectedMic.asStateFlow()

    private val _selectedPreset = MutableStateFlow(AudioPreset.STANDARD_PODCAST)
    val selectedPreset: StateFlow<AudioPreset> = _selectedPreset.asStateFlow()

    private val _peakDbfs = MutableStateFlow(-60.0f)
    val peakDbfs: StateFlow<Float> = _peakDbfs.asStateFlow()

    private val _rmsDbfs = MutableStateFlow(-60.0f)
    val rmsDbfs: StateFlow<Float> = _rmsDbfs.asStateFlow()

    private val _elapsedMillis = MutableStateFlow(0L)
    val elapsedMillis: StateFlow<Long> = _elapsedMillis.asStateFlow()

    // Waveform peak stream & history
    private val _waveformPeaks = MutableStateFlow<List<Float>>(emptyList())
    val waveformPeaks: StateFlow<List<Float>> = _waveformPeaks.asStateFlow()

    private val _newPeakEvent = MutableSharedFlow<Float>(extraBufferCapacity = 128)
    val newPeakEvent: SharedFlow<Float> = _newPeakEvent.asSharedFlow()

    // Teleprompter state
    private val _isPrompterVisible = MutableStateFlow(true)
    val isPrompterVisible: StateFlow<Boolean> = _isPrompterVisible.asStateFlow()

    private val _prompterScript = MutableStateFlow("Welcome to Audio Studio. Tap 'Script' to edit or import a file.")
    val prompterScript: StateFlow<String> = _prompterScript.asStateFlow()

    private val _isPrompterAutoScrolling = MutableStateFlow(false)
    val isPrompterAutoScrolling: StateFlow<Boolean> = _isPrompterAutoScrolling.asStateFlow()

    private val _wordsPerLine = MutableStateFlow(8)
    val wordsPerLine: StateFlow<Int> = _wordsPerLine.asStateFlow()

    private val _scrollSpeed = MutableStateFlow(1.5f)
    val scrollSpeed: StateFlow<Float> = _scrollSpeed.asStateFlow()

    private val _fontSizeSp = MutableStateFlow(22f)
    val fontSizeSp: StateFlow<Float> = _fontSizeSp.asStateFlow()

    fun setStudioState(state: StudioState) { _studioState.value = state }
    fun setPunchMode(mode: PunchMode) { _punchMode.value = mode }
    fun togglePunchMode() {
        _punchMode.value = if (_punchMode.value == PunchMode.PREVIEW) PunchMode.REPLACE else PunchMode.PREVIEW
    }

    fun setSelectedMic(mic: DiscoveredMic?) { _selectedMic.value = mic }
    fun setSelectedPreset(preset: AudioPreset) { _selectedPreset.value = preset }

    fun updateDbfs(peak: Float, rms: Float) {
        _peakDbfs.value = peak
        _rmsDbfs.value = rms
    }

    fun addLivePeak(peak: Float) {
        val current = _waveformPeaks.value.toMutableList()
        current.add(peak)
        _waveformPeaks.value = current
        _newPeakEvent.tryEmit(peak)
    }

    fun setWaveformPeaks(peaks: List<Float>) {
        _waveformPeaks.value = peaks
    }

    fun clearWaveform() {
        _waveformPeaks.value = emptyList()
    }

    fun setElapsedMillis(ms: Long) { _elapsedMillis.value = ms }

    fun togglePrompterVisibility() { _isPrompterVisible.value = !_isPrompterVisible.value }
    fun setPrompterScript(text: String) { _prompterScript.value = text }
    fun togglePrompterAutoScroll() { _isPrompterAutoScrolling.value = !_isPrompterAutoScrolling.value }

    fun adjustWordsPerLine(delta: Int) {
        _wordsPerLine.value = max(3, min(25, _wordsPerLine.value + delta))
    }

    fun adjustScrollSpeed(delta: Float) {
        _scrollSpeed.value = max(0.5f, min(10.0f, _scrollSpeed.value + delta))
    }

    fun adjustFontSize(delta: Float) {
        _fontSizeSp.value = max(12f, min(48f, _fontSizeSp.value + delta))
    }
}
