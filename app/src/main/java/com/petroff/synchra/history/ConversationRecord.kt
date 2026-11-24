package com.petroff.synchra.history

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ConversationRecord(
    val id: String,
    val timestamp: Long,
    // Claude's title, stored together with the summary.
    val title: String?,
    val sourceLanguageName: String,
    val targetLanguageName: String,
    val sourceText: String,
    val translatedText: String,
    val summary: String? = null,
    // The conversation's own context (main screen) at recording time, kept for the summary.
    // Records from before v4 hold the general and conversation contexts joined here.
    val context: String? = null,
    // The general context (Settings: about the user) at recording time, kept for the summary.
    val userContext: String? = null,
)

private val DISPLAY_TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/**
 * The name shown in the History list and on the conversation screen: Claude's title once the
 * conversation has been summarized, otherwise just the date and time it was recorded.
 */
fun ConversationRecord.displayTitle(): String {
    val summarizedTitle = title?.takeIf { summary != null && it.isNotBlank() }
    return summarizedTitle ?: displayDateTime()
}

/** When the conversation was recorded, e.g. "24 Nov, 14:55". */
fun ConversationRecord.displayDateTime(): String =
    Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DISPLAY_TIMESTAMP_FORMATTER)
