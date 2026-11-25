package com.petroff.synchra.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.performClick
import com.petroff.synchra.history.ConversationRecord
import com.petroff.synchra.history.ConversationUiState
import com.petroff.synchra.history.ConversationView
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import com.petroff.synchra.summary.SummarySections
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

        composeRule.onNodeWithContentDescription("Summary").assertExists()
        composeRule.onNodeWithText("Both").assertExists()
        composeRule.onNodeWithText("We agreed on the deadline.").assertExists()
        composeRule.onNodeWithText("Мы согласовали срок.").assertExists()
    }

    @Test
    fun summaryViewShowsTitleSummaryAndOriginalButton() {
        show(ConversationUiState(record = record(summary = "Обсудили сроки."), view = ConversationView.SUMMARY))

        composeRule.onNodeWithContentDescription("Original").assertExists()
        composeRule.onNodeWithText("Обсудили сроки.").assertExists()
        composeRule.onAllNodesWithText("Встреча")[0].assertExists()
        composeRule.onNodeWithText("We agreed on the deadline.").assertDoesNotExist()
    }

    @Test
    fun summaryTitleIsSetApartFromTheSummaryByADivider() {
        show(ConversationUiState(record = record(summary = "Обсудили сроки."), view = ConversationView.SUMMARY))

        val title = composeRule.onNodeWithTag("summaryTitle").getUnclippedBoundsInRoot()
        val divider = composeRule.onNodeWithTag("summaryDivider").getUnclippedBoundsInRoot()
        val body = composeRule.onNodeWithText("Обсудили сроки.").getUnclippedBoundsInRoot()

        assertTrue(title.bottom <= divider.top)
        assertTrue(divider.bottom <= body.top)
    }

    @Test
    fun summaryShowsEachBlockUnderItsHeading() {
        val stored = SummarySections("Сроки релиза", "Обсудили пятницу.", listOf("Релиз в пятницу")).encode()
        show(ConversationUiState(record = record(summary = stored), view = ConversationView.SUMMARY))

        for (text in listOf("Topic", "Сроки релиза", "Overview", "Обсудили пятницу.", "Decisions and results", "• Релиз в пятницу")) {
            composeRule.onNodeWithText(text).assertExists()
        }
        composeRule.onAllNodesWithTag("summaryBlockDivider").assertCountEquals(2)
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
        composeRule.onNodeWithContentDescription("Original").performClick()

        assertEquals(1, original)
        assertEquals(0, summary)
    }

    @Test
    fun summaryButtonInOriginalViewInvokesCallback() {
        var summary = 0
        show(ConversationUiState(record = record(), view = ConversationView.ORIGINAL), onSummaryClick = { summary++ })

        composeRule.onNodeWithContentDescription("Summary").performClick()

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
        composeRule.onNodeWithContentDescription("Summary").assertIsNotEnabled()
    }

    @Test
    fun summaryAndOriginalAreIconsNotText() {
        show(ConversationUiState(record = record(summary = "S"), view = ConversationView.SUMMARY))

        composeRule.onNodeWithContentDescription("Original").assertExists()
        composeRule.onNodeWithText("Original").assertDoesNotExist()
    }

    @Test
    fun summaryIsAnIconInTheOriginalView() {
        show(ConversationUiState(record = record(), view = ConversationView.ORIGINAL))

        composeRule.onNodeWithContentDescription("Summary").assertExists()
        composeRule.onNodeWithText("Summary").assertDoesNotExist()
    }

    @Test
    fun theRecordedLanguagesAreShownShortInTheTopBar() {
        show(ConversationUiState(record = record(summary = "Итог"), view = ConversationView.SUMMARY))

        composeRule.onNodeWithTag("languagePair").assertTextEquals("En-Ru")
        composeRule.onNodeWithContentDescription("English → Russian").assertExists()
        composeRule.onNodeWithTag("languagePairDivider").assertExists()
    }

    @Test
    fun theRecordedLanguagesAreShownInTheOriginalViewToo() {
        show(ConversationUiState(record = record(), view = ConversationView.ORIGINAL))

        composeRule.onNodeWithTag("languagePair").assertTextEquals("En-Ru")
    }
}
