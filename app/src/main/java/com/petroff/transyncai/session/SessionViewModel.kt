package com.petroff.transyncai.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.petroff.transyncai.asr.AsrSegment
import com.petroff.transyncai.asr.SpeechRecognizer
import com.petroff.transyncai.config.AppConfig
import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.context.ContextBuffer
import com.petroff.transyncai.translate.Translator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SessionViewModel(
    private val speechRecognizer: SpeechRecognizer,
    private val translator: Translator,
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private var contextBuffer = ContextBuffer(configRepository.getConfig().bufferSize)

    init {
        _uiState.update { it.copy(canStart = computeCanStart(it.viewMode)) }
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode, canStart = computeCanStart(mode)) }
    }

    private fun computeCanStart(mode: ViewMode): Boolean {
        val needsTranslation = mode != ViewMode.TRANSCRIPTION_ONLY
        return !needsTranslation || !configRepository.getConfig().apiKey.isNullOrBlank()
    }

    fun start() {
        val config = configRepository.getConfig()
        if (!computeCanStart(_uiState.value.viewMode)) {
            _uiState.update { it.copy(canStart = false, errorMessage = "Set your Kimi API key in Settings") }
            return
        }

        contextBuffer = ContextBuffer(config.bufferSize)
        _uiState.update {
            it.copy(isListening = true, sourceText = "", translatedText = "", errorMessage = null, canStart = true)
        }

        speechRecognizer.start { segment -> onSegment(segment, config) }
    }

    fun stop() {
        speechRecognizer.stop()
        _uiState.update { it.copy(isListening = false) }
    }

    private fun onSegment(segment: AsrSegment, config: AppConfig) {
        contextBuffer.addSegment(segment.text, segment.isFinal)
        val displaySource = listOf(contextBuffer.context(), contextBuffer.currentPhrase())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _uiState.update { it.copy(sourceText = displaySource) }

        if (_uiState.value.viewMode == ViewMode.TRANSCRIPTION_ONLY) return
        if (!segment.isFinal) return

        val pair = config.selectedLanguagePair
        viewModelScope.launch {
            translator.translate(contextBuffer.context(), segment.text, pair.sourceCode, pair.targetCode)
                .onSuccess { text -> _uiState.update { it.copy(translatedText = text, errorMessage = null) } }
                .onFailure { e -> _uiState.update { it.copy(errorMessage = e.message ?: "Translation failed") } }
        }
    }
}
