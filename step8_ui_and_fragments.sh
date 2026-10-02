#!/bin/sh
set -e

BASE="app/src/main/java/com/example/audiorecorder"
RES="app/src/main/res"

echo "==> 1. Writing activity_main.xml..."
cat << 'ACT_XML' > "$RES/layout/activity_main.xml"
<?xml version="1.0" encoding="utf-8"?>
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
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
        android:layout_height="76dp"
        android:orientation="horizontal"
        android:gravity="center"
        android:background="#16181B"
        android:paddingHorizontal="24dp"
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

echo "==> 2. Writing RecordingAdapter.kt..."
cat << 'ADAPTER' > "$BASE/ui/library/RecordingAdapter.kt"
package com.example.audiorecorder.ui.library

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.audiorecorder.data.db.RecordingEntity
import com.example.audiorecorder.databinding.ItemRecordingCardBinding
import com.example.audiorecorder.util.TimecodeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecordingAdapter(
    private val onPlayClick: (RecordingEntity) -> Unit,
    private val onExportClick: (RecordingEntity) -> Unit,
    private val onEditClick: (RecordingEntity) -> Unit,
    private val onRenameClick: (RecordingEntity) -> Unit,
    private val onDeleteClick: (RecordingEntity) -> Unit,
    private val onCardLongClick: (RecordingEntity) -> Unit,
    private val onCardSelectToggle: (RecordingEntity) -> Unit
) : ListAdapter<RecordingEntity, RecordingAdapter.ViewHolder>(DiffCallback) {

    private var expandedCardId: Long? = null
    private var isMultiSelectMode = false
    private var selectedIds: Set<Long> = emptySet()

    fun setMultiSelectState(active: Boolean, selected: Set<Long>) {
        isMultiSelectMode = active
        selectedIds = selected
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecordingCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemRecordingCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: RecordingEntity) {
            binding.tvTitle.text = item.title
            val rateKhz = item.sampleRate / 1000
            val channelText = if (item.channelCount == 2) "Stereo" else "Mono"
            binding.tvSpecs.text = "${rateKhz}kHz • ${item.bitDepth}b • $channelText • ${item.format}"

            val durationText = TimecodeFormatter.formatMillis(item.durationMs)
            val sizeMb = String.format(Locale.US, "%.1f MB", item.fileSize / (1024.0 * 1024.0))
            val dateText = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(item.createdAt))
            binding.tvDetails.text = "$durationText • $sizeMb • $dateText"

            // Multi-select CheckBox handling
            if (isMultiSelectMode) {
                binding.cbSelect.visibility = View.VISIBLE
                binding.cbSelect.isChecked = selectedIds.contains(item.id)
                binding.btnPlayPause.visibility = View.GONE
            } else {
                binding.cbSelect.visibility = View.GONE
                binding.btnPlayPause.visibility = View.VISIBLE
            }

            // Expanded Drawer State
            val isExpanded = expandedCardId == item.id
            binding.layoutDrawer.visibility = if (isExpanded && !isMultiSelectMode) View.VISIBLE else View.GONE

            // Clicks
            binding.root.setOnClickListener {
                if (isMultiSelectMode) {
                    onCardSelectToggle(item)
                } else {
                    expandedCardId = if (isExpanded) null else item.id
                    notifyItemChanged(bindingAdapterPosition)
                }
            }

            binding.root.setOnLongClickListener {
                onCardLongClick(item)
                true
            }

            binding.cbSelect.setOnClickListener { onCardSelectToggle(item) }
            binding.btnPlayPause.setOnClickListener { onPlayClick(item) }
            binding.btnExport.setOnClickListener { onExportClick(item) }
            binding.btnEdit.setOnClickListener { onEditClick(item) }
            binding.btnRename.setOnClickListener { onRenameClick(item) }
            binding.btnDelete.setOnClickListener { onDeleteClick(item) }
        }
    }

    object DiffCallback : DiffUtil.ItemCallback<RecordingEntity>() {
        override fun areItemsTheSame(oldItem: RecordingEntity, newItem: RecordingEntity) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: RecordingEntity, newItem: RecordingEntity) = oldItem == newItem
    }
}
ADAPTER

echo "==> 3. Writing LibraryFragment.kt..."
cat << 'LIB_FRAG' > "$BASE/ui/library/LibraryFragment.kt"
package com.example.audiorecorder.ui.library

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.content.FileProvider
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.audiorecorder.MainActivity
import com.example.audiorecorder.R
import com.example.audiorecorder.data.db.RecordingEntity
import com.example.audiorecorder.databinding.FragmentLibraryBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LibraryViewModel by activityViewModels()

    private lateinit var adapter: RecordingAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupSearchAndFilters()
        setupBatchActions()
        observeData()
    }

    private fun setupRecyclerView() {
        adapter = RecordingAdapter(
            onPlayClick = { recording -> (activity as? MainActivity)?.playRecordingPreview(recording) },
            onExportClick = { recording -> shareRecording(recording) },
            onEditClick = { recording -> (activity as? MainActivity)?.loadRecordingIntoStudio(recording) },
            onRenameClick = { recording -> showRenameDialog(recording) },
            onDeleteClick = { recording -> viewModel.softDelete(recording.id) },
            onCardLongClick = { recording -> viewModel.enterMultiSelectMode(recording.id) },
            onCardSelectToggle = { recording -> viewModel.toggleSelection(recording.id) }
        )

        binding.rvRecordings.layoutManager = LinearLayoutManager(requireContext())
        binding.rvRecordings.adapter = adapter
    }

    private fun setupSearchAndFilters() {
        binding.etSearch.doAfterTextChanged { text ->
            viewModel.setSearchQuery(text?.toString().orEmpty())
        }

        binding.chipGroupFilters.setOnCheckedStateChangeListener { _, checkedIds ->
            val id = checkedIds.firstOrNull() ?: R.id.chipAll
            when (id) {
                R.id.chipAll -> {
                    viewModel.setDateFilter(DateFilter.ALL)
                    viewModel.setFormatFilter("ALL")
                }
                R.id.chipToday -> viewModel.setDateFilter(DateFilter.TODAY)
                R.id.chipPastWeek -> viewModel.setDateFilter(DateFilter.PAST_WEEK)
                R.id.chipWav -> viewModel.setFormatFilter("WAV")
                R.id.chipFlac -> viewModel.setFormatFilter("FLAC")
                R.id.chipAac -> viewModel.setFormatFilter("AAC")
            }
        }
    }

    private fun setupBatchActions() {
        binding.btnCloseBatch.setOnClickListener { viewModel.exitMultiSelectMode() }
        binding.btnBatchDelete.setOnClickListener { viewModel.softDeleteSelected() }
        binding.btnBatchExport.setOnClickListener { batchShareSelected() }
    }

    private fun observeData() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.recordings.collectLatest { list ->
                        adapter.submitList(list)
                        binding.tvEmptyState.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.isMultiSelectMode.collectLatest { isMulti ->
                        binding.layoutBatchBar.visibility = if (isMulti) View.VISIBLE else View.GONE
                        adapter.setMultiSelectState(isMulti, viewModel.selectedIds.value)
                    }
                }
                launch {
                    viewModel.selectedIds.collectLatest { ids ->
                        binding.tvSelectedCount.text = "${ids.size} selected"
                        adapter.setMultiSelectState(viewModel.isMultiSelectMode.value, ids)
                    }
                }
            }
        }
    }

    private fun showRenameDialog(recording: RecordingEntity) {
        val input = EditText(requireContext()).apply { setText(recording.title) }
        AlertDialog.Builder(requireContext())
            .setTitle("Rename Recording")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = input.text.toString().trim()
                if (newTitle.isNotEmpty()) viewModel.renameRecording(recording.id, newTitle)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareRecording(recording: RecordingEntity) {
        val file = File(recording.filePath)
        if (!file.exists()) return

        val uri: Uri = FileProvider.getUriForFile(
            requireContext(),
            "${requireContext().packageName}.fileprovider",
            file
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Recording"))
    }

    private fun batchShareSelected() {
        val selected = viewModel.selectedIds.value
        val list = viewModel.recordings.value.filter { selected.contains(it.id) }
        val uris = ArrayList<Uri>()

        for (item in list) {
            val file = File(item.filePath)
            if (file.exists()) {
                uris.add(
                    FileProvider.getUriForFile(
                        requireContext(),
                        "${requireContext().packageName}.fileprovider",
                        file
                    )
                )
            }
        }

        if (uris.isEmpty()) return

        val shareIntent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "audio/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share Selected Takes"))
        viewModel.exitMultiSelectMode()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
LIB_FRAG

echo "==> 4. Writing StudioFragment.kt..."
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

        // Tuning Steppers
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
        binding.btnPresetSelector.setOnClickListener { /* Open preset dialog */ }
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

    override fun onScrubbing(sampleOffset: Long) {
        val preset = viewModel.selectedPreset.value
        val ms = TimecodeFormatter.samplesToMillis(sampleOffset, preset.sampleRate)
        viewModel.setElapsedMillis(ms)
    }

    override fun onScrubStop(finalSampleOffset: Long) {
        val preset = viewModel.selectedPreset.value
        (activity as? MainActivity)?.seekScratchpadToSample(finalSampleOffset, preset.channels)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
STUDIO_FRAG

echo "==> 5. Writing MainActivity.kt..."
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

        checkPermissions()
        bindRecordingService()
        setupNavigation()
        setupRecordDock()

        // Default viewport to Studio
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, studioFragment)
            .commit()
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
        // Navigates take to Studio for punch-and-roll editing
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

echo "==> Step 8 UI & Navigation generated successfully!"
