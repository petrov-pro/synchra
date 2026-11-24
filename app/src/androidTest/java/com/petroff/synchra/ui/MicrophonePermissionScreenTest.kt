package com.petroff.synchra.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MicrophonePermissionScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun explainsWhyAndAsksForTheMicrophone() {
        var asked = false
        composeRule.setContent {
            MicrophonePermissionScreen(permanentlyDenied = false, onAllow = { asked = true }, onOpenSettings = {})
        }

        composeRule.onNodeWithText("Synchra needs the microphone").assertExists()
        composeRule.onNodeWithText("Allow microphone").performClick()

        assertTrue(asked)
    }

    @Test
    fun afterAPermanentRefusalItSendsToAppSettings() {
        var opened = false
        composeRule.setContent {
            MicrophonePermissionScreen(permanentlyDenied = true, onAllow = {}, onOpenSettings = { opened = true })
        }

        composeRule.onNodeWithText("Allow microphone").assertDoesNotExist()
        composeRule.onNodeWithText("Open app settings").performClick()

        assertTrue(opened)
    }
}
