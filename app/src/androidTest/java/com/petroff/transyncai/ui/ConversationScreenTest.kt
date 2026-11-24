package com.petroff.transyncai.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.history.ConversationUiState
import com.petroff.transyncai.history.ConversationView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ConversationScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun record(summary: String? = null) = ConversationRecord(
        id = "r1",
        timestamp = 1_700_000_000_000L,
        title = summary?.let { "Встреча" },
        sourceLanguageName = "English",
        targetLanguageName = "Russian",
        sourceText = "We agreed on the deadline.",
        translatedText = "Мы согласовали срок.",
        summary = summary,
    )

    private fun show(
        state: ConversationUiState,
        onSummaryClick: () -> Unit = {},
        onOriginalClick: () -> Unit = {},
        onDismissError: () -> Unit = {},
    ) = composeRule.setContent {
        ConversationScreen(
            state = state,
            onBack = {},
            onSummaryClick = onSummaryClick,
            onOriginalClick = onOriginalClick,
            onViewModeChange = {},
            onDismissError = onDismissError,
        )
    }

    @Test
    fun originalViewShowsTextsAndSummaryButton() {
        show(ConversationUiState(record = record(), view = ConversationView.ORIGINAL))

        composeRule.onNodeWithText("Summary").assertExists()
        composeRule.onNodeWithText("Both").assertExists()
        composeRule.onNodeWithText("We agreed on the deadline.").assertExists()
        composeRule.onNodeWithText("Мы согласовали срок.").assertExists()
    }

    @Test
    fun summaryViewShowsTitleSummaryAndOriginalButton() {
        show(ConversationUiState(record = record(summary = "Обсудили сроки."), view = ConversationView.SUMMARY))

        composeRule.onNodeWithText("Original").assertExists()
        composeRule.onNodeWithText("Обсудили сроки.").assertExists()
        composeRule.onAllNodesWithText("Встреча")[0].assertExists()
        composeRule.onNodeWithText("We agreed on the deadline.").assertDoesNotExist()
    }

    @Test
    fun tappingOriginalAndSummaryInvokesCallbacks() {
        var summary = 0
        var original = 0
        show(
            ConversationUiState(record = record(summary = "S"), view = ConversationView.SUMMARY),
            onSummaryClick = { summary++ },
            onOriginalClick = { original++ },
        )
        composeRule.onNodeWithText("Original").performClick()

        assertEquals(1, original)
        assertEquals(0, summary)
    }

    @Test
    fun summaryButtonInOriginalViewInvokesCallback() {
        var summary = 0
        show(ConversationUiState(record = record(), view = ConversationView.ORIGINAL), onSummaryClick = { summary++ })

        composeRule.onNodeWithText("Summary").performClick()

        assertEquals(1, summary)
    }

    @Test
    fun errorShowsPopupAndOkDismisses() {
        var dismissed = 0
        show(
            ConversationUiState(record = record(), errorMessage = "Your Anthropic balance is empty. Top it up at console.anthropic.com."),
            onDismissError = { dismissed++ },
        )

        composeRule.onNodeWithText("Your Anthropic balance is empty. Top it up at console.anthropic.com.").assertExists()
        composeRule.onNodeWithText("OK").performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun progressShownAndButtonDisabledWhileSummarizing() {
        show(ConversationUiState(record = record(), isSummarizing = true))

        composeRule.onNodeWithText("Making summary…").assertExists()
        composeRule.onNodeWithText("Summary").assertIsNotEnabled()
    }
}
