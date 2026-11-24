package com.petroff.transyncai.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.petroff.transyncai.history.ConversationRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun record(id: String, title: String? = null) = ConversationRecord(
        id = id,
        timestamp = 1_700_000_000_000L,
        title = title,
        sourceLanguageName = "English",
        targetLanguageName = "Russian",
        sourceText = "hello world",
        translatedText = "привет мир",
    )

    @Test
    fun emptyListShowsEmptyState() {
        composeRule.setContent {
            HistoryScreen(
                records = emptyList(),
                searchQuery = "",
                onSearchQueryChange = {},
                onRecordClick = {},
                onDeleteRecord = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("No conversations yet.").assertExists()
    }

    @Test
    fun tappingARowInvokesOnRecordClickWithItsId() {
        var clickedId: String? = null
        composeRule.setContent {
            HistoryScreen(
                records = listOf(record(id = "abc", title = "My conversation")),
                searchQuery = "",
                onSearchQueryChange = {},
                onRecordClick = { clickedId = it },
                onDeleteRecord = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("My conversation").performClick()

        assertEquals("abc", clickedId)
    }

    @Test
    fun deleteRequiresConfirmationBeforeInvokingOnDeleteRecord() {
        var deletedId: String? = null
        composeRule.setContent {
            HistoryScreen(
                records = listOf(record(id = "abc", title = "My conversation")),
                searchQuery = "",
                onSearchQueryChange = {},
                onRecordClick = {},
                onDeleteRecord = { deletedId = it },
                onBack = {},
            )
        }

        composeRule.onNodeWithContentDescription("Delete conversation").performClick()
        assertEquals(null, deletedId)

        composeRule.onNodeWithText("Delete").performClick()

        assertEquals("abc", deletedId)
    }

    @Test
    fun typingInSearchInvokesOnSearchQueryChange() {
        var query = ""
        composeRule.setContent {
            HistoryScreen(
                records = emptyList(),
                searchQuery = query,
                onSearchQueryChange = { query = it },
                onRecordClick = {},
                onDeleteRecord = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Search").performTextInput("invoice")

        assertEquals("invoice", query)
    }
}
