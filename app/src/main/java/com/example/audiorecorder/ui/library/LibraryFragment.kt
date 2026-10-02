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
