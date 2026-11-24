package com.petroff.synchra.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The History list: every saved conversation, oldest first by default, optionally filtered by search. */
class HistoryViewModel(private val historyRepository: HistoryRepository) : ViewModel() {

    private val _records = MutableStateFlow<List<ConversationRecord>>(emptyList())
    val records: StateFlow<List<ConversationRecord>> = _records.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private var refreshJob: Job? = null

    private val _newestFirst = MutableStateFlow(false)
    val newestFirst: StateFlow<Boolean> = _newestFirst.asStateFlow()

    fun refresh() {
        val query = _searchQuery.value
        val newestFirst = _newestFirst.value
        // Only the latest request may fill the list: an older, slower one must not land last.
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // The repository returns newest first.
            val newestFirstRecords = if (query.isBlank()) historyRepository.getRecords() else historyRepository.search(query)
            _records.value = if (newestFirst) newestFirstRecords else newestFirstRecords.reversed()
        }
    }

    fun toggleOrder() {
        _newestFirst.value = !_newestFirst.value
        refresh()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        refresh()
    }

    fun delete(id: String) {
        viewModelScope.launch {
            historyRepository.deleteRecord(id)
            refresh()
        }
    }
}
