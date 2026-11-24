package com.petroff.transyncai.ui

import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.ComposeNavigator
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.testing.TestNavHostController
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Double taps during a screen transition must not stack screens twice or pop past the start. */
class SafeNavigationTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var navController: TestNavHostController

    @Before
    fun setUp() {
        composeRule.setContent {
            navController = TestNavHostController(LocalContext.current).apply {
                navigatorProvider.addNavigator(ComposeNavigator())
            }
            NavHost(navController = navController, startDestination = "main") {
                composable("main") { Text("Main") }
                composable("conversation/{id}") { Text("Conversation") }
            }
        }
    }

    private fun routes() = navController.currentBackStack.value.mapNotNull { it.destination.route }

    @Test
    fun doubleTapOpensTheScreenOnce() {
        composeRule.runOnUiThread {
            navController.navigateOnce("conversation/r1")
            navController.navigateOnce("conversation/r1")
        }
        composeRule.waitForIdle()

        assertEquals(1, routes().count { it == "conversation/{id}" })
    }

    @Test
    fun doubleBackNeverLeavesAnEmptyScreen() {
        composeRule.runOnUiThread { navController.navigate("conversation/r1") }
        composeRule.waitForIdle()

        composeRule.runOnUiThread {
            navController.popOnce()
            navController.popOnce()
        }
        composeRule.waitForIdle()

        assertEquals("main", navController.currentDestination?.route)
    }
}
