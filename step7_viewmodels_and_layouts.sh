#!/bin/sh
set -e

BASE="app/src/main/java/com/example/audiorecorder"
RES="app/src/main/res"

echo "==> 1. Writing LibraryViewModel.kt..."
cat << 'LIB_VM' > "$BASE/ui/library/LibraryViewModel.kt"
package com.example.audiorecorder.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audiorecorder.data.db.RecordingEntity
import com.example.audiorecorder.data.repository.RecordingRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar

enum class DateFilter { ALL, TODAY, PAST_WEEK, PAST_MONTH }
enum class DurationFilter { ALL, SHORT, MEDIUM, LONG } // <1 min, 1-10 min, >10 min
enum class SortOption { DATE_DESC, DATE_ASC, TITLE_ASC, TITLE_DESC, DURATION_DESC, SIZE_DESC }

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = RecordingRepository.getInstance(application)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _dateFilter = MutableStateFlow(DateFilter.ALL)
    val dateFilter: StateFlow<DateFilter> = _dateFilter.asStateFlow()

    private val _durationFilter = MutableStateFlow(DurationFilter.ALL)
    val durationFilter: StateFlow<DurationFilter> = _durationFilter.asStateFlow()

    private val _formatFilter = MutableStateFlow("ALL")
    val formatFilter: StateFlow<String> = _formatFilter.asStateFlow()

    private val _sortOption = MutableStateFlow(SortOption.DATE_DESC)
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    private val _selectedIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedIds: StateFlow<Set<Long>> = _selectedIds.asStateFlow()

    private val _isMultiSelectMode = MutableStateFlow(false)
    val isMultiSelectMode: StateFlow<Boolean> = _isMultiSelectMode.asStateFlow()

    val recordings: StateFlow<List<RecordingEntity>> = combine(
        repository.allActiveRecordings,
        _searchQuery,
        _dateFilter,
        _durationFilter,
        _formatFilter,
        _sortOption
    ) { all, query, dateF, durF, formatF, sortOpt ->
        filterAndSort(all, query, dateF, durF, formatF, sortOpt)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun filterAndSort(
        list: List<RecordingEntity>,
        query: String,
        dateF: DateFilter,
        durF: DurationFilter,
        formatF: String,
        sortOpt: SortOption
    ): List<RecordingEntity> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        cal.timeInMillis = now
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        val startOfToday = cal.timeInMillis
        val oneWeekAgo = now - (7L * 24 * 3600 * 1000)
        val oneMonthAgo = now - (30L * 24 * 3600 * 1000)

        var filtered = list

        if (query.isNotBlank()) {
            filtered = filtered.filter { it.title.contains(query, ignoreCase = true) }
        }

        filtered = when (dateF) {
            DateFilter.ALL -> filtered
            DateFilter.TODAY -> filtered.filter { it.createdAt >= startOfToday }
            DateFilter.PAST_WEEK -> filtered.filter { it.createdAt >= oneWeekAgo }
            DateFilter.PAST_MONTH -> filtered.filter { it.createdAt >= oneMonthAgo }
        }

        filtered = when (durF) {
            DurationFilter.ALL -> filtered
            DurationFilter.SHORT -> filtered.filter { it.durationMs < 60_000 }
            DurationFilter.MEDIUM -> filtered.filter { it.durationMs in 60_000..600_000 }
            DurationFilter.LONG -> filtered.filter { it.durationMs > 600_000 }
        }

        if (formatF != "ALL") {
            filtered = filtered.filter { it.format.equals(formatF, ignoreCase = true) }
        }

        return when (sortOpt) {
            SortOption.DATE_DESC -> filtered.sortedByDescending { it.createdAt }
            SortOption.DATE_ASC -> filtered.sortedBy { it.createdAt }
            SortOption.TITLE_ASC -> filtered.sortedBy { it.title.lowercase() }
            SortOption.TITLE_DESC -> filtered.sortedByDescending { it.title.lowercase() }
            SortOption.DURATION_DESC -> filtered.sortedByDescending { it.durationMs }
            SortOption.SIZE_DESC -> filtered.sortedByDescending { it.fileSize }
        }
    }

    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setDateFilter(filter: DateFilter) { _dateFilter.value = filter }
    fun setDurationFilter(filter: DurationFilter) { _durationFilter.value = filter }
    fun setFormatFilter(format: String) { _formatFilter.value = format }
    fun setSortOption(option: SortOption) { _sortOption.value = option }

    fun enterMultiSelectMode(initialId: Long) {
        _isMultiSelectMode.value = true
        _selectedIds.value = setOf(initialId)
    }

    fun exitMultiSelectMode() {
        _isMultiSelectMode.value = false
        _selectedIds.value = emptySet()
    }

    fun toggleSelection(id: Long) {
        val current = _selectedIds.value.toMutableSet()
        if (current.contains(id)) {
            current.remove(id)
            if (current.isEmpty()) {
                exitMultiSelectMode()
                return
            }
        } else {
            current.add(id)
        }
        _selectedIds.value = current
    }

    fun selectAll(allIds: List<Long>) {
        _selectedIds.value = allIds.toSet()
    }

    fun softDelete(id: Long) = viewModelScope.launch {
        repository.softDelete(id)
    }

    fun softDeleteSelected() = viewModelScope.launch {
        val ids = _selectedIds.value.toList()
        repository.softDeleteMultiple(ids)
        exitMultiSelectMode()
    }

    fun renameRecording(id: Long, newTitle: String) = viewModelScope.launch {
        repository.renameRecording(id, newTitle)
    }
}
LIB_VM

echo "==> 2. Writing StudioViewModel.kt..."
cat << 'STUDIO_VM' > "$BASE/ui/studio/StudioViewModel.kt"
package com.example.audiorecorder.ui.studio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.audiorecorder.audio.hardware.AudioPreset
import com.example.audiorecorder.audio.hardware.DiscoveredMic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

echo "==> 3. Writing item_recording_card.xml..."
cat << 'ITEM_CARD' > "$RES/layout/item_recording_card.xml"
<?xml version="1.0" encoding="utf-8"?>
<com.google.android.material.card.MaterialCardView
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:id="@+id/cardContainer"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:layout_marginHorizontal="12dp"
    android:layout_marginVertical="6dp"
    app:cardBackgroundColor="#1E2124"
    app:cardCornerRadius="12dp"
    app:cardElevation="2dp"
    app:strokeColor="#2E3338"
    app:strokeWidth="1dp">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="12dp">

        <!-- Collapsed Header Row -->
        <LinearLayout
            android:id="@+id/layoutCollapsedHeader"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:gravity="center_vertical"
            android:orientation="horizontal">

            <CheckBox
                android:id="@+id/cbSelect"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginEnd="8dp"
                android:visibility="gone" />

            <LinearLayout
                android:layout_width="0dp"
                android:layout_height="wrap_content"
                android:layout_weight="1"
                android:orientation="vertical">

                <TextView
                    android:id="@+id/tvTitle"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Recording Title"
                    android:textColor="#FFFFFF"
                    android:textSize="16sp"
                    android:textStyle="bold"
                    android:maxLines="1"
                    android:ellipsize="end" />

                <TextView
                    android:id="@+id/tvSpecs"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:text="48kHz • 24b • Stereo • WAV"
                    android:textColor="#00E676"
                    android:textSize="12sp" />

                <TextView
                    android:id="@+id/tvDetails"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginTop="2dp"
                    android:text="04:12 • 45.2 MB • Oct 2, 2026"
                    android:textColor="#9E9E9E"
                    android:textSize="12sp" />
            </LinearLayout>

            <ImageButton
                android:id="@+id/btnPlayPause"
                android:layout_width="44dp"
                android:layout_height="44dp"
                android:background="?attr/selectableItemBackgroundBorderless"
                android:src="@android:drawable/ic_media_play"
                app:tint="#448AFF"
                android:contentDescription="Play or Pause recording" />
        </LinearLayout>

        <!-- Expandable Drawer -->
        <LinearLayout
            android:id="@+id/layoutDrawer"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:visibility="gone"
            android:layout_marginTop="12dp">

            <SeekBar
                android:id="@+id/sbTimeline"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:progressBackgroundTint="#424242"
                android:progressTint="#448AFF"
                android:thumbTint="#448AFF" />

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:layout_marginTop="8dp"
                android:gravity="center">

                <Button
                    android:id="@+id/btnExport"
                    style="@style/Widget.Material3.Button.TextButton"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Export"
                    android:textColor="#448AFF" />

                <Button
                    android:id="@+id/btnEdit"
                    style="@style/Widget.Material3.Button.TextButton"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Edit"
                    android:textColor="#00E676" />

                <Button
                    android:id="@+id/btnRename"
                    style="@style/Widget.Material3.Button.TextButton"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Rename"
                    android:textColor="#FFD600" />

                <Button
                    android:id="@+id/btnDelete"
                    style="@style/Widget.Material3.Button.TextButton"
                    android:layout_width="0dp"
                    android:layout_height="wrap_content"
                    android:layout_weight="1"
                    android:text="Delete"
                    android:textColor="#FF5252" />
            </LinearLayout>
        </LinearLayout>

    </LinearLayout>

</com.google.android.material.card.MaterialCardView>
ITEM_CARD

echo "==> 4. Writing fragment_library.xml..."
cat << 'FRAG_LIB' > "$RES/layout/fragment_library.xml"
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#121416">

    <!-- Batch Selection Bar -->
    <LinearLayout
        android:id="@+id/layoutBatchBar"
        android:layout_width="match_parent"
        android:layout_height="52dp"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:paddingHorizontal="16dp"
        android:background="#1E2124"
        android:visibility="gone">

        <ImageButton
            android:id="@+id/btnCloseBatch"
            android:layout_width="36dp"
            android:layout_height="36dp"
            android:background="?attr/selectableItemBackgroundBorderless"
            android:src="@android:drawable/ic_menu_close_clear_cancel"
            app:tint="#FFFFFF"
            android:contentDescription="Close batch mode" />

        <TextView
            android:id="@+id/tvSelectedCount"
            android:layout_width="0dp"
            android:layout_height="wrap_content"
            android:layout_weight="1"
            android:layout_marginStart="12dp"
            android:text="0 selected"
            android:textColor="#FFFFFF"
            android:textSize="16sp"
            android:textStyle="bold" />

        <Button
            android:id="@+id/btnBatchExport"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Export"
            android:textColor="#448AFF" />

        <Button
            android:id="@+id/btnBatchDelete"
            style="@style/Widget.Material3.Button.TextButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:text="Delete"
            android:textColor="#FF5252" />
    </LinearLayout>

    <!-- Search Input -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:paddingHorizontal="12dp"
        android:paddingTop="12dp">

        <EditText
            android:id="@+id/etSearch"
            android:layout_width="match_parent"
            android:layout_height="44dp"
            android:background="@android:drawable/editbox_background_normal"
            android:backgroundTint="#1E2124"
            android:hint="Search recordings..."
            android:textColorHint="#757575"
            android:textColor="#FFFFFF"
            android:textSize="14sp"
            android:paddingHorizontal="14dp"
            android:inputType="text"
            android:imeOptions="actionSearch" />
    </LinearLayout>

    <!-- Horizontal Filter Chips Scroll -->
    <HorizontalScrollView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:scrollbars="none"
        android:paddingHorizontal="12dp"
        android:paddingVertical="8dp">

        <com.google.android.material.chip.ChipGroup
            android:id="@+id/chipGroupFilters"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            app:singleLine="true">

            <com.google.android.material.chip.Chip
                android:id="@+id/chipAll"
                style="@style/Widget.Material3.Chip.Filter"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:checked="true"
                android:text="All" />

            <com.google.android.material.chip.Chip
                android:id="@+id/chipToday"
                style="@style/Widget.Material3.Chip.Filter"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Today" />

            <com.google.android.material.chip.Chip
                android:id="@+id/chipPastWeek"
                style="@style/Widget.Material3.Chip.Filter"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Past Week" />

            <com.google.android.material.chip.Chip
                android:id="@+id/chipWav"
                style="@style/Widget.Material3.Chip.Filter"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="WAV" />

            <com.google.android.material.chip.Chip
                android:id="@+id/chipFlac"
                style="@style/Widget.Material3.Chip.Filter"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="FLAC" />

            <com.google.android.material.chip.Chip
                android:id="@+id/chipAac"
                style="@style/Widget.Material3.Chip.Filter"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="AAC" />
        </com.google.android.material.chip.ChipGroup>
    </HorizontalScrollView>

    <!-- RecyclerView for Takes -->
    <FrameLayout
        android:layout_width="match_parent"
        android:layout_height="match_parent">

        <androidx.recyclerview.widget.RecyclerView
            android:id="@+id/rvRecordings"
            android:layout_width="match_parent"
            android:layout_height="match_parent"
            android:clipToPadding="false"
            android:paddingBottom="84dp" />

        <TextView
            android:id="@+id/tvEmptyState"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_gravity="center"
            android:text="No recordings yet.\nTap the mic below to start."
            android:textAlignment="center"
            android:textColor="#757575"
            android:textSize="16sp"
            android:visibility="gone" />
    </FrameLayout>

</LinearLayout>
FRAG_LIB

echo "==> 5. Writing fragment_studio.xml..."
cat << 'FRAG_STUDIO' > "$RES/layout/fragment_studio.xml"
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="#101214">

    <!-- 1. Teleprompter Section (Dynamic Height) -->
    <LinearLayout
        android:id="@+id/layoutPrompterContainer"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:orientation="vertical"
        android:background="#16181B">

        <!-- Prompter Header Controls -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="40dp"
            android:orientation="horizontal"
            android:gravity="center_vertical"
            android:paddingHorizontal="12dp"
            android:background="#1E2124">

            <Button
                android:id="@+id/btnScript"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Script ▼"
                android:textColor="#448AFF"
                android:textSize="12sp" />

            <View
                android:layout_width="0dp"
                android:layout_height="1dp"
                android:layout_weight="1" />

            <Button
                android:id="@+id/btnScrollToggle"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Auto-Scroll"
                android:textColor="#00E676"
                android:textSize="12sp" />

            <Button
                android:id="@+id/btnMirror"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Mirror ⮂"
                android:textColor="#FFD600"
                android:textSize="12sp" />
        </LinearLayout>

        <!-- Prompter Viewport -->
        <com.example.audiorecorder.ui.customviews.TeleprompterView
            android:id="@+id/teleprompterView"
            android:layout_width="match_parent"
            android:layout_height="0dp"
            android:layout_weight="1"
            android:padding="16dp" />

        <!-- Prompter Tuning Capsules -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="36dp"
            android:orientation="horizontal"
            android:gravity="center"
            android:background="#181A1D"
            android:paddingHorizontal="8dp">

            <TextView
                android:id="@+id/tvWordsDec"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="-"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <TextView
                android:id="@+id/tvWordsValue"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="8 w/l"
                android:textColor="#9E9E9E"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/tvWordsInc"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="+"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <View
                android:layout_width="16dp"
                android:layout_height="1dp" />

            <TextView
                android:id="@+id/tvSpeedDec"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="-"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <TextView
                android:id="@+id/tvSpeedValue"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Speed"
                android:textColor="#9E9E9E"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/tvSpeedInc"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="+"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <View
                android:layout_width="16dp"
                android:layout_height="1dp" />

            <TextView
                android:id="@+id/tvFontDec"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="-"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <TextView
                android:id="@+id/tvFontValue"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="Size"
                android:textColor="#9E9E9E"
                android:textSize="11sp" />

            <TextView
                android:id="@+id/tvFontInc"
                android:layout_width="28dp"
                android:layout_height="match_parent"
                android:gravity="center"
                android:text="+"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />
        </LinearLayout>
    </LinearLayout>

    <!-- 2. Waveform Visualizer & Zoom Section -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="160dp"
        android:orientation="horizontal"
        android:background="#121416"
        android:padding="8dp">

        <com.example.audiorecorder.ui.customviews.WaveformVisualizerView
            android:id="@+id/waveformVisualizerView"
            android:layout_width="0dp"
            android:layout_height="match_parent"
            android:layout_weight="1" />

        <LinearLayout
            android:layout_width="36dp"
            android:layout_height="match_parent"
            android:orientation="vertical"
            android:gravity="center">

            <Button
                android:id="@+id/btnZoomIn"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="36dp"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:text="+"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />

            <Button
                android:id="@+id/btnZoomOut"
                style="@style/Widget.Material3.Button.TextButton"
                android:layout_width="36dp"
                android:layout_height="0dp"
                android:layout_weight="1"
                android:text="-"
                android:textColor="#FFFFFF"
                android:textSize="16sp" />
        </LinearLayout>
    </LinearLayout>

    <!-- 3. Status & Control Strip -->
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:paddingHorizontal="12dp"
        android:paddingTop="8dp"
        android:paddingBottom="84dp"
        android:background="#181A1D">

        <!-- dBFS Meter and Timecode -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="48dp"
            android:orientation="horizontal"
            android:gravity="center_vertical">

            <com.example.audiorecorder.ui.customviews.DbfsMeterView
                android:id="@+id/dbfsMeterView"
                android:layout_width="14dp"
                android:layout_height="match_parent"
                android:layout_marginEnd="12dp" />

            <TextView
                android:id="@+id/tvTimecode"
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="00:00:00.000"
                android:textColor="#FFFFFF"
                android:textSize="26sp"
                android:textStyle="bold"
                android:fontFamily="monospace" />
        </LinearLayout>

        <!-- Action Pills Row -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="horizontal"
            android:layout_marginTop="8dp">

            <Button
                android:id="@+id/btnMicSelector"
                style="@style/Widget.Material3.Button.TonalButton"
                android:layout_width="0dp"
                android:layout_height="36dp"
                android:layout_weight="1"
                android:layout_marginEnd="4dp"
                android:padding="0dp"
                android:text="🎙 Built-in"
                android:textSize="11sp" />

            <Button
                android:id="@+id/btnPresetSelector"
                style="@style/Widget.Material3.Button.TonalButton"
                android:layout_width="0dp"
                android:layout_height="36dp"
                android:layout_weight="1"
                android:layout_marginEnd="4dp"
                android:padding="0dp"
                android:text="🎛 Podcast"
                android:textSize="11sp" />

            <Button
                android:id="@+id/btnPrompterToggle"
                style="@style/Widget.Material3.Button.TonalButton"
                android:layout_width="0dp"
                android:layout_height="36dp"
                android:layout_weight="1"
                android:layout_marginEnd="4dp"
                android:padding="0dp"
                android:text="🗎 Prompter"
                android:textSize="11sp" />

            <Button
                android:id="@+id/btnModeToggle"
                style="@style/Widget.Material3.Button.TonalButton"
                android:layout_width="0dp"
                android:layout_height="36dp"
                android:layout_weight="1"
                android:padding="0dp"
                android:text="⎌ Replace"
                android:textColor="#FF5252"
                android:textSize="11sp" />
        </LinearLayout>
    </LinearLayout>

</LinearLayout>
FRAG_STUDIO

echo "==> Step 7 ViewModels and Layouts generated successfully!"
