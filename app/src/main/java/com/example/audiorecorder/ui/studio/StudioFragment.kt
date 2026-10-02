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
    val binding get() = _binding!!
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
        setupAuditionButton()
        observeStudioState()
    }

    private fun setupAuditionButton() {
        binding.btnStudioPlayPause.setOnClickListener {
            (activity as? MainActivity)?.toggleStudioPreview()
        }
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
        binding.btnNewTake.setOnClickListener {
            (activity as? MainActivity)?.startNewBlankSession()
        }
        binding.btnMicSelector.setOnClickListener { showMicrophonePicker() }
        binding.btnPrompterToggle.setOnClickListener {
            viewModel.togglePrompterVisibility()
            binding.layoutPrompterContainer.visibility =
                if (viewModel.isPrompterVisible.value) View.VISIBLE else View.GONE
        }
        binding.btnModeToggle.setOnClickListener {
            viewModel.togglePunchMode()
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
                    viewModel.punchMode.collectLatest { mode ->
                        if (mode == PunchMode.PREVIEW) {
                            binding.btnModeToggle.text = "▶ Preview"
                            binding.btnModeToggle.setTextColor(0xFF00E5FF.toInt())
                        } else {
                            binding.btnModeToggle.text = "⎌ Replace"
                            binding.btnModeToggle.setTextColor(0xFFFF3D71.toInt())
                        }
                    }
                }
                launch {
                    viewModel.studioState.collectLatest { state ->
                        if (state == StudioState.PREVIEWING) {
                            binding.btnStudioPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                        } else {
                            binding.btnStudioPlayPause.setImageResource(android.R.drawable.ic_media_play)
                        }
                    }
                }
                launch {
                    viewModel.elapsedMillis.collectLatest { ms ->
                        binding.tvTimecode.text = TimecodeFormatter.formatMillis(ms)
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
                    viewModel.waveformPeaks.collectLatest { peaks ->
                        if (viewModel.studioState.value != StudioState.RECORDING) {
                            binding.waveformVisualizerView.setPeaks(peaks)
                        }
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
        viewModel.setScrubPosition(sampleOffset, finalPeakIndex)
        (activity as? MainActivity)?.seekScratchpadToSample(sampleOffset, preset.channels)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
