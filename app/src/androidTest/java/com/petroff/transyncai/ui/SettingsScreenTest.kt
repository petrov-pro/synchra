package com.petroff.transyncai.ui

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.config.LanguagePair
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun enteringAndSavingApiKeyInvokesCallback() {
        var savedKey: String? = null
        composeRule.setContent {
            SettingsScreen(
                currentApiKey = null,
                currentBufferSize = 6,
                currentChunkSizeWords = 8,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = { savedKey = it },
                onSaveBufferSize = {},
                onSaveChunkSizeWords = {},
                onSaveGeneralContext = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Anthropic API key").performTextInput("sk-abc-123")
        composeRule.onNodeWithText("Save").performClick()

        assert(savedKey == "sk-abc-123")
    }

    @Test
    fun enteringAndSavingGeneralContextInvokesCallback() {
        var savedContext: String? = null
        composeRule.setContent {
            SettingsScreen(
                currentApiKey = null,
                currentBufferSize = 6,
                currentChunkSizeWords = 8,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveBufferSize = {},
                onSaveChunkSizeWords = {},
                onSaveGeneralContext = { savedContext = it },
                onBack = {},
            )
        }

        composeRule.onNodeWithText("General context").performTextInput("Discussing a billing support case")
        composeRule.onNodeWithText("Save").performClick()

        assertEquals("Discussing a billing support case", savedContext)
    }

    @Test
    fun generalContextFieldShowsCurrentValue() {
        composeRule.setContent {
            SettingsScreen(
                currentApiKey = null,
                currentBufferSize = 6,
                currentChunkSizeWords = 8,
                currentGeneralContext = "Existing context text",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveBufferSize = {},
                onSaveChunkSizeWords = {},
                onSaveGeneralContext = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Existing context text").assertExists()
    }

    @Test
    fun apiKeyFieldIsNotFocusedByDefault() {
        composeRule.setContent {
            SettingsScreen(
                currentApiKey = null,
                currentBufferSize = 6,
                currentChunkSizeWords = 8,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveBufferSize = {},
                onSaveChunkSizeWords = {},
                onSaveGeneralContext = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Anthropic API key").assertIsNotFocused()
    }

    @Test
    fun apiKeyFieldIsFocusedWhenAutoFocusRequested() {
        composeRule.setContent {
            SettingsScreen(
                currentApiKey = null,
                currentBufferSize = 6,
                currentChunkSizeWords = 8,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                autoFocusApiKey = true,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveBufferSize = {},
                onSaveChunkSizeWords = {},
                onSaveGeneralContext = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Anthropic API key").assertIsFocused()
    }

    @Test
    fun everySettingsFieldHasAHelpIcon() {
        composeRule.setContent {
            SettingsScreen(
                currentApiKey = null,
                currentBufferSize = 6,
                currentChunkSizeWords = 8,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveBufferSize = {},
                onSaveChunkSizeWords = {},
                onSaveGeneralContext = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithContentDescription("Help: Languages").assertExists()
        composeRule.onNodeWithContentDescription("Help: Anthropic API key").assertExists()
        composeRule.onNodeWithContentDescription("Help: Context buffer size").assertExists()
        composeRule.onNodeWithContentDescription("Help: Translation chunk size").assertExists()
        composeRule.onNodeWithContentDescription("Help: General context").assertExists()
    }

    @Test
    fun apiKeyHelpIconShowsExplanationAndCanBeDismissed() {
        composeRule.setContent {
            SettingsScreen(
                currentApiKey = null,
                currentBufferSize = 6,
                currentChunkSizeWords = 8,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveBufferSize = {},
                onSaveChunkSizeWords = {},
                onSaveGeneralContext = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithContentDescription("Help: Anthropic API key").performClick()
        composeRule.onNodeWithText("Anthropic (Claude)", substring = true).assertExists()

        composeRule.onNodeWithText("Got it").performClick()
        composeRule.onNodeWithText("Anthropic (Claude)", substring = true).assertDoesNotExist()
    }

    @Test
    fun languageDropdownShowsCurrentPairAndSelectingUpdatesIt() {
        val pairs = DEFAULT_LANGUAGE_PAIRS + LanguagePair("en", "English", "es", "Spanish")
        var selectedIndex = 0
        composeRule.setContent {
            SettingsScreen(
                currentApiKey = null,
                currentBufferSize = 6,
                currentChunkSizeWords = 8,
                currentGeneralContext = "",
                languagePairs = pairs,
                selectedLanguagePairIndex = selectedIndex,
                onSelectLanguagePair = { selectedIndex = it },
                onSaveApiKey = {},
                onSaveBufferSize = {},
                onSaveChunkSizeWords = {},
                onSaveGeneralContext = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("English → Russian").assertExists()
        composeRule.onNodeWithText("English → Russian").performClick()
        composeRule.onNodeWithText("English → Spanish").performClick()

        assertEquals(1, selectedIndex)
    }
}
