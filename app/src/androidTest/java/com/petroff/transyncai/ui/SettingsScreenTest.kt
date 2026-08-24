package com.petroff.transyncai.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
                onSaveApiKey = { savedKey = it },
                onSaveBufferSize = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithText("Kimi API key").performTextInput("sk-abc-123")
        composeRule.onNodeWithText("Save").performClick()

        assert(savedKey == "sk-abc-123")
    }
}
