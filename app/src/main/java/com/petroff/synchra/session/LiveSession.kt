package com.petroff.synchra.session

import com.petroff.synchra.config.ConfigRepository
import com.petroff.synchra.config.LanguagePair
import com.petroff.synchra.history.ConversationRecord
import com.petroff.synchra.history.HistoryRepository
import com.petroff.synchra.soniox.LiveRequest
import com.petroff.synchra.soniox.LiveTranscript
import com.petroff.synchra.soniox.SpeechTranslator
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The live session: Soniox streams the transcript and its translation together. Owned by the
 * app, not by a screen, so recording goes on in the background (RecordingService keeps the
 * process alive) and a screen opened later shows the whole conversation so far.
 */
class LiveSession(
    private val speechTranslator: SpeechTranslator,
    private val configRepository: ConfigRepository,
    private val historyRepository: HistoryRepository,
    // Outlives screens: saving the conversation must not depend on one being open.
    private val scope: CoroutineScope,
) {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    // Callbacks of a stopped session can arrive after the next one started; they may only save
    // their own conversation, never touch the screen of the current session.
    @Volatile private var currentSession = 0

    init {
        refreshCanStart()
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode, canStart = hasSonioxKey()) }
    }

    /**
     * Re-checks canStart against the current ConfigRepository state. Call this whenever config
     * may have changed outside this session — e.g. after returning from Settings, where the
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
        val session = ++currentSession
        _uiState.update {
            it.copy(
                isListening = true,
                sourceText = "",
                translatedText = "",
                errorMessage = null,
                canStart = true,
                lastRecordId = null,
                statusMessage = null,
                isSending = false,
                startedAt = System.currentTimeMillis(),
            )
        }
        val pair = config.selectedLanguagePair
        val translate = _uiState.value.viewMode != ViewMode.TRANSCRIPTION_ONLY
        val userContext = config.generalContext
        val conversationContext = config.conversationContext
        // Soniox gets both as one text, each under a heading so it knows what each part is.
        val sonioxContext = listOfNotNull(
            userContext.takeIf { it.isNotBlank() }?.let { "Background:\n$it" },
            conversationContext.takeIf { it.isNotBlank() }?.let { "About this conversation:\n$it" },
        ).joinToString("\n\n")
        speechTranslator.start(
            LiveRequest(
                sourceLang = pair.sourceCode,
                targetLang = if (translate) pair.targetCode else null,
                context = sonioxContext,
            ),
            onUpdate = { live ->
                if (session == currentSession) {
                    _uiState.update { it.copy(sourceText = live.source, translatedText = live.translation) }
                }
            },
            onError = { message ->
                if (session == currentSession) _uiState.update { it.copy(errorMessage = message) }
            },
            onAutoStopped = {
                if (session == currentSession) {
                    _uiState.update { it.copy(statusMessage = "Stopped after 5 minutes of silence.") }
                }
            },
            onSendingChanged = { sending ->
                if (session == currentSession) _uiState.update { it.copy(isSending = sending) }
            },
            onFinished = { live ->
                val isCurrent = session == currentSession
                if (isCurrent) {
                    _uiState.update {
                        it.copy(
                            isListening = false,
                            isSending = false,
                            sourceText = live.source,
                            translatedText = live.translation,
                        )
                    }
                }
                saveHistoryRecordIfNeeded(live, isCurrent, userContext, conversationContext, pair)
            },
        )
    }

    fun stop() {
        // History is saved from start's onFinished, once Soniox has flushed the last phrase and
        // its translation; flip isListening now so the UI responds immediately.
        speechTranslator.stop()
        _uiState.update { it.copy(isListening = false, isSending = false) }
    }

    // pair and contexts are the ones the session started with: Settings may change meanwhile.
    private fun saveHistoryRecordIfNeeded(
        live: LiveTranscript,
        isCurrentSession: Boolean,
        userContext: String,
        conversationContext: String,
        pair: LanguagePair,
    ) {
        if (live.source.isBlank() && live.translation.isBlank()) return
        val record = ConversationRecord(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            title = null,
            sourceLanguageName = pair.sourceName,
            targetLanguageName = pair.targetName,
            sourceText = live.source,
            translatedText = live.translation,
            context = conversationContext.ifBlank { null },
            userContext = userContext.ifBlank { null },
        )
        scope.launch {
            historyRepository.addRecord(record)
            // Summary target only for the session on screen; an older one is just saved.
            if (isCurrentSession) _uiState.update { it.copy(lastRecordId = record.id) }
        }
    }

    /** Forgets the Summary target if that record was deleted in History meanwhile. */
    fun refreshLastRecord() {
        val id = _uiState.value.lastRecordId ?: return
        scope.launch {
            if (historyRepository.getRecord(id) == null) _uiState.update { it.copy(lastRecordId = null) }
        }
    }
}
