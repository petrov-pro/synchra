package com.petroff.transyncai.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The History list: every saved conversation, newest first, optionally filtered by search. */
class HistoryViewModel(private val historyRepository: HistoryRepository) : ViewModel() {

    private val _records = MutableStateFlow<List<ConversationRecord>>(emptyList())
    val records: StateFlow<List<ConversationRecord>> = _records.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    fun refresh() {
        val query = _searchQuery.value
        viewModelScope.launch {
            _records.value = if (query.isBlank()) historyRepository.getRecords() else historyRepository.search(query)
        }
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
