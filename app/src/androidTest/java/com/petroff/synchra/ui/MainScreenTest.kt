package com.petroff.synchra.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.petroff.synchra.config.Language
import com.petroff.synchra.config.languageForCode
import com.petroff.synchra.session.SessionUiState
import com.petroff.synchra.session.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MainScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val english = languageForCode("en")!!
    private val russian = languageForCode("ru")!!

    @Test
    fun theLanguagePickerSearchesAndPicksTheSpeechLanguage() {
        var picked: Language? = null
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(),
                sourceLanguage = english,
                targetLanguage = russian,
                recentLanguages = listOf(english, russian),
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onSourceLanguageChange = { picked = it },
            )
        }

        composeRule.onNodeWithTag("sourceLanguage").performClick()
        composeRule.onNodeWithText("Recent").assertExists()
        composeRule.onNodeWithTag("languageSearch").performTextInput("hind")
        composeRule.onNodeWithText("Hindi").performClick()

        assertEquals("hi", picked?.code)
        composeRule.onNodeWithTag("languageSearch").assertDoesNotExist()
    }

    @Test
    fun theSwapButtonSwapsLanguages() {
        var swapped = false
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(),
                sourceLanguage = english,
                targetLanguage = russian,
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onSwapLanguages = { swapped = true },
            )
        }

        composeRule.onNodeWithTag("sourceLanguage").assertTextEquals("English")
        composeRule.onNodeWithTag("targetLanguage").assertTextEquals("Russian")
        composeRule.onNodeWithContentDescription("Swap languages").performClick()

        assertEquals(true, swapped)
    }

    @Test
    fun languagesCannotChangeWhileListening() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = true),
                sourceLanguage = english,
                targetLanguage = russian,
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithTag("sourceLanguage").assertIsNotEnabled()
        composeRule.onNodeWithTag("targetLanguage").assertIsNotEnabled()
        composeRule.onNodeWithContentDescription("Swap languages").assertIsNotEnabled()
    }

    @Test
    fun startStopButtonTogglesLabel() {
        var listening = false
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = listening),
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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
                sourceLanguage = english,
                targetLanguage = russian,
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

    @Test
    fun conversationContextStopsAtItsLimitAndShowsACounter() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(),
                sourceLanguage = english,
                targetLanguage = russian,
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithContentDescription("Context").performClick()
        composeRule.onAllNodesWithText("About this conversation", useUnmergedTree = true).onFirst().assertExists()
        composeRule.onAllNodes(hasSetTextAction())[0].performTextInput("x".repeat(2_100))

        composeRule.onNodeWithText("2000 / 2000").assertExists()
    }
}

