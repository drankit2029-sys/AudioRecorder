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
