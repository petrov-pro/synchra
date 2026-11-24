package com.petroff.transyncai.session

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow

/** The main screen's view of the app-wide [LiveSession]; closing the screen doesn't stop it. */
class SessionViewModel(private val liveSession: LiveSession) : ViewModel() {

    val uiState: StateFlow<SessionUiState> = liveSession.uiState

    fun setViewMode(mode: ViewMode) = liveSession.setViewMode(mode)

    fun refreshCanStart() = liveSession.refreshCanStart()

    fun start() = liveSession.start()

    fun stop() = liveSession.stop()

    fun refreshLastRecord() = liveSession.refreshLastRecord()
}
