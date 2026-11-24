package com.petroff.transyncai.history

class RoomHistoryRepository(private val dao: ConversationRecordDao) : HistoryRepository {

    override suspend fun getRecords(): List<ConversationRecord> = dao.getAll().map { it.toDomain() }

    // What the customer types is plain text: %, _ and \ must not act as LIKE wildcards.
    override suspend fun search(query: String): List<ConversationRecord> {
        val escaped = query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return dao.search(escaped).map { it.toDomain() }
    }

    override suspend fun addRecord(record: ConversationRecord) = dao.insert(record.toEntity())

    override suspend fun deleteRecord(id: String) = dao.deleteById(id)

    override suspend fun getRecord(id: String): ConversationRecord? = dao.getById(id)?.toDomain()

    override suspend fun setSummary(id: String, title: String, summary: String) =
        dao.updateSummary(id, title, summary)

    private fun ConversationRecordEntity.toDomain() = ConversationRecord(
        id = id,
        timestamp = timestamp,
        title = title,
        sourceLanguageName = sourceLanguageName,
        targetLanguageName = targetLanguageName,
        sourceText = sourceText,
        translatedText = translatedText,
        summary = summary,
    )

    private fun ConversationRecord.toEntity() = ConversationRecordEntity(
        id = id,
        timestamp = timestamp,
        title = title,
        sourceLanguageName = sourceLanguageName,
        targetLanguageName = targetLanguageName,
        sourceText = sourceText,
        translatedText = translatedText,
        summary = summary,
    )
}
