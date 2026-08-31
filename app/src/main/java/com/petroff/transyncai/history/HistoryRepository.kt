package com.petroff.transyncai.history

interface HistoryRepository {
    suspend fun getRecords(): List<ConversationRecord>
    suspend fun search(query: String): List<ConversationRecord>
    suspend fun addRecord(record: ConversationRecord)
    suspend fun deleteRecord(id: String)
    suspend fun setTitle(id: String, title: String)
}
