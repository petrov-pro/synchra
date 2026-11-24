package com.petroff.transyncai.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.history.displayDateTime
import com.petroff.transyncai.history.displayTitle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class HistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun record(
        id: String,
        title: String? = null,
        summary: String? = title?.let { "summary" },
        timestamp: Long = 1_700_000_000_000L,
    ) = ConversationRecord(
        id = id,
        timestamp = timestamp,
        title = title,
        sourceLanguageName = "English",
        targetLanguageName = "Russian",
        sourceText = "hello world",
        translatedText = "привет мир",
        summary = summary,
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

    @Test
    fun unsummarizedRecordIsNamedByDateAndSummarizedByTitle() {
        val unsummarized = record(id = "a")
        composeRule.setContent {
            HistoryScreen(
                records = listOf(unsummarized, record(id = "b", title = "Встреча", timestamp = 1_700_100_000_000L)),
                searchQuery = "",
                onSearchQueryChange = {},
                onRecordClick = {},
                onDeleteRecord = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText(unsummarized.displayTitle()).assertExists()
        composeRule.onNodeWithText("Встреча").assertExists()
    }

    @Test
    fun summarizedRecordShowsTitleAndDateTime() {
        val summarized = record(id = "b", title = "Встреча")
        composeRule.setContent {
            HistoryScreen(
                records = listOf(summarized),
                searchQuery = "",
                onSearchQueryChange = {},
                onRecordClick = {},
                onDeleteRecord = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Встреча").assertExists()
        composeRule.onNodeWithText(summarized.displayDateTime()).assertExists()
    }

    @Test
    fun eachRecordIsItsOwnCard() {
        composeRule.setContent {
            HistoryScreen(
                records = listOf(record(id = "a"), record(id = "b", title = "Встреча")),
                searchQuery = "",
                onSearchQueryChange = {},
                onRecordClick = {},
                onDeleteRecord = {},
                onBack = {},
            )
        }

        composeRule.onAllNodesWithTag("historyRecord").assertCountEquals(2)
    }

    @Test
    fun orderSwitchShowsCurrentOrderAndInvokesCallback() {
        var toggles = 0
        composeRule.setContent {
            HistoryScreen(
                records = listOf(record(id = "a")),
                searchQuery = "",
                onSearchQueryChange = {},
                onRecordClick = {},
                onDeleteRecord = {},
                onBack = {},
                newestFirst = false,
                onToggleOrder = { toggles++ },
            )
        }

        composeRule.onNodeWithContentDescription("Oldest first").performClick()

        assertEquals(1, toggles)
    }
}

