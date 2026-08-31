package com.petroff.transyncai.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRecordTest {

    private fun record(title: String?) = ConversationRecord(
        id = "1",
        timestamp = 1756675200000L,
        title = title,
        sourceLanguageName = "English",
        targetLanguageName = "Russian",
        sourceText = "hello",
        translatedText = "привет",
    )

    @Test
    fun `displayTitle returns the explicit title when set`() {
        val result = record(title = "My conversation").displayTitle()

        assertEquals("My conversation", result)
    }

    @Test
    fun `displayTitle falls back to timestamp and language pair when title is null`() {
        val result = record(title = null).displayTitle()

        assertTrue(result.contains("· English → Russian"))
    }

    @Test
    fun `displayTitle falls back to timestamp and language pair when title is blank`() {
        val result = record(title = "   ").displayTitle()

        assertTrue(result.contains("· English → Russian"))
    }
}
