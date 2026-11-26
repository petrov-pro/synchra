package com.petroff.synchra.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ConversationRecordDao {

    @Query("SELECT * FROM conversation_records ORDER BY timestamp DESC")
    suspend fun getAll(): List<ConversationRecordEntity>

    @Query(
        "SELECT * FROM conversation_records WHERE " +
            "title LIKE '%' || :query || '%' ESCAPE '\\' OR " +
            "sourceText LIKE '%' || :query || '%' ESCAPE '\\' OR " +
            "translatedText LIKE '%' || :query || '%' ESCAPE '\\' " +
            "ORDER BY timestamp DESC"
    )
    /** [query] must be LIKE-escaped with '\\' (see RoomHistoryRepository.search). */
    suspend fun search(query: String): List<ConversationRecordEntity>

    // A paused session saves its text and saves it again, longer, under the same id later.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(record: ConversationRecordEntity)

    @Query("DELETE FROM conversation_records WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM conversation_records WHERE id = :id")
    suspend fun getById(id: String): ConversationRecordEntity?

    @Query("UPDATE conversation_records SET title = :title, summary = :summary WHERE id = :id")
    suspend fun updateSummary(id: String, title: String, summary: String)
}
