package com.petroff.synchra.session

data class SessionUiState(
    val isListening: Boolean = false,
    val viewMode: ViewMode = ViewMode.BOTH,
    val sourceText: String = "",
    val translatedText: String = "",
    val errorMessage: String? = null,
    val canStart: Boolean = true,
    // Record saved by the last finished session in this app run — the main-screen Summary target.
    val lastRecordId: String? = null,
    // Neutral notice, e.g. why a session ended by itself.
    val statusMessage: String? = null,
    // Audio is going to Soniox right now (billed); false during pauses the silence gate holds back.
    val isSending: Boolean = false,
    // When the current/last session started, for the notification's timer.
    val startedAt: Long? = null,
)
