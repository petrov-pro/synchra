package com.petroff.transyncai.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.session.SessionUiState
import com.petroff.transyncai.session.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startStopButtonTogglesLabel() {
        var listening = false
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = listening),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = { listening = !listening },
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithText("Start").assertExists()
    }

    @Test
    fun transcriptionOnlyModeHidesTranslationPanel() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(
                    viewMode = ViewMode.TRANSCRIPTION_ONLY,
                    sourceText = "hello world",
                    translatedText = "should not show",
                ),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithText("hello world").assertExists()
        composeRule.onNodeWithText("should not show").assertDoesNotExist()
    }

    @Test
    fun viewModeSwitchIsDisabledWhileListening() {
        // viewMode is pinned away from the BOTH default (which the other tests rely on)
        // because BOTH renders a "Translation" panel section header alongside the
        // "Translation" segmented-button label, making onNodeWithText("Translation")
        // ambiguous (2 matching nodes) and the assertion below fail regardless of which
        // node it happened to resolve to. TRANSCRIPTION_ONLY hides that header so the
        // segmented button is the only "Translation" node, without touching what's under
        // test: whether all three segmented buttons are disabled while listening.
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = true, viewMode = ViewMode.TRANSCRIPTION_ONLY),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithText("Both").assertIsNotEnabled()
        composeRule.onNodeWithText("Transcription").assertIsNotEnabled()
        composeRule.onNodeWithText("Translation").assertIsNotEnabled()
    }

    @Test
    fun viewModeSwitchIsEnabledWhenNotListening() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = false),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithText("Both").assertIsEnabled()
    }

    @Test
    fun contextButtonIsDisabledWhileListening() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = true),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithContentDescription("Context").assertIsNotEnabled()
    }

    @Test
    fun contextButtonIsShownInTranscriptionOnlyMode() {
        // Soniox uses the context for recognition too, not only for translation.
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.TRANSCRIPTION_ONLY),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithContentDescription("Context").assertExists()
    }

    @Test
    fun contextButtonOpensDialogPrefilledWithCurrentConversationContextAndSavingInvokesCallback() {
        var saved: String? = null
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = false),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "Existing conversation topic",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = { saved = it },
            )
        }

        composeRule.onNodeWithContentDescription("Context").performClick()
        composeRule.onNodeWithText("Existing conversation topic").assertExists()
        composeRule.onNodeWithText("Existing conversation topic").performTextReplacement("New conversation topic")
        composeRule.onNodeWithText("Save").performClick()

        assertEquals("New conversation topic", saved)
    }

    @Test
    fun tappingBothWithoutApiKeyRedirectsInsteadOfSwitchingMode() {
        var viewModeChanged = false
        var redirected = false
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.TRANSCRIPTION_ONLY),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                hasApiKey = false,
                onToggleListening = {},
                onViewModeChange = { viewModeChanged = true },
                onOpenSettings = {},
                onSaveConversationContext = {},
                onApiKeyRequired = { redirected = true },
            )
        }

        composeRule.onNodeWithText("Both").performClick()

        assert(redirected)
        assert(!viewModeChanged)
    }

    @Test
    fun tappingTranscriptionWithoutApiKeyAlsoRedirects() {
        // Soniox does the transcription too, so every mode needs its key.
        var newMode: ViewMode? = null
        var redirected = false
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.BOTH),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                hasApiKey = false,
                onToggleListening = {},
                onViewModeChange = { newMode = it },
                onOpenSettings = {},
                onSaveConversationContext = {},
                onApiKeyRequired = { redirected = true },
            )
        }

        composeRule.onNodeWithText("Transcription").performClick()

        assertEquals(null, newMode)
        assertEquals(true, redirected)
    }

    @Test
    fun bothAndTranslationSwitchModeNormallyOnceApiKeyIsSet() {
        var newMode: ViewMode? = null
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.TRANSCRIPTION_ONLY),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                hasApiKey = true,
                onToggleListening = {},
                onViewModeChange = { newMode = it },
                onOpenSettings = {},
                onSaveConversationContext = {},
                onApiKeyRequired = { throw AssertionError("should not redirect when a key is set") },
            )
        }

        composeRule.onNodeWithText("Both").performClick()

        assertEquals(ViewMode.BOTH, newMode)
    }

    @Test
    fun dividerIsShownBetweenPanelsOnlyInBothMode() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.BOTH),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithTag("sourceTranslationDivider").assertExists()
    }

    @Test
    fun dividerIsNotShownInTranscriptionOnlyMode() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(viewMode = ViewMode.TRANSCRIPTION_ONLY),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithTag("sourceTranslationDivider").assertDoesNotExist()
    }

    @Test
    fun summaryButtonDisabledWithoutLastRecordAndWhileListening() {
        var state by mutableStateOf(SessionUiState(lastRecordId = null))
        composeRule.setContent {
            MainScreen(
                uiState = state,
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithContentDescription("Summary").assertIsNotEnabled()
        state = SessionUiState(isListening = true, lastRecordId = "r1")
        composeRule.onNodeWithContentDescription("Summary").assertIsNotEnabled()
        state = SessionUiState(lastRecordId = "r1")
        composeRule.onNodeWithContentDescription("Summary").assertIsEnabled()
    }

    @Test
    fun historyAndSummaryButtonsInvokeCallbacks() {
        var history = 0
        var summary = 0
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(lastRecordId = "r1"),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onOpenHistory = { history++ },
                onSummary = { summary++ },
            )
        }

        composeRule.onNodeWithContentDescription("History").performClick()
        composeRule.onNodeWithContentDescription("Summary").performClick()

        assertEquals(1, history)
        assertEquals(1, summary)
    }

    @Test
    fun historyAndSummaryAreTopBarIconsNextToContextAndSettings() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(lastRecordId = "r1"),
                currentLanguagePair = DEFAULT_LANGUAGE_PAIRS.first(),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        // Icons only — no text buttons left in the content area.
        composeRule.onNodeWithText("History").assertDoesNotExist()
        composeRule.onNodeWithText("Summary").assertDoesNotExist()
        composeRule.onNodeWithTag("historySummaryDivider").assertExists()
        val history = composeRule.onNodeWithContentDescription("History").fetchSemanticsNode().boundsInRoot
        val settings = composeRule.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        assertEquals(settings.top, history.top)
    }
}

