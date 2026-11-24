package com.petroff.transyncai.history

import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ConversationRecordTest {

    private val when_ = ZonedDateTime.of(2026, 11, 24, 14, 55, 0, 0, ZoneId.systemDefault())
    private val dateName = DateTimeFormatter.ofPattern("d MMM, HH:mm").format(when_)

    private fun record(title: String?, summary: String?) = ConversationRecord(
        id = "1",
        timestamp = when_.toInstant().toEpochMilli(),
        title = title,
        sourceLanguageName = "English",
        targetLanguageName = "Russian",
        sourceText = "hello",
        translatedText = "привет",
        summary = summary,
    )

    @Test
    fun `displayTitle is date and time only without a summary`() {
        val result = record(title = null, summary = null).displayTitle()

        assertEquals(dateName, result)
        assertFalse(result.contains("→"))
    }

    @Test
    fun `displayTitle ignores a title without a summary`() {
        assertEquals(dateName, record(title = "Old", summary = null).displayTitle())
    }

    @Test
    fun `displayTitle is the Claude title once summarized`() {
        assertEquals("Встреча", record(title = "Встреча", summary = "Обсудили сроки.").displayTitle())
    }
}
