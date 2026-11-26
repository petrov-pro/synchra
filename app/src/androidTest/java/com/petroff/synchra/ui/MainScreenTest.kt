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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.petroff.synchra.config.Language
import com.petroff.synchra.config.languageForCode
import com.petroff.synchra.session.SessionUiState
import com.petroff.synchra.session.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val english = languageForCode("en")!!
    private val russian = languageForCode("ru")!!

    @Test
    fun theLanguagesAreShownAndChangedInSettings() {
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

        // Short, in the top bar, set apart from History by a divider; the full names stay
        // available to screen readers.
        composeRule.onNodeWithText("English → Russian").assertDoesNotExist()
        composeRule.onNodeWithTag("languagePair").assertTextEquals("En-Ru")
        composeRule.onNodeWithContentDescription("English → Russian").assertExists()
        composeRule.onNodeWithTag("languagePairDivider").assertExists()
        val pair = composeRule.onNodeWithTag("languagePair").fetchSemanticsNode().boundsInRoot
        val history = composeRule.onNodeWithContentDescription("History").fetchSemanticsNode().boundsInRoot
        assertTrue(pair.right <= history.left)
        assertTrue(pair.bottom <= history.bottom && pair.top >= history.top)
        composeRule.onNodeWithTag("sourceLanguage").assertDoesNotExist()
    }

    @Test
    fun theAppNameIsShownAsABrandTitle() {
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

        composeRule.onNodeWithTag("appTitle").assertTextEquals("Synchra")
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
    fun viewModeSwitchWorksWhileListening() {
        var picked: ViewMode? = null
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = true, viewMode = ViewMode.TRANSCRIPTION_ONLY),
                sourceLanguage = english,
                targetLanguage = russian,
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = { picked = it },
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithText("Both").assertIsEnabled().performClick()

        assertEquals(ViewMode.BOTH, picked)
    }

    @Test
    fun translationPanelExplainsWhenTheRecordingHasNoTranslation() {
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(isListening = true, viewMode = ViewMode.BOTH, translationRequested = false),
                sourceLanguage = english,
                targetLanguage = russian,
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
            )
        }

        composeRule.onNodeWithText("No translation: this recording was started in Transcription mode.").assertExists()
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

    @Test
    fun crashReportConsentIsAskedOnceAndAnswered() {
        var answer: Boolean? = null
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
                askCrashReportsConsent = true,
                onCrashReportsConsent = { answer = it },
            )
        }

        composeRule.onNodeWithText("Send crash reports?").assertExists()
        composeRule.onNodeWithText("Don't send").performClick()
        assertEquals(false, answer)
    }

    @Test
    fun crashReportConsentIsNotAskedOnceAnswered() {
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

        composeRule.onNodeWithText("Send crash reports?").assertDoesNotExist()
    }

    private fun showMain(state: SessionUiState) = composeRule.setContent {
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

    @Test
    fun summaryIsHiddenInFreeMode() {
        showMain(SessionUiState(mode = com.petroff.synchra.config.Mode.FREE, lastRecordId = "r1"))
        composeRule.onNodeWithContentDescription("Summary").assertDoesNotExist()
    }

    @Test
    fun summaryIsShownInPaidMode() {
        showMain(SessionUiState(mode = com.petroff.synchra.config.Mode.PAID, lastRecordId = "r1"))
        composeRule.onNodeWithContentDescription("Summary").assertExists()
    }

    @Test
    fun preparingTranslationIsShown() {
        showMain(SessionUiState(mode = com.petroff.synchra.config.Mode.FREE, isListening = true, isPreparing = true))
        composeRule.onNodeWithText("Preparing translation…").assertExists()
    }

    @Test
    fun theReasonStartIsOffIsShown() {
        showMain(SessionUiState(mode = com.petroff.synchra.config.Mode.FREE, canStart = false, startHint = "Download the speech model in Settings"))
        composeRule.onNodeWithText("Download the speech model in Settings").assertExists()
    }

    @Test
    fun contextIsHiddenInFreeMode() {
        showMain(SessionUiState(mode = com.petroff.synchra.config.Mode.FREE))
        composeRule.onNodeWithContentDescription("Context").assertDoesNotExist()
    }

    @Test
    fun contextIsShownInPaidMode() {
        showMain(SessionUiState(mode = com.petroff.synchra.config.Mode.PAID))
        composeRule.onNodeWithContentDescription("Context").assertExists()
    }

    @Test
    fun theSilenceIconShowsWhileNobodySpeaksUnlessTurnedOff() {
        var state by mutableStateOf(SessionUiState(isListening = true, isSending = false))
        var showIcon by mutableStateOf(true)
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
                showSilenceIcon = showIcon,
            )
        }

        composeRule.onNodeWithTag("silenceIcon").assertExists()
        state = state.copy(isSending = true)
        composeRule.onNodeWithTag("silenceIcon").assertDoesNotExist()
        state = state.copy(isSending = false)
        showIcon = false
        composeRule.onNodeWithTag("silenceIcon").assertDoesNotExist()
        showIcon = true
        state = SessionUiState(isListening = false)
        composeRule.onNodeWithTag("silenceIcon").assertDoesNotExist()
    }

    @Test
    fun scrollingBackStopsFollowingTheTextUntilTheJumpButton() {
        val longText = (1..200).joinToString(" ") { "word$it" }
        var state by mutableStateOf(SessionUiState(isListening = true, viewMode = ViewMode.TRANSCRIPTION_ONLY, sourceText = longText))
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
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sourceJumpToLatest").assertDoesNotExist()

        composeRule.onNodeWithTag("sourcePanel").performTouchInput { swipeDown(startY = top + 200f, endY = bottom) }
        composeRule.onNodeWithTag("sourcePanel").performTouchInput { swipeDown(startY = top + 200f, endY = bottom) }
        composeRule.waitForIdle()
        state = state.copy(sourceText = "$longText more")
        composeRule.waitForIdle()
        // Still where the user scrolled to: the new words didn't pull the text to the end.
        composeRule.onNodeWithTag("sourceJumpToLatest").assertExists().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("sourceJumpToLatest").assertDoesNotExist()
    }

    @Test
    fun pausedSessionAsksToContinue() {
        var continued = 0
        var stopped = 0
        composeRule.setContent {
            MainScreen(
                uiState = SessionUiState(pausedAfterMinutes = 60, sourceText = "hello"),
                sourceLanguage = english,
                targetLanguage = russian,
                currentConversationContext = "",
                onToggleListening = {},
                onViewModeChange = {},
                onOpenSettings = {},
                onSaveConversationContext = {},
                onContinueSession = { continued++ },
                onStopPaused = { stopped++ },
            )
        }

        composeRule.onNodeWithText("Paused after 1 h. Continue?").assertExists()
        composeRule.onNodeWithText("Continue").performClick()
        assertEquals(1, continued)
        composeRule.onNodeWithText("Stop").performClick()
        assertEquals(1, stopped)
    }
}
