package com.petroff.transyncai.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface ConversationRecordDao {

    @Query("SELECT * FROM conversation_records ORDER BY timestamp DESC")
    suspend fun getAll(): List<ConversationRecordEntity>

    @Query(
        "SELECT * FROM conversation_records WHERE " +
            "title LIKE '%' || :query || '%' OR " +
            "sourceText LIKE '%' || :query || '%' OR " +
            "translatedText LIKE '%' || :query || '%' " +
            "ORDER BY timestamp DESC"
    )
    suspend fun search(query: String): List<ConversationRecordEntity>

    @Insert
    suspend fun insert(record: ConversationRecordEntity)

    @Query("DELETE FROM conversation_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM conversation_records WHERE id = :id")
    suspend fun getById(id: String): ConversationRecordEntity?

    @Query("UPDATE conversation_records SET title = :title, summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: String, title: String, summary: String)
}
