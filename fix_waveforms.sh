#!/bin/sh
set -e

BASE="app/src/main/java/com/example/audiorecorder"

echo "==> 1. Updating AudioCaptureEngine.kt with linear peak extraction..."
cat << 'CAPTURE' > "$BASE/audio/engine/AudioCaptureEngine.kt"
package com.example.audiorecorder.audio.engine

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.example.audiorecorder.audio.dsp.DbfsCalculator
import com.example.audiorecorder.audio.hardware.DiscoveredMic
import com.example.audiorecorder.audio.scratchpad.ScratchpadManager
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

interface AudioCaptureListener {
    fun onAudioFrame(peakDbfs: Float, rmsDbfs: Float, peakLinear: Float)
    fun onError(errorMessage: String)
}

class AudioCaptureEngine(
    private val scratchpadManager: ScratchpadManager,
    private val listener: AudioCaptureListener? = null
) {

    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    @SuppressLint("MissingPermission")
    fun startCapture(
        sampleRate: Int,
        channels: Int,
        targetMic: DiscoveredMic? = null
    ): Boolean {
        if (isRecording.get()) return true

        val channelConfig = if (channels == 2) {
            AudioFormat.CHANNEL_IN_STEREO
        } else {
            AudioFormat.CHANNEL_IN_MONO
        }

        val audioFormat = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelConfig)
            .build()

        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            channelConfig,
            AudioFormat.ENCODING_PCM_FLOAT
        )

        if (minBufferSize <= 0) {
            listener?.onError("Invalid hardware audio buffer configuration.")
            return false
        }

        val bufferSize = minBufferSize * 2

        try {
            val recordInstance = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(audioFormat)
                .setBufferSizeInBytes(bufferSize)
                .build()

            if (targetMic != null) {
                recordInstance.preferredDevice = targetMic.rawDeviceInfo
            }

            if (recordInstance.state != AudioRecord.STATE_INITIALIZED) {
                listener?.onError("Failed to initialize AudioRecord instance.")
                recordInstance.release()
                return false
            }

            audioRecord = recordInstance
            scratchpadManager.openSession()
            recordInstance.startRecording()
            isRecording.set(true)
            isPaused.set(false)

            recordingThread = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
                captureLoop(bufferSize / 4)
            }, "AudioCaptureThread").apply {
                start()
            }

            return true
        } catch (e: Exception) {
            listener?.onError("Error starting audio capture: ${e.message}")
            release()
            return false
        }
    }

    private fun captureLoop(floatChunkSize: Int) {
        val record = audioRecord ?: return
        val floatBuffer = FloatArray(floatChunkSize)
        var lastMetricsPostTime = 0L

        while (isRecording.get()) {
            if (isPaused.get()) {
                try {
                    Thread.sleep(20)
                } catch (_: InterruptedException) {
                    break
                }
                continue
            }

            val readCount = record.read(floatBuffer, 0, floatChunkSize, AudioRecord.READ_BLOCKING)

            if (readCount > 0) {
                scratchpadManager.writeFloats(floatBuffer, readCount)

                val now = System.currentTimeMillis()
                // Throttle emission to ~40Hz (every 25ms) for visualizer stability
                if (now - lastMetricsPostTime >= 25) {
                    lastMetricsPostTime = now

                    var peakLinear = 0.0f
                    for (i in 0 until readCount) {
                        val sampleAbs = abs(floatBuffer[i])
                        if (sampleAbs > peakLinear) {
                            peakLinear = sampleAbs
                        }
                    }

                    val peakDbfs = DbfsCalculator.calculatePeakDbfs(floatBuffer, readCount)
                    val rmsDbfs = DbfsCalculator.calculateRmsDbfs(floatBuffer, readCount)
                    listener?.onAudioFrame(peakDbfs, rmsDbfs, peakLinear)
                }
            } else if (readCount < 0) {
                listener?.onError("AudioRecord read error code: $readCount")
                break
            }
        }
    }

    fun pauseCapture() { isPaused.set(true) }
    fun resumeCapture() { isPaused.set(false) }

    fun stopCapture() {
        isRecording.set(false)
        isPaused.set(false)

        try {
            recordingThread?.join(1000)
        } catch (_: InterruptedException) {}
        recordingThread = null

        try {
            audioRecord?.stop()
        } catch (_: Exception) {}

        scratchpadManager.sync()
        release()
    }

    fun release() {
        try {
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
    }

    fun isRecording(): Boolean = isRecording.get()
    fun isPaused(): Boolean = isPaused.get()
}
CAPTURE

echo "==> 2. Updating AudioRecordingService.kt..."
cat << 'SERVICE' > "$BASE/audio/engine/AudioRecordingService.kt"
package com.example.audiorecorder.audio.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.audiorecorder.MainActivity
import com.example.audiorecorder.audio.hardware.AudioPreset
import com.example.audiorecorder.audio.hardware.DiscoveredMic
import com.example.audiorecorder.audio.scratchpad.CrashRecoverySentinel
import com.example.audiorecorder.audio.scratchpad.ScratchpadManager
import com.example.audiorecorder.util.TimecodeFormatter
import java.io.File

class AudioRecordingService : Service(), AudioCaptureListener {

    private val binder = LocalBinder()
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var scratchpadManager: ScratchpadManager
    private lateinit var captureEngine: AudioCaptureEngine
    private lateinit var crashSentinel: CrashRecoverySentinel
    private lateinit var playbackEngine: AudioPlaybackEngine

    private var activePreset: AudioPreset = AudioPreset.STANDARD_PODCAST
    private var activeMic: DiscoveredMic? = null

    var serviceListener: AudioCaptureListener? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioRecordingService = this@AudioRecordingService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        crashSentinel = CrashRecoverySentinel(this)
        scratchpadManager = ScratchpadManager(crashSentinel.rawFile)
        captureEngine = AudioCaptureEngine(scratchpadManager, this)
        playbackEngine = AudioPlaybackEngine(this)

        acquireWakeLock()
        createNotificationChannel()
    }

    fun startRecording(preset: AudioPreset, mic: DiscoveredMic?): Boolean {
        activePreset = preset
        activeMic = mic

        crashSentinel.saveSessionState(
            sampleRate = preset.sampleRate,
            channels = preset.channels,
            bitDepth = preset.bitDepth
        )

        val success = captureEngine.startCapture(preset.sampleRate, preset.channels, mic)
        if (success) {
            val notification = buildNotification("Recording active...", "00:00.000")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
        return success
    }

    fun pauseRecording() {
        captureEngine.pauseCapture()
        updateNotification("Recording paused", TimecodeFormatter.formatMillis(getElapsedMillis()))
    }

    fun resumeRecording() {
        captureEngine.resumeCapture()
        updateNotification("Recording active...", TimecodeFormatter.formatMillis(getElapsedMillis()))
    }

    fun stopRecording(): File {
        captureEngine.stopCapture()
        stopForeground(STOP_FOREGROUND_REMOVE)
        return crashSentinel.rawFile
    }

    fun getElapsedMillis(): Long {
        val totalBytes = scratchpadManager.getTotalAudioBytes()
        val totalSamples = totalBytes / (activePreset.channels * 4L)
        return TimecodeFormatter.samplesToMillis(totalSamples, activePreset.sampleRate)
    }

    fun getScratchpadManager(): ScratchpadManager = scratchpadManager
    fun getPlaybackEngine(): AudioPlaybackEngine = playbackEngine
    fun getCrashSentinel(): CrashRecoverySentinel = crashSentinel

    override fun onAudioFrame(peakDbfs: Float, rmsDbfs: Float, peakLinear: Float) {
        serviceListener?.onAudioFrame(peakDbfs, rmsDbfs, peakLinear)
    }

    override fun onError(errorMessage: String) {
        serviceListener?.onError(errorMessage)
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AudioRecorder::RecordingWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(12 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Studio Session",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing audio studio recording process"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String, timeText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Audio Studio ($status)")
            .setContentText("Duration: $timeText")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(status: String, timeText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(status, timeText))
    }

    override fun onDestroy() {
        super.onDestroy()
        captureEngine.release()
        playbackEngine.release()
        releaseWakeLock()
    }

    companion object {
        const val CHANNEL_ID = "recording_service_channel"
        const val NOTIFICATION_ID = 4041
    }
}
SERVICE

echo "==> 3. Rewriting WaveformVisualizerView.kt..."
cat << 'WAVE' > "$BASE/ui/customviews/WaveformVisualizerView.kt"
package com.example.audiorecorder.ui.customviews

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

interface WaveformScrubListener {
    fun onScrubStart()
    fun onScrubbing(peakIndex: Int)
    fun onScrubStop(finalPeakIndex: Int)
}

class WaveformVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = context.resources.displayMetrics.density

    private val playedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#00E5FF")
        strokeCap = Paint.Cap.ROUND
    }

    private val unplayedBarPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#455A64")
        strokeCap = Paint.Cap.ROUND
    }

    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF3D71")
        strokeWidth = 2.5f * density
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1F2429")
        strokeWidth = 1f * density
    }

    private val peaks = ArrayList<Float>()
    private var playheadIndex = 0
    private var zoomFactor = 1.0f // 0.5f to 3.0f

    var scrubListener: WaveformScrubListener? = null
    private var isScrubbing = false
    private var lastTouchX = 0f

    fun setPeaks(initialPeaks: List<Float>) {
        peaks.clear()
        peaks.addAll(initialPeaks)
        playheadIndex = peaks.size
        postInvalidate()
    }

    fun addLivePeak(peak: Float) {
        peaks.add(min(1.0f, max(0.0f, peak)))
        playheadIndex = peaks.size
        postInvalidateOnAnimation()
    }

    fun setPlayheadIndex(index: Int) {
        playheadIndex = max(0, min(peaks.size, index))
        postInvalidateOnAnimation()
    }

    fun clear() {
        peaks.clear()
        playheadIndex = 0
        postInvalidate()
    }

    fun zoomIn() {
        zoomFactor = min(3.0f, zoomFactor * 1.25f)
        postInvalidate()
    }

    fun zoomOut() {
        zoomFactor = max(0.5f, zoomFactor / 1.25f)
        postInvalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isScrubbing = true
                lastTouchX = event.x
                scrubListener?.onScrubStart()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isScrubbing) {
                    val deltaX = event.x - lastTouchX
                    lastTouchX = event.x

                    val stepPx = (4.0f * density) * zoomFactor
                    val indexDelta = (deltaX / stepPx).toInt()
                    if (indexDelta != 0) {
                        playheadIndex = max(0, min(peaks.size, playheadIndex - indexDelta))
                        scrubListener?.onScrubbing(playheadIndex)
                        postInvalidate()
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isScrubbing) {
                    isScrubbing = false
                    scrubListener?.onScrubStop(playheadIndex)
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val centerY = h / 2f
        val centerX = w / 2f

        // Center timeline axis
        canvas.drawLine(0f, centerY, w, centerY, baselinePaint)

        val barWidth = 2.5f * density * zoomFactor
        val barGap = 1.5f * density * zoomFactor
        val step = barWidth + barGap

        playedBarPaint.strokeWidth = barWidth
        unplayedBarPaint.strokeWidth = barWidth

        val maxHalfAmp = (h * 0.44f)

        // Draw waveform bars scrolling past the fixed center playhead
        for (i in peaks.indices) {
            val x = centerX + ((i - playheadIndex) * step)
            if (x < -10f || x > w + 10f) continue

            // Perceptual dynamic expansion: pow(peak, 0.6)
            val linearPeak = peaks[i]
            val scaledPeak = linearPeak.toDouble().pow(0.6).toFloat()
            val amp = max(2f * density, scaledPeak * maxHalfAmp)

            val paint = if (i <= playheadIndex) playedBarPaint else unplayedBarPaint
            canvas.drawLine(x, centerY - amp, x, centerY + amp, paint)
        }

        // Stationary Center Playhead
        canvas.drawLine(centerX, 0f, centerX, h, playheadPaint)
    }
}
WAVE

echo "==> 4. Updating StudioViewModel.kt..."
cat << 'STUDIO_VM' > "$BASE/ui/studio/StudioViewModel.kt"
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
STUDIO_VM

echo "==> 5. Updating StudioFragment.kt..."
cat << 'STUDIO_FRAG' > "$BASE/ui/studio/StudioFragment.kt"
package com.example.audiorecorder.ui.studio

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.audiorecorder.MainActivity
import com.example.audiorecorder.audio.hardware.MicrophoneManager
import com.example.audiorecorder.databinding.FragmentStudioBinding
import com.example.audiorecorder.ui.customviews.WaveformScrubListener
import com.example.audiorecorder.util.TimecodeFormatter
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class StudioFragment : Fragment(), WaveformScrubListener {

    private var _binding: FragmentStudioBinding? = null
    private val binding get() = _binding!!
    private val viewModel: StudioViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStudioBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.waveformVisualizerView.scrubListener = this

        binding.waveformVisualizerView.setPeaks(viewModel.waveformPeaks.value)

        setupPrompterControls()
        setupActionPills()
        setupZoomControls()
        observeStudioState()
    }

    private fun setupPrompterControls() {
        binding.btnScript.setOnClickListener { showScriptEditDialog() }
        binding.btnScrollToggle.setOnClickListener {
            viewModel.togglePrompterAutoScroll()
            if (viewModel.isPrompterAutoScrolling.value) {
                binding.teleprompterView.startAutoScroll()
                binding.btnScrollToggle.setTextColor(0xFF00E676.toInt())
            } else {
                binding.teleprompterView.pauseAutoScroll()
                binding.btnScrollToggle.setTextColor(0xFFFFFFFF.toInt())
            }
        }
        binding.btnMirror.setOnClickListener { binding.teleprompterView.toggleMirror() }

        binding.tvWordsInc.setOnClickListener { viewModel.adjustWordsPerLine(1) }
        binding.tvWordsDec.setOnClickListener { viewModel.adjustWordsPerLine(-1) }
        binding.tvSpeedInc.setOnClickListener {
            viewModel.adjustScrollSpeed(0.5f)
            binding.teleprompterView.setScrollSpeed(viewModel.scrollSpeed.value)
        }
        binding.tvSpeedDec.setOnClickListener {
            viewModel.adjustScrollSpeed(-0.5f)
            binding.teleprompterView.setScrollSpeed(viewModel.scrollSpeed.value)
        }
        binding.tvFontInc.setOnClickListener {
            viewModel.adjustFontSize(2f)
            binding.teleprompterView.setFontSize(viewModel.fontSizeSp.value)
        }
        binding.tvFontDec.setOnClickListener {
            viewModel.adjustFontSize(-2f)
            binding.teleprompterView.setFontSize(viewModel.fontSizeSp.value)
        }
    }

    private fun setupActionPills() {
        binding.btnMicSelector.setOnClickListener { showMicrophonePicker() }
        binding.btnPresetSelector.setOnClickListener { }
        binding.btnPrompterToggle.setOnClickListener {
            viewModel.togglePrompterVisibility()
            binding.layoutPrompterContainer.visibility =
                if (viewModel.isPrompterVisible.value) View.VISIBLE else View.GONE
        }
        binding.btnModeToggle.setOnClickListener {
            viewModel.togglePunchMode()
            val mode = viewModel.punchMode.value
            binding.btnModeToggle.text = if (mode == PunchMode.REPLACE) "⎌ Replace" else "▶ Preview"
            binding.btnModeToggle.setTextColor(if (mode == PunchMode.REPLACE) 0xFFFF5252.toInt() else 0xFF00E676.toInt())
        }
    }

    private fun setupZoomControls() {
        binding.btnZoomIn.setOnClickListener { binding.waveformVisualizerView.zoomIn() }
        binding.btnZoomOut.setOnClickListener { binding.waveformVisualizerView.zoomOut() }
    }

    private fun observeStudioState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.elapsedMillis.collectLatest { ms ->
                        binding.tvTimecode.text = TimecodeFormatter.formatMillis(ms)
                        // In playback or non-recording states, advance visualizer playhead
                        if (viewModel.studioState.value != StudioState.RECORDING) {
                            val targetIndex = (ms / 25L).toInt()
                            binding.waveformVisualizerView.setPlayheadIndex(targetIndex)
                        }
                    }
                }
                launch {
                    viewModel.newPeakEvent.collect { peak ->
                        binding.waveformVisualizerView.addLivePeak(peak)
                    }
                }
                launch {
                    viewModel.peakDbfs.collectLatest { peak ->
                        binding.dbfsMeterView.setLevels(viewModel.rmsDbfs.value, peak)
                    }
                }
                launch {
                    viewModel.wordsPerLine.collectLatest { words ->
                        binding.tvWordsValue.text = "$words w/l"
                    }
                }
                launch {
                    viewModel.prompterScript.collectLatest { script ->
                        binding.teleprompterView.setScript(script)
                    }
                }
            }
        }
    }

    private fun showScriptEditDialog() {
        val input = EditText(requireContext()).apply {
            setText(viewModel.prompterScript.value)
            setLines(6)
        }
        AlertDialog.Builder(requireContext())
            .setTitle("Edit Prompter Script")
            .setView(input)
            .setPositiveButton("Apply") { _, _ ->
                viewModel.setPrompterScript(input.text.toString())
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMicrophonePicker() {
        val micMgr = MicrophoneManager(requireContext())
        val mics = micMgr.enumerateMicrophones()
        val names = mics.map { it.name }.toTypedArray()

        AlertDialog.Builder(requireContext())
            .setTitle("Select Audio Input")
            .setItems(names) { _, which ->
                val chosen = mics[which]
                viewModel.setSelectedMic(chosen)
                binding.btnMicSelector.text = "🎙 ${chosen.typeName}"
            }
            .show()
    }

    override fun onScrubStart() {
        (activity as? MainActivity)?.pauseActiveAudioForScrub()
    }

    override fun onScrubbing(peakIndex: Int) {
        val ms = peakIndex.toLong() * 25L
        viewModel.setElapsedMillis(ms)
    }

    override fun onScrubStop(finalPeakIndex: Int) {
        val preset = viewModel.selectedPreset.value
        val sampleOffset = (finalPeakIndex.toLong() * 25L * preset.sampleRate) / 1000L
        (activity as? MainActivity)?.seekScratchpadToSample(sampleOffset, preset.channels)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
STUDIO_FRAG

echo "==> 6. Updating MainActivity.kt with audio frame callbacks & WAV extraction..."
cat << 'MAIN_ACT' > "$BASE/MainActivity.kt"
package com.example.audiorecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.example.audiorecorder.audio.engine.AudioCaptureListener
import com.example.audiorecorder.audio.engine.AudioRecordingService
import com.example.audiorecorder.data.db.RecordingEntity
import com.example.audiorecorder.data.repository.RecordingRepository
import com.example.audiorecorder.databinding.ActivityMainBinding
import com.example.audiorecorder.ui.library.LibraryFragment
import com.example.audiorecorder.ui.studio.PunchMode
import com.example.audiorecorder.ui.studio.StudioFragment
import com.example.audiorecorder.ui.studio.StudioState
import com.example.audiorecorder.ui.studio.StudioViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

class MainActivity : AppCompatActivity(), AudioCaptureListener {

    private lateinit var binding: ActivityMainBinding
    private val studioViewModel: StudioViewModel by viewModels()

    private var recordingService: AudioRecordingService? = null
    private var isServiceBound = false

    private val libraryFragment = LibraryFragment()
    private val studioFragment = StudioFragment()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioRecordingService.LocalBinder
            recordingService = binder.getService()
            recordingService?.serviceListener = this@MainActivity
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            isServiceBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (!recordGranted) {
            Toast.makeText(this, "Microphone permission required for audio studio.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applySystemWindowInsets()
        checkPermissions()
        bindRecordingService()
        setupNavigation()
        setupRecordDock()

        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, studioFragment)
            .commit()
    }

    private fun applySystemWindowInsets() {
        val initialDockBottomPadding = binding.bottomDock.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.fragmentContainer.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right
            )
            binding.bottomDock.updatePadding(
                bottom = initialDockBottomPadding + systemBars.bottom
            )
            windowInsets
        }
    }

    private fun checkPermissions() {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun bindRecordingService() {
        val intent = Intent(this, AudioRecordingService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun setupNavigation() {
        binding.btnNavLibrary.setOnClickListener {
            binding.btnNavLibrary.setTextColor(0xFF448AFF.toInt())
            binding.btnNavStudio.setTextColor(0xFFFFFFFF.toInt())
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, libraryFragment)
                .commit()
        }

        binding.btnNavStudio.setOnClickListener {
            binding.btnNavStudio.setTextColor(0xFF448AFF.toInt())
            binding.btnNavLibrary.setTextColor(0xFFFFFFFF.toInt())
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, studioFragment)
                .commit()
        }
    }

    private fun setupRecordDock() {
        binding.fabRecord.setOnClickListener {
            startStudioRecording()
        }

        binding.fabPauseResume.setOnClickListener {
            val state = studioViewModel.studioState.value
            if (state == StudioState.RECORDING) {
                recordingService?.pauseRecording()
                studioViewModel.setStudioState(StudioState.PAUSED)
                binding.fabPauseResume.setImageResource(android.R.drawable.ic_media_play)
            } else if (state == StudioState.PAUSED) {
                recordingService?.resumeRecording()
                studioViewModel.setStudioState(StudioState.RECORDING)
                binding.fabPauseResume.setImageResource(android.R.drawable.ic_media_pause)
            }
        }

        binding.fabStop.setOnClickListener {
            stopStudioRecording()
        }
    }

    private fun startStudioRecording() {
        val service = recordingService ?: return
        val preset = studioViewModel.selectedPreset.value
        val mic = studioViewModel.selectedMic.value

        // Starting a fresh take clears existing waveform
        studioViewModel.clearWaveform()

        val success = service.startRecording(preset, mic)
        if (success) {
            studioViewModel.setStudioState(StudioState.RECORDING)
            binding.fabRecord.visibility = View.GONE
            binding.layoutActiveControls.visibility = View.VISIBLE
            binding.fabPauseResume.setImageResource(android.R.drawable.ic_media_pause)
        } else {
            Toast.makeText(this, "Could not start audio capture engine.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopStudioRecording() {
        val service = recordingService ?: return
        val rawFile = service.stopRecording()
        val preset = studioViewModel.selectedPreset.value
        val scratchpad = service.getScratchpadManager()

        if (studioViewModel.punchMode.value == PunchMode.REPLACE) {
            scratchpad.spliceTailBack(preset.sampleRate, preset.channels)
        }

        val recordingsDir = File(getExternalFilesDir(null), "recordings").apply { if (!exists()) mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val wavName = "Take_$timeStamp.wav"
        val destWav = File(recordingsDir, wavName)

        scratchpad.exportToFloatWav(destWav, preset.sampleRate, preset.channels)
        service.getCrashSentinel().clearSession()

        val durationMs = service.getElapsedMillis()
        val entity = RecordingEntity(
            title = "Take $timeStamp",
            filePath = destWav.absolutePath,
            durationMs = durationMs,
            sampleRate = preset.sampleRate,
            bitDepth = 32,
            channelCount = preset.channels,
            format = "WAV",
            fileSize = destWav.length()
        )

        CoroutineScope(Dispatchers.IO).launch {
            RecordingRepository.getInstance(this@MainActivity).insertRecording(entity)
        }

        studioViewModel.setStudioState(StudioState.IDLE)
        binding.layoutActiveControls.visibility = View.GONE
        binding.fabRecord.visibility = View.VISIBLE
        studioViewModel.setElapsedMillis(0L)

        Toast.makeText(this, "Take saved to Library!", Toast.LENGTH_SHORT).show()
    }

    fun pauseActiveAudioForScrub() {
        if (studioViewModel.studioState.value == StudioState.RECORDING) {
            recordingService?.pauseRecording()
            studioViewModel.setStudioState(StudioState.PAUSED)
            binding.fabPauseResume.setImageResource(android.R.drawable.ic_media_play)
        }
    }

    fun seekScratchpadToSample(sampleIndex: Long, channels: Int) {
        recordingService?.getScratchpadManager()?.seekToSample(sampleIndex, channels)
    }

    fun playRecordingPreview(recording: RecordingEntity) {
        val file = File(recording.filePath)
        recordingService?.getPlaybackEngine()?.startPlayback(
            scratchFile = file,
            startSample = 0L,
            sampleRate = recording.sampleRate,
            channels = recording.channelCount
        )
    }

    fun loadRecordingIntoStudio(recording: RecordingEntity) {
        CoroutineScope(Dispatchers.IO).launch {
            val extractedPeaks = extractWaveformPeaksFromWav(File(recording.filePath), recording.sampleRate, recording.channelCount)
            withContext(Dispatchers.Main) {
                studioViewModel.setWaveformPeaks(extractedPeaks)
                studioViewModel.setElapsedMillis(recording.durationMs)
                binding.btnNavStudio.performClick()
                Toast.makeText(this@MainActivity, "Loaded '${recording.title}'", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun extractWaveformPeaksFromWav(file: File, sampleRate: Int, channels: Int): List<Float> {
        val peaksList = ArrayList<Float>()
        if (!file.exists() || file.length() <= 44L) return peaksList

        try {
            FileInputStream(file).use { fis ->
                fis.skip(44) // Skip RIFF WAV header
                val samplesPerWindow = ((sampleRate * 0.025f) * channels).toInt()
                val byteChunkSize = samplesPerWindow * 4
                val byteBuf = ByteArray(byteChunkSize)
                val direct = ByteBuffer.wrap(byteBuf).order(ByteOrder.LITTLE_ENDIAN)

                var read: Int
                while (fis.read(byteBuf).also { read = it } > 0) {
                    val floatsRead = read / 4
                    direct.position(0)
                    var peak = 0.0f
                    for (i in 0 until floatsRead) {
                        val s = abs(direct.getFloat())
                        if (s > peak) peak = s
                    }
                    peaksList.add(peak)
                }
            }
        } catch (_: Exception) {}
        return peaksList
    }

    override fun onAudioFrame(peakDbfs: Float, rmsDbfs: Float, peakLinear: Float) {
        runOnUiThread {
            studioViewModel.updateDbfs(peakDbfs, rmsDbfs)
            if (studioViewModel.studioState.value == StudioState.RECORDING) {
                studioViewModel.addLivePeak(peakLinear)
            }
            recordingService?.let {
                studioViewModel.setElapsedMillis(it.getElapsedMillis())
            }
        }
    }

    override fun onError(errorMessage: String) {
        runOnUiThread {
            Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
        }
    }
}
MAIN_ACT

echo "==> 7. Rebuilding and deploying..."
./deploy.sh "Fix blank waveforms with linear 40Hz peak sampling and perceptual scaling"
