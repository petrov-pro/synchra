package com.petroff.transyncai.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.history.HistoryRepository
import com.petroff.transyncai.soniox.LiveRequest
import com.petroff.transyncai.soniox.SpeechTranslator
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Runs a live session: Soniox streams the transcript and its translation together. */
class SessionViewModel(
    private val speechTranslator: SpeechTranslator,
    private val configRepository: ConfigRepository,
    private val historyRepository: HistoryRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    init {
        refreshCanStart()
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode, canStart = hasSonioxKey()) }
    }

    /**
     * Re-checks canStart against the current ConfigRepository state. Call this whenever config
     * may have changed outside this ViewModel — e.g. after returning from Settings, where the
     * Soniox key was saved via ConfigRepository directly.
     */
    fun refreshCanStart() {
        _uiState.update { it.copy(canStart = hasSonioxKey()) }
    }

    // Soniox both recognizes and translates, so its key is needed in every view mode.
    private fun hasSonioxKey() = !configRepository.getConfig().sonioxApiKey.isNullOrBlank()

    fun start() {
        if (!hasSonioxKey()) {
            _uiState.update { it.copy(canStart = false, errorMessage = "Set your Soniox API key in Settings") }
            return
        }
        val config = configRepository.getConfig()
        _uiState.update {
            it.copy(
                isListening = true,
                sourceText = "",
                translatedText = "",
                errorMessage = null,
                canStart = true,
                lastRecordId = null,
            )
        }
        val pair = config.selectedLanguagePair
        val translate = _uiState.value.viewMode != ViewMode.TRANSCRIPTION_ONLY
        speechTranslator.start(
            LiveRequest(
                sourceLang = pair.sourceCode,
                targetLang = if (translate) pair.targetCode else null,
                // Conversation context, falling back to the general one from Settings.
                context = config.conversationContext.ifBlank { config.generalContext },
            ),
            onUpdate = { live -> _uiState.update { it.copy(sourceText = live.source, translatedText = live.translation) } },
            onError = { message -> _uiState.update { it.copy(errorMessage = message) } },
            onFinished = { live ->
                _uiState.update {
                    it.copy(isListening = false, sourceText = live.source, translatedText = live.translation)
                }
                saveHistoryRecordIfNeeded()
            },
        )
    }

    fun stop() {
        // History is saved from start's onFinished, once Soniox has flushed the last phrase and
        // its translation; flip isListening now so the UI responds immediately.
        speechTranslator.stop()
        _uiState.update { it.copy(isListening = false) }
    }

    private fun saveHistoryRecordIfNeeded() {
        val state = _uiState.value
        if (state.sourceText.isBlank() && state.translatedText.isBlank()) return
        val pair = configRepository.getConfig().selectedLanguagePair
        val record = ConversationRecord(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            title = null,
            sourceLanguageName = pair.sourceName,
            targetLanguageName = pair.targetName,
            sourceText = state.sourceText,
            translatedText = state.translatedText,
        )
        viewModelScope.launch {
            historyRepository.addRecord(record)
            _uiState.update { it.copy(lastRecordId = record.id) }
        }
    }

    /** Forgets the Summary target if that record was deleted in History meanwhile. */
    fun refreshLastRecord() {
        val id = _uiState.value.lastRecordId ?: return
        viewModelScope.launch {
            if (historyRepository.getRecord(id) == null) _uiState.update { it.copy(lastRecordId = null) }
        }
    }
}
