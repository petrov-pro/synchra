package com.petroff.synchra

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.petroff.synchra.ui.SynchraApp

class MainActivity : ComponentActivity() {
    internal val container: AppContainer
        get() = (application as SynchraApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SynchraApp(container)
        }
    }
}
