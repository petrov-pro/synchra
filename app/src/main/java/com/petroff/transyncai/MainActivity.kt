package com.petroff.transyncai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.petroff.transyncai.ui.TransyncAiApp

class MainActivity : ComponentActivity() {
    internal val container: AppContainer
        get() = (application as TransyncApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TransyncAiApp(container)
        }
    }
}
