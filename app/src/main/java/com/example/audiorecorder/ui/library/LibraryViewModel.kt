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
enum class DurationFilter { ALL, SHORT, MEDIUM, LONG }
enum class SortOption { DATE_DESC, DATE_ASC, TITLE_ASC, TITLE_DESC, DURATION_DESC, SIZE_DESC }

data class FilterCriteria(
    val query: String = "",
    val dateFilter: DateFilter = DateFilter.ALL,
    val durationFilter: DurationFilter = DurationFilter.ALL,
    val formatFilter: String = "ALL",
    val sortOption: SortOption = SortOption.DATE_DESC
)

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

    // Combine 5 filter flows into typed FilterCriteria
    private val filterCriteria = combine(
        _searchQuery,
        _dateFilter,
        _durationFilter,
        _formatFilter,
        _sortOption
    ) { query, dateF, durF, formatF, sortOpt ->
        FilterCriteria(query, dateF, durF, formatF, sortOpt)
    }

    // Combine database recordings with typed criteria
    val recordings: StateFlow<List<RecordingEntity>> = combine(
        repository.allActiveRecordings,
        filterCriteria
    ) { all, criteria ->
        filterAndSort(
            list = all,
            query = criteria.query,
            dateF = criteria.dateFilter,
            durF = criteria.durationFilter,
            formatF = criteria.formatFilter,
            sortOpt = criteria.sortOption
        )
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
