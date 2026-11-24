package com.petroff.transyncai.history

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_records")
data class ConversationRecordEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val title: String?,
    val sourceLanguageName: String,
    val targetLanguageName: String,
    val sourceText: String,
    val translatedText: String,
    val summary: String? = null,
)
