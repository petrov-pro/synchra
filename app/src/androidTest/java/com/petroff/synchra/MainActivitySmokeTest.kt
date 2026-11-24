package com.petroff.synchra

import android.Manifest
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    // Task 11 gates MainActivity's content behind a RECORD_AUDIO permission screen
    // (RequiresRecordAudioPermission) — without a granted permission, the test never
    // reaches the TopAppBar this test asserts on. Must run before composeRule launches
    // the Activity, hence order = 0.
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesAndShowsAppName() {
        composeRule.onNodeWithText("Synchra").assertExists()
    }

    @Test
    fun rotationKeepsTheSameAppContainer() {
        // A container per Activity reopened the database and HTTP clients on every rotation.
        val before = composeRule.activity.container
        composeRule.activityRule.scenario.recreate()
        var after: AppContainer? = null
        composeRule.activityRule.scenario.onActivity { after = it.container }

        assertSame(before, after)
    }
}

