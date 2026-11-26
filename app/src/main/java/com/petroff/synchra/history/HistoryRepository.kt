package com.petroff.synchra.history

interface HistoryRepository {
    suspend fun getRecords(): List<ConversationRecord>
    suspend fun search(query: String): List<ConversationRecord>
    /** Replaces a record with the same id (a paused session saves its text again, longer). */
    suspend fun addRecord(record: ConversationRecord)
    suspend fun deleteRecord(id: String)
    suspend fun getRecord(id: String): ConversationRecord?
    suspend fun setSummary(id: String, title: String, summary: String)
}
