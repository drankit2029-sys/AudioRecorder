#!/bin/sh
set -e

BASE="app/src/main/java/com/example/audiorecorder"
RES="app/src/main/res"

echo "==> 1. Updating activity_main.xml to handle dynamic dock insets..."
cat << 'ACT_XML' > "$RES/layout/activity_main.xml"
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/rootContainer"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#101214">

    <!-- Fragment Host Viewport -->
    <androidx.fragment.app.FragmentContainerView
        android:id="@+id/fragmentContainer"
        android:layout_width="0dp"
        android:layout_height="0dp"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintBottom_toTopOf="@+id/bottomDock"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

    <!-- Bottom Persistent Dock & Morphing Controls -->
    <LinearLayout
        android:id="@+id/bottomDock"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:minHeight="76dp"
        android:orientation="horizontal"
        android:gravity="center"
        android:background="#16181B"
        android:paddingHorizontal="24dp"
        android:paddingTop="12dp"
        android:paddingBottom="12dp"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent">

        <!-- Navigation: Library / Studio Switcher -->
        <Button
            android:id="@+id/btnNavLibrary"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Library"
            android:textColor="#FFFFFF" />

        <View
            android:layout_width="0dp"
            android:layout_height="1dp"
            android:layout_weight="1" />

        <!-- Idle State Record Button -->
        <com.google.android.material.floatingactionbutton.FloatingActionButton
            android:id="@+id/fabRecord"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:backgroundTint="#FF1744"
            app:tint="#FFFFFF"
            android:src="@android:drawable/ic_btn_speak_now"
            app:fabSize="normal"
            android:contentDescription="Record" />

        <!-- Active State Morphing Controls (Pause + Stop) -->
        <LinearLayout
            android:id="@+id/layoutActiveControls"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:gravity="center"
            android:visibility="gone">

            <com.google.android.material.floatingactionbutton.FloatingActionButton
                android:id="@+id/fabPauseResume"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="16dp"
                android:backgroundTint="#FFD600"
                app:tint="#121416"
                android:src="@android:drawable/ic_media_pause"
                app:fabSize="mini"
                android:contentDescription="Pause or Resume" />

            <com.google.android.material.floatingactionbutton.FloatingActionButton
                android:id="@+id/fabStop"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:backgroundTint="#FF1744"
                app:tint="#FFFFFF"
                android:src="@android:drawable/ic_menu_save"
                app:fabSize="normal"
                android:contentDescription="Stop and Save" />
        </LinearLayout>

        <View
            android:layout_width="0dp"
            android:layout_height="1dp"
            android:layout_weight="1" />

        <Button
            android:id="@+id/btnNavStudio"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Studio"
            android:textColor="#448AFF" />
    </LinearLayout>

</androidx.constraintlayout.widget.ConstraintLayout>
ACT_XML

echo "==> 2. Patching MainActivity.kt with WindowInsetsCompat handler..."
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

        // Default viewport to Studio
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, studioFragment)
            .commit()
    }

    private fun applySystemWindowInsets() {
        val initialDockBottomPadding = binding.bottomDock.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())

            // Push fragment viewport down past the status bar & sides past camera cutouts
            binding.fragmentContainer.updatePadding(
                left = systemBars.left,
                top = systemBars.top,
                right = systemBars.right
            )

            // Extend bottom dock background behind navigation bar while keeping controls accessible
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
        startService(intent) // Keep service running in background
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
        // Idle Record Button
        binding.fabRecord.setOnClickListener {
            startStudioRecording()
        }

        // Active State Pause / Resume
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

        // Active State Stop & Commit
        binding.fabStop.setOnClickListener {
            stopStudioRecording()
        }
    }

    private fun startStudioRecording() {
        val service = recordingService ?: return
        val preset = studioViewModel.selectedPreset.value
        val mic = studioViewModel.selectedMic.value

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

        // Handle punch-out tail splicing if range replace was active
        if (studioViewModel.punchMode.value == PunchMode.REPLACE) {
            scratchpad.spliceTailBack(preset.sampleRate, preset.channels)
        }

        // Finalize take to WAV file in app recordings directory
        val recordingsDir = File(getExternalFilesDir(null), "recordings").apply { if (!exists()) mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val wavName = "Take_$timeStamp.wav"
        val destWav = File(recordingsDir, wavName)

        scratchpad.exportToFloatWav(destWav, preset.sampleRate, preset.channels)
        service.getCrashSentinel().clearSession()

        // Insert database record
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

        // Reset Dock UI
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
        binding.btnNavStudio.performClick()
        Toast.makeText(this, "Loaded '${recording.title}' into Studio", Toast.LENGTH_SHORT).show()
    }

    override fun onDbfsUpdate(peakDbfs: Float, rmsDbfs: Float) {
        runOnUiThread {
            studioViewModel.updateDbfs(peakDbfs, rmsDbfs)
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

echo "==> 3. Triggering deploy script..."
./deploy.sh "Apply WindowInsetsCompat systemBars handling to resolve status and navigation bar overlap"
