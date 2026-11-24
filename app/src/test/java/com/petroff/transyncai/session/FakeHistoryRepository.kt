package com.petroff.transyncai.session

import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.history.HistoryRepository

class FakeHistoryRepository : HistoryRepository {
    val addedRecords = mutableListOf<ConversationRecord>()
    private val records = mutableListOf<ConversationRecord>()

    override suspend fun getRecords(): List<ConversationRecord> = records.toList()

    override suspend fun search(query: String): List<ConversationRecord> =
        records.filter {
            it.title?.contains(query, ignoreCase = true) == true ||
                it.sourceText.contains(query, ignoreCase = true) ||
                it.translatedText.contains(query, ignoreCase = true)
        }

    override suspend fun addRecord(record: ConversationRecord) {
        addedRecords.add(record)
        records.add(0, record)
    }

    override suspend fun deleteRecord(id: String) {
        records.removeAll { it.id == id }
    }

    override suspend fun getRecord(id: String): ConversationRecord? = records.firstOrNull { it.id == id }

    override suspend fun setSummary(id: String, title: String, summary: String) {
        val index = records.indexOfFirst { it.id == id }
        if (index >= 0) records[index] = records[index].copy(title = title, summary = summary)
    }
}
