package com.petroff.transyncai.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ConversationRecord(
    val id: String,
    val timestamp: Long,
    val title: String?,
    val sourceLanguageName: String,
    val targetLanguageName: String,
    val sourceText: String,
    val translatedText: String,
)

private val DISPLAY_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, HH:mm")

/**
 * The title shown in the History list/detail screens: the customer's own title once
 * they've set one (see HistoryDetailScreen), otherwise a fallback computed from the
 * timestamp and language pair — never stored, so the stored `title` field stays an
 * honest signal of whether the customer actually named this conversation.
 */
fun ConversationRecord.displayTitle(): String {
    val explicitTitle = title?.takeIf { it.isNotBlank() }
    if (explicitTitle != null) return explicitTitle
    val formattedTimestamp = Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(DISPLAY_TIMESTAMP_FORMATTER)
    return "$formattedTimestamp · $sourceLanguageName → $targetLanguageName"
}
