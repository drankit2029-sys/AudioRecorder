package com.example.audiorecorder

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.ColorStateList
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.audiorecorder.audio.engine.AudioCaptureListener
import com.example.audiorecorder.audio.engine.AudioPlaybackListener
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
import kotlinx.coroutines.flow.collectLatest
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

class MainActivity : AppCompatActivity(), AudioCaptureListener, AudioPlaybackListener {

    private lateinit var binding: ActivityMainBinding
    private val studioViewModel: StudioViewModel by viewModels()

    private var recordingService: AudioRecordingService? = null
    private var isServiceBound = false

    private val libraryFragment = LibraryFragment()
    private val studioFragment = StudioFragment()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioRecordingService.LocalBinder
            val boundService = binder.getService()
            recordingService = boundService
            boundService.serviceListener = this@MainActivity
            boundService.getPlaybackEngine().listener = this@MainActivity
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
        observePunchAndStudioMode()

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

    private fun observePunchAndStudioMode() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    studioViewModel.punchMode.collectLatest { updateDockControls() }
                }
                launch {
                    studioViewModel.studioState.collectLatest { updateDockControls() }
                }
            }
        }
    }

    private fun updateDockControls() {
        val state = studioViewModel.studioState.value
        val mode = studioViewModel.punchMode.value

        if (state == StudioState.RECORDING || state == StudioState.PAUSED) {
            binding.fabRecord.visibility = View.GONE
            binding.layoutActiveControls.visibility = View.VISIBLE
            binding.fabPauseResume.setImageResource(
                if (state == StudioState.RECORDING) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            )
        } else {
            binding.layoutActiveControls.visibility = View.GONE
            binding.fabRecord.visibility = View.VISIBLE

            if (mode == PunchMode.PREVIEW) {
                if (state == StudioState.PREVIEWING) {
                    binding.fabRecord.setImageResource(android.R.drawable.ic_media_pause)
                    binding.fabRecord.backgroundTintList = ColorStateList.valueOf(0xFF00E5FF.toInt())
                } else {
                    binding.fabRecord.setImageResource(android.R.drawable.ic_media_play)
                    binding.fabRecord.backgroundTintList = ColorStateList.valueOf(0xFF00E5FF.toInt())
                }
            } else {
                binding.fabRecord.setImageResource(android.R.drawable.ic_btn_speak_now)
                binding.fabRecord.backgroundTintList = ColorStateList.valueOf(0xFFFF1744.toInt())
            }
        }
    }

    private fun setupRecordDock() {
        binding.fabRecord.setOnClickListener {
            val mode = studioViewModel.punchMode.value
            if (mode == PunchMode.PREVIEW) {
                toggleStudioPreview()
            } else {
                startStudioRecording()
            }
        }

        binding.fabPauseResume.setOnClickListener {
            val state = studioViewModel.studioState.value
            if (state == StudioState.RECORDING) {
                recordingService?.pauseRecording()
                studioViewModel.setStudioState(StudioState.PAUSED)
            } else if (state == StudioState.PAUSED) {
                recordingService?.resumeRecording()
                studioViewModel.setStudioState(StudioState.RECORDING)
            }
        }

        binding.fabStop.setOnClickListener {
            stopStudioRecording()
        }
    }

    fun startNewBlankSession() {
        val service = recordingService ?: return
        service.getPlaybackEngine().stopPlayback()
        if (studioViewModel.studioState.value == StudioState.RECORDING) {
            service.stopRecording()
        }
        service.getScratchpadManager().resetSession()
        studioViewModel.clearWaveform()
        studioViewModel.setElapsedMillis(0L)
        studioViewModel.setStudioState(StudioState.IDLE)
        Toast.makeText(this, "Started new session", Toast.LENGTH_SHORT).show()
    }

    fun toggleStudioPreview() {
        val service = recordingService ?: return
        val playback = service.getPlaybackEngine()

        if (studioViewModel.studioState.value == StudioState.PREVIEWING) {
            playback.stopPlayback()
            studioViewModel.setStudioState(StudioState.IDLE)
        } else {
            val scratchpad = service.getScratchpadManager()
            scratchpad.sync()
            val rawFile = scratchpad.scratchFile

            if (!rawFile.exists() || rawFile.length() == 0L) {
                Toast.makeText(this, "No recorded audio to audition yet.", Toast.LENGTH_SHORT).show()
                return
            }

            val preset = studioViewModel.selectedPreset.value
            val startSample = studioViewModel.punchInSampleIndex

            studioViewModel.setStudioState(StudioState.PREVIEWING)
            val success = playback.startPlayback(
                scratchFile = rawFile,
                startSample = startSample,
                sampleRate = preset.sampleRate,
                channels = preset.channels
            )

            if (!success) {
                studioViewModel.setStudioState(StudioState.IDLE)
                Toast.makeText(this, "Could not start timeline audition.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startStudioRecording() {
        val service = recordingService ?: return
        val preset = studioViewModel.selectedPreset.value
        val mic = studioViewModel.selectedMic.value
        val scratchpad = service.getScratchpadManager()

        service.getPlaybackEngine().stopPlayback()

        val totalBytes = scratchpad.getTotalAudioBytes()
        val totalSamples = totalBytes / (preset.channels * 4L)
        val punchSample = studioViewModel.punchInSampleIndex

        if (punchSample < totalSamples && totalSamples > 0L) {
            // Punch-in: Shelve downstream tail audio and peaks
            scratchpad.prepareRangeReplacement(punchSample, punchSample, preset.channels)
            studioViewModel.preparePunchWaveform(studioViewModel.punchInPeakIndex)
        }

        val success = service.startRecording(preset, mic)
        if (success) {
            studioViewModel.setStudioState(StudioState.RECORDING)
        } else {
            Toast.makeText(this, "Could not start audio capture engine.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopStudioRecording() {
        val service = recordingService ?: return
        val preset = studioViewModel.selectedPreset.value
        val scratchpad = service.getScratchpadManager()

        service.stopRecording()

        // Splice downstream tail back if it was shelved during punch-in
        if (scratchpad.isTailShelved) {
            scratchpad.spliceTailBack(preset.sampleRate, preset.channels)
            studioViewModel.spliceTailPeaksBack()
        }

        // Export take to permanent WAV file in app recordings directory
        val recordingsDir = File(getExternalFilesDir(null), "recordings").apply { if (!exists()) mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val wavName = "Take_$timeStamp.wav"
        val destWav = File(recordingsDir, wavName)

        scratchpad.exportToFloatWav(destWav, preset.sampleRate, preset.channels)

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
        Toast.makeText(this, "Take saved to Library!", Toast.LENGTH_SHORT).show()
    }

    fun pauseActiveAudioForScrub() {
        if (studioViewModel.studioState.value == StudioState.RECORDING) {
            recordingService?.pauseRecording()
            studioViewModel.setStudioState(StudioState.PAUSED)
        } else if (studioViewModel.studioState.value == StudioState.PREVIEWING) {
            recordingService?.getPlaybackEngine()?.stopPlayback()
            studioViewModel.setStudioState(StudioState.IDLE)
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
        val service = recordingService ?: return
        val targetFile = File(recording.filePath)
        if (!targetFile.exists()) return

        CoroutineScope(Dispatchers.IO).launch {
            // 1. Copy WAV payload into live studio scratchpad
            service.getScratchpadManager().loadFromWav(targetFile)

            // 2. Extract visualizer peaks
            val extractedPeaks = extractWaveformPeaksFromWav(targetFile, recording.sampleRate, recording.channelCount)

            withContext(Dispatchers.Main) {
                studioViewModel.setWaveformPeaks(extractedPeaks)
                studioViewModel.setElapsedMillis(0L)
                studioViewModel.setScrubPosition(0L, 0)
                seekScratchpadToSample(0L, recording.channelCount)
                binding.btnNavStudio.performClick()
                Toast.makeText(this@MainActivity, "Loaded '${recording.title}' into Studio", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun extractWaveformPeaksFromWav(file: File, sampleRate: Int, channels: Int): List<Float> {
        val peaksList = ArrayList<Float>()
        if (!file.exists() || file.length() <= 44L) return peaksList

        try {
            FileInputStream(file).use { fis ->
                fis.skip(44)
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

    override fun onPlaybackTick(currentSample: Long, currentMs: Long) {
        runOnUiThread {
            studioViewModel.setElapsedMillis(currentMs)
        }
    }

    override fun onPlaybackFinished() {
        runOnUiThread {
            studioViewModel.setStudioState(StudioState.IDLE)
        }
    }

    override fun onPreRollCountdown(secondsRemaining: Int) {}
    override fun onPreRollFinished() {}

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
