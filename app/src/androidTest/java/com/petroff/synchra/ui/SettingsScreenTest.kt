package com.petroff.synchra.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import com.petroff.synchra.config.Language
import com.petroff.synchra.config.languageForCode
import androidx.compose.ui.test.performFirstLinkClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SettingsScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val english = languageForCode("en")!!
    private val russian = languageForCode("ru")!!

    @Test
    fun theLanguagePickerSearchesAndPicksTheSpeechLanguage() {
        var picked: Language? = null
        composeRule.setContent {
            SettingsScreen(
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onSaveSonioxApiKey = {},
                onBack = {},
                sourceLanguage = english,
                targetLanguage = russian,
                recentLanguages = listOf(english, russian),
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
            SettingsScreen(
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onSaveSonioxApiKey = {},
                onBack = {},
                sourceLanguage = english,
                targetLanguage = russian,
                onSwapLanguages = { swapped = true },
            )
        }

        composeRule.onNodeWithTag("sourceLanguage").assertTextEquals("English")
        composeRule.onNodeWithTag("targetLanguage").assertTextEquals("Russian")
        composeRule.onNodeWithContentDescription("Swap languages").performClick()

        assertEquals(true, swapped)
    }

    @Test
    fun enteringAndSavingApiKeyInvokesCallback() {
        var savedKey: String? = null
        composeRule.setContent {
            SettingsScreen(
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
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
                onSaveApiKey = {},
                onSaveGeneralContext = { savedContext = it },
                onSaveSonioxApiKey = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Global context").performTextInput("Discussing a billing support case")
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
                autoFocusApiKey = true,
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
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onSaveSonioxApiKey = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithContentDescription("Help: Anthropic API key").assertExists()
        composeRule.onNodeWithContentDescription("Help: Soniox API key").assertExists()
        composeRule.onNodeWithContentDescription("Help: Global context").assertExists()
        composeRule.onNodeWithContentDescription("Help: Languages").assertExists()
    }

    @Test
    fun apiKeyHelpIconShowsExplanationAndCanBeDismissed() {
        composeRule.setContent {
            SettingsScreen(
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
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
    fun enteringAndSavingSonioxKeyInvokesCallback() {
        var savedSonioxKey: String? = null
        composeRule.setContent {
            SettingsScreen(
                currentApiKey = null,
                currentGeneralContext = "",
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

    private fun helpLinkOpens(helpFor: String): String? {
        var opened: String? = null
        composeRule.setContent {
            SettingsScreen(
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onSaveSonioxApiKey = {},
                onBack = {},
                onOpenLink = { opened = it },
            )
        }

        composeRule.onNodeWithContentDescription("Help: $helpFor").performClick()
        composeRule.onNodeWithText("How to create a key and add funds", substring = true, useUnmergedTree = true).performFirstLinkClick()
        return opened
    }

    @Test
    fun sonioxHelpOpensSetupInstructionsInTheBrowser() {
        val opened = helpLinkOpens("Soniox API key")

        assertTrue(opened!!.startsWith("https://chatgpt.com/?q=Instructions%20for%20using%20Soniox"))
    }

    @Test
    fun anthropicHelpOpensSetupInstructionsInTheBrowser() {
        val opened = helpLinkOpens("Anthropic API key")

        assertTrue(opened!!.startsWith("https://chatgpt.com/?q=Instructions%20for%20using%20Claude"))
    }

    @Test
    fun theCrashReportsSwitchAppliesAtOnce() {
        var crashReports: Boolean? = null
        composeRule.setContent {
            SettingsScreen(
                currentSonioxApiKey = null,
                currentApiKey = null,
                currentGeneralContext = "",
                onSaveSonioxApiKey = {},
                onSaveApiKey = {},
                onSaveGeneralContext = {},
                onBack = {},
                crashReportsEnabled = false,
                onCrashReportsChange = { crashReports = it },
            )
        }

        composeRule.onNodeWithTag("crashReportsSwitch").performScrollTo().performClick()
        assertEquals(true, crashReports)
    }

    private fun showSettings(
        mode: com.petroff.synchra.config.Mode,
        modelState: com.petroff.synchra.asr.ModelState = com.petroff.synchra.asr.ModelState.NotDownloaded,
        isRecording: Boolean = false,
        onSaveMode: (com.petroff.synchra.config.Mode) -> Unit = {},
        onDownload: () -> Unit = {},
    ) = composeRule.setContent {
        SettingsScreen(
            currentSonioxApiKey = null,
            currentApiKey = null,
            currentGeneralContext = "",
            onSaveApiKey = {},
            onSaveGeneralContext = {},
            onSaveSonioxApiKey = {},
            onBack = {},
            currentMode = mode,
            onSaveMode = onSaveMode,
            modelState = modelState,
            onDownloadModel = onDownload,
            isRecording = isRecording,
        )
    }

    @Test
    fun freeModeShowsTheModelRowNotTheKeys() {
        showSettings(com.petroff.synchra.config.Mode.FREE)
        composeRule.onNodeWithText("Speech model").assertExists()
        composeRule.onNodeWithText("Soniox API key").assertDoesNotExist()
        composeRule.onNodeWithText("Anthropic API key").assertDoesNotExist()
    }

    @Test
    fun paidModeShowsTheKeysNotTheModelRow() {
        showSettings(com.petroff.synchra.config.Mode.PAID)
        composeRule.onNodeWithText("Soniox API key").assertExists()
        composeRule.onNodeWithText("Speech model").assertDoesNotExist()
    }

    @Test
    fun theModeIsSavedOnSaveOnly() {
        var saved: com.petroff.synchra.config.Mode? = null
        showSettings(com.petroff.synchra.config.Mode.FREE, onSaveMode = { saved = it })

        composeRule.onNodeWithText("Paid").performClick()
        composeRule.onNodeWithText("Soniox API key").assertExists()
        assertEquals(null, saved)

        composeRule.onNodeWithText("Save").performScrollTo().performClick()
        assertEquals(com.petroff.synchra.config.Mode.PAID, saved)
    }

    @Test
    fun modelStatesShowTheirTextAndButton() {
        var downloads = 0
        showSettings(com.petroff.synchra.config.Mode.FREE, onDownload = { downloads++ })
        composeRule.onNodeWithText("Not downloaded (≈650 MB)").assertExists()
        composeRule.onNodeWithText("Wi-Fi recommended", substring = true).assertExists()
        composeRule.onNodeWithText("Download").performClick()
        assertEquals(1, downloads)
    }

    @Test
    fun downloadingShowsPercentAndCancel() {
        showSettings(com.petroff.synchra.config.Mode.FREE, com.petroff.synchra.asr.ModelState.Downloading(37))
        composeRule.onNodeWithText("Downloading 37%").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }

    @Test
    fun readyShowsDelete() {
        showSettings(com.petroff.synchra.config.Mode.FREE, com.petroff.synchra.asr.ModelState.Ready)
        composeRule.onNodeWithText("Ready").assertExists()
        composeRule.onNodeWithText("Delete").assertIsEnabled()
    }

    @Test
    fun failedTellsHowToResume() {
        showSettings(com.petroff.synchra.config.Mode.FREE, com.petroff.synchra.asr.ModelState.Failed)
        composeRule.onNodeWithText("Download failed — tap Download to resume").assertExists()
        composeRule.onNodeWithText("Download").assertExists()
        // A broken partial download can always be thrown away.
        composeRule.onNodeWithText("Delete").assertIsEnabled()
    }

    @Test
    fun recordingLocksTheModeAndDelete() {
        showSettings(com.petroff.synchra.config.Mode.FREE, com.petroff.synchra.asr.ModelState.Ready, isRecording = true)
        composeRule.onNodeWithText("Delete").assertIsNotEnabled()
        composeRule.onNodeWithText("Paid").assertIsNotEnabled()
        composeRule.onNodeWithText("Stop recording first").assertExists()
    }

    @Test
    fun unpackingIsShownAfterTheDownload() {
        showSettings(com.petroff.synchra.config.Mode.FREE, com.petroff.synchra.asr.ModelState.Unpacking)
        composeRule.onNodeWithText("Unpacking… (a minute or two)").assertExists()
        composeRule.onNodeWithText("Cancel").assertExists()
    }
}
