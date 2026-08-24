package com.petroff.transyncai.session

data class SessionUiState(
    val isListening: Boolean = false,
    val viewMode: ViewMode = ViewMode.BOTH,
    val sourceText: String = "",
    val translatedText: String = "",
    val errorMessage: String? = null,
    val canStart: Boolean = true,
)
