package com.petroff.synchra

import android.os.Bundle
import android.view.MotionEvent
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

    // Any touch in the app means the customer is here: the session time limit starts over.
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) container.liveSession.onUserActivity()
        return super.dispatchTouchEvent(event)
    }
}
