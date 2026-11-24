package com.petroff.transyncai.ui

import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = { savedKey = it },
                onSaveGeneralContext = {},
                onSaveSonioxApiKey = {},
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
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveGeneralContext = { savedContext = it },
                onSaveSonioxApiKey = {},
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
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "Existing context text",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onSaveSonioxApiKey = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Existing context text").assertExists()
    }

    @Test
    fun apiKeyFieldIsNotFocusedByDefault() {
        composeRule.setContent {
            SettingsScreen(
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onSaveSonioxApiKey = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Soniox API key").assertIsNotFocused()
    }

    @Test
    fun apiKeyFieldIsFocusedWhenAutoFocusRequested() {
        composeRule.setContent {
            SettingsScreen(
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                autoFocusApiKey = true,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onSaveSonioxApiKey = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Soniox API key").assertIsFocused()
    }

    @Test
    fun everySettingsFieldHasAHelpIcon() {
        composeRule.setContent {
            SettingsScreen(
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onSaveSonioxApiKey = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithContentDescription("Help: Languages").assertExists()
        composeRule.onNodeWithContentDescription("Help: Anthropic API key").assertExists()
        composeRule.onNodeWithContentDescription("Help: Soniox API key").assertExists()
        composeRule.onNodeWithContentDescription("Help: General context").assertExists()
    }

    @Test
    fun apiKeyHelpIconShowsExplanationAndCanBeDismissed() {
        composeRule.setContent {
            SettingsScreen(
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onSaveSonioxApiKey = {},
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
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
                languagePairs = pairs,
                selectedLanguagePairIndex = selectedIndex,
                onSelectLanguagePair = { selectedIndex = it },
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onSaveSonioxApiKey = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("English → Russian").assertExists()
        composeRule.onNodeWithText("English → Russian").performClick()
        composeRule.onNodeWithText("English → Spanish").performClick()

        assertEquals(1, selectedIndex)
    }

    @Test
    fun enteringAndSavingSonioxKeyInvokesCallback() {
        var savedSonioxKey: String? = null
        composeRule.setContent {
            SettingsScreen(
                currentApiKey = null,
                currentGeneralContext = "",
                languagePairs = DEFAULT_LANGUAGE_PAIRS,
                selectedLanguagePairIndex = 0,
                onSelectLanguagePair = {},
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onBack = {},
                currentSonioxApiKey = null,
                onSaveSonioxApiKey = { savedSonioxKey = it },
            )
        }

        composeRule.onNodeWithText("Soniox API key").performTextInput("snx-abc")
        composeRule.onNodeWithText("Save").performScrollTo().performClick()

        assertEquals("snx-abc", savedSonioxKey)
    }
}
