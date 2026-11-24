package com.petroff.transyncai.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.history.ConversationUiState
import com.petroff.transyncai.history.ConversationView
import com.petroff.transyncai.session.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Long-pressing a transcript, translation or summary copies it to the clipboard. */
class CopyOnLongPressTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val clipboard = context.getSystemService(ClipboardManager::class.java)

    private fun clipboardText(): String? {
        var text: String? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
        }
        return text
    }

    @Before
    fun clearClipboard() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { clipboard.clearPrimaryClip() }
    }

    @Test
    fun longPressOnSourceCopiesTheTranscript() {
        composeRule.setContent { TranscriptPanels(ViewMode.BOTH, "Hello world", "Привет мир") }

        composeRule.onNodeWithTag("sourcePanel").performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals("Hello world", clipboardText())
    }

    @Test
    fun longPressOnTranslationCopiesTheTranslation() {
        composeRule.setContent { TranscriptPanels(ViewMode.BOTH, "Hello world", "Привет мир") }

        composeRule.onNodeWithTag("translationPanel").performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals("Привет мир", clipboardText())
    }

    @Test
    fun longPressOnAnEmptyPanelCopiesNothing() {
        composeRule.setContent { TranscriptPanels(ViewMode.BOTH, "", "") }

        composeRule.onNodeWithTag("sourcePanel").performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals(null, clipboardText())
    }

    @Test
    fun longPressOnSummaryCopiesTitleAndSummary() {
        val record = ConversationRecord(
            id = "r1", timestamp = 1_700_000_000_000L, title = "Встреча", sourceLanguageName = "English",
            targetLanguageName = "Russian", sourceText = "s", translatedText = "t", summary = "Обсудили сроки.",
        )
        composeRule.setContent {
            ConversationScreen(
                state = ConversationUiState(record = record, view = ConversationView.SUMMARY),
                onBack = {}, onSummaryClick = {}, onOriginalClick = {}, onViewModeChange = {}, onDismissError = {},
            )
        }

        composeRule.onNodeWithTag("summaryText").performTouchInput { longClick() }
        composeRule.waitForIdle()

        assertEquals("Встреча\n\nОбсудили сроки.", clipboardText())
    }
}
