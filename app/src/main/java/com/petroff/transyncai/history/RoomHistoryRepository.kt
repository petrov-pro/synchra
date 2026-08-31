package com.petroff.transyncai.history

class RoomHistoryRepository(private val dao: ConversationRecordDao) : HistoryRepository {

    override suspend fun getRecords(): List<ConversationRecord> = dao.getAll().map { it.toDomain() }

    override suspend fun search(query: String): List<ConversationRecord> = dao.search(query).map { it.toDomain() }

    override suspend fun addRecord(record: ConversationRecord) = dao.insert(record.toEntity())

    override suspend fun deleteRecord(id: String) = dao.deleteById(id)

    override suspend fun setTitle(id: String, title: String) = dao.updateTitle(id, title)

    private fun ConversationRecordEntity.toDomain() = ConversationRecord(
        id = id,
        timestamp = timestamp,
        title = title,
        sourceLanguageName = sourceLanguageName,
        targetLanguageName = targetLanguageName,
        sourceText = sourceText,
        translatedText = translatedText,
    )

    private fun ConversationRecord.toEntity() = ConversationRecordEntity(
        id = id,
        timestamp = timestamp,
        title = title,
        sourceLanguageName = sourceLanguageName,
        targetLanguageName = targetLanguageName,
        sourceText = sourceText,
        translatedText = translatedText,
    )
}
