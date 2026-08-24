package com.petroff.transyncai.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.session.SessionUiState
import com.petroff.transyncai.session.ViewMode
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
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedIndex = 0,
                onSelectLanguagePair = {},
                onToggleListening = { listening = !listening },
                onViewModeChange = {},
                onOpenSettings = {},
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
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedIndex = 0,
                onSelectLanguagePair = {},
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
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
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedIndex = 0,
                onSelectLanguagePair = {},
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
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
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedIndex = 0,
                onSelectLanguagePair = {},
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
            )
        }

        composeRule.onNodeWithText("Both").assertIsEnabled()
    }
}
