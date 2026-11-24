package com.petroff.transyncai.session

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.petroff.transyncai.asr.AsrSegment
import com.petroff.transyncai.asr.SpeechRecognizer
import com.petroff.transyncai.config.AppConfig
import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.config.Engine
import com.petroff.transyncai.context.ContextBuffer
import com.petroff.transyncai.history.ConversationRecord
import com.petroff.transyncai.history.HistoryRepository
import com.petroff.transyncai.soniox.LiveRequest
import com.petroff.transyncai.soniox.SpeechTranslator
import com.petroff.transyncai.translate.Translator
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private data class QueuedChunk(
    val text: String,
    val context: String,
    val sourceCode: String,
    val targetCode: String,
)

class SessionViewModel(
    private val speechRecognizer: SpeechRecognizer,
    private val translator: Translator,
    private val configRepository: ConfigRepository,
    private val historyRepository: HistoryRepository,
    // Used instead of speechRecognizer + translator when the Soniox engine is selected.
    private val speechTranslator: SpeechTranslator,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    private var contextBuffer = ContextBuffer(configRepository.getConfig().bufferSize)
    private var chunker = TranslationChunker(minChunkWords = configRepository.getConfig().chunkSizeWords)
    private var translationQueue: Channel<QueuedChunk>? = null
    private var translationJob: Job? = null
    private var sonioxActive = false

    init {
        _uiState.update { it.copy(canStart = computeCanStart(it.viewMode)) }
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode, canStart = computeCanStart(mode)) }
    }

    /**
     * Re-checks canStart against the current ConfigRepository state without changing
     * viewMode. Call this whenever config may have changed outside this ViewModel's own
     * setters — e.g. after returning from Settings, where the API key was saved via
     * ConfigRepository directly, not through this ViewModel.
     */
    fun refreshCanStart() {
        _uiState.update { it.copy(canStart = computeCanStart(it.viewMode)) }
    }

    private fun computeCanStart(mode: ViewMode): Boolean {
        val config = configRepository.getConfig()
        // Soniox both recognizes and translates, so its key is needed in every mode.
        if (config.engine == Engine.SONIOX) return !config.sonioxApiKey.isNullOrBlank()
        val needsTranslation = mode != ViewMode.TRANSCRIPTION_ONLY
        return !needsTranslation || !config.apiKey.isNullOrBlank()
    }

    fun start() {
        val config = configRepository.getConfig()
        if (!computeCanStart(_uiState.value.viewMode)) {
            val keyName = if (config.engine == Engine.SONIOX) "Soniox" else "Anthropic"
            _uiState.update { it.copy(canStart = false, errorMessage = "Set your $keyName API key in Settings") }
            return
        }
        if (config.engine == Engine.SONIOX) {
            startSoniox(config)
            return
        }

        contextBuffer = ContextBuffer(config.bufferSize)
        chunker = TranslationChunker(minChunkWords = config.chunkSizeWords)
        _uiState.update {
            it.copy(isListening = true, sourceText = "", translatedText = "", errorMessage = null, canStart = true)
        }

        // A single ordered consumer processes chunks one at a time, so translated pieces are
        // appended in the order they were spoken even if individual network calls complete out
        // of order (a later-committed chunk's request finishing before an earlier one's).
        val queue = Channel<QueuedChunk>(Channel.UNLIMITED)
        translationQueue = queue
        translationJob = viewModelScope.launch { processTranslationQueue(queue) }

        speechRecognizer.start { segment -> onSegment(segment, config) }
    }

    fun stop() {
        if (sonioxActive) {
            // History is saved from startSoniox's onFinished, once Soniox has flushed the last
            // phrase and its translation.
            speechTranslator.stop()
            _uiState.update { it.copy(isListening = false) }
            return
        }
        speechRecognizer.stop()
        // Flip isListening immediately so the UI responds the instant the customer taps
        // Stop — the history save below waits on in-flight translations and must not
        // delay that.
        _uiState.update { it.copy(isListening = false) }
        val queue = translationQueue
        val job = translationJob
        translationQueue = null
        translationJob = null
        viewModelScope.launch {
            queue?.close()
            // Wait for processTranslationQueue's loop to fully drain and exit before
            // snapshotting uiState into the history record — otherwise a translation
            // still in flight when Stop was tapped would silently be missing from the
            // saved record, with no way to fix that after the fact.
            job?.join()
            saveHistoryRecordIfNeeded()
        }
    }

    private fun startSoniox(config: AppConfig) {
        sonioxActive = true
        _uiState.update {
            it.copy(isListening = true, sourceText = "", translatedText = "", errorMessage = null, canStart = true)
        }
        val pair = config.selectedLanguagePair
        val translate = _uiState.value.viewMode != ViewMode.TRANSCRIPTION_ONLY
        speechTranslator.start(
            LiveRequest(
                sourceLang = pair.sourceCode,
                targetLang = if (translate) pair.targetCode else null,
                context = config.conversationContext.ifBlank { config.generalContext },
            ),
            onUpdate = { live -> _uiState.update { it.copy(sourceText = live.source, translatedText = live.translation) } },
            onError = { message -> _uiState.update { it.copy(errorMessage = message) } },
            onFinished = { live ->
                sonioxActive = false
                _uiState.update {
                    it.copy(isListening = false, sourceText = live.source, translatedText = live.translation)
                }
                saveHistoryRecordIfNeeded()
            },
        )
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
        viewModelScope.launch { historyRepository.addRecord(record) }
    }

    private fun onSegment(segment: AsrSegment, config: AppConfig) {
        Log.d(TAG, "onSegment: text=\"${segment.text}\" isFinal=${segment.isFinal}")
        contextBuffer.addSegment(segment.text, segment.isFinal)
        val displaySource = listOf(contextBuffer.context(), contextBuffer.currentPhrase())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _uiState.update { it.copy(sourceText = displaySource) }

        if (_uiState.value.viewMode == ViewMode.TRANSCRIPTION_ONLY) return

        // Soft-commit chunking: translate ~minChunkWords new words as soon as they're stable,
        // without waiting for a pause — isFinal (endpoint/silence) is only the flush trigger
        // for whatever's left at the end of an utterance, not the sole trigger for translating.
        val chunk = if (segment.isFinal) chunker.onFinal(segment.text) else chunker.onPartialUpdate(segment.text)
        if (chunk.isNullOrBlank()) return

        val pair = config.selectedLanguagePair
        Log.d(TAG, "onSegment: enqueueing chunk \"$chunk\" (${pair.sourceCode}->${pair.targetCode})")
        translationQueue?.trySend(QueuedChunk(chunk, effectiveContext(config), pair.sourceCode, pair.targetCode))
    }

    // Standing context (conversation context, falling back to general context — see
    // SettingsScreen/the Main screen's Context popup) combined with the rolling transcript of
    // recently finalized phrases, so the translator gets both the customer's domain/topic hints and
    // continuity with what was just said.
    private fun effectiveContext(config: AppConfig): String {
        val standingContext = config.conversationContext.ifBlank { config.generalContext }
        return listOf(standingContext, contextBuffer.context()).filter { it.isNotBlank() }.joinToString("\n")
    }

    private suspend fun processTranslationQueue(queue: Channel<QueuedChunk>) {
        for (queued in queue) {
            translator.translate(queued.context, queued.text, queued.sourceCode, queued.targetCode)
                .onSuccess { text ->
                    Log.d(TAG, "processTranslationQueue: translate SUCCESS -> \"$text\"")
                    _uiState.update {
                        val appended = listOf(it.translatedText, text).filter { part -> part.isNotBlank() }.joinToString(" ")
                        it.copy(translatedText = appended, errorMessage = null)
                    }
                }
                .onFailure { e ->
                    Log.e(TAG, "processTranslationQueue: translate FAILURE: ${e.message}", e)
                    _uiState.update { it.copy(errorMessage = e.message ?: "Translation failed") }
                }
        }
    }

    companion object {
        private const val TAG = "TransyncAI/Session"
    }
}
