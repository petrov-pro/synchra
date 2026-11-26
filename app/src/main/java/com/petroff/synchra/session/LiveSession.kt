package com.petroff.synchra.session

import com.petroff.synchra.diagnostics.CrashReporter
import com.petroff.synchra.diagnostics.NoOpCrashReporter
import com.petroff.synchra.config.ConfigRepository
import com.petroff.synchra.config.LanguagePair
import com.petroff.synchra.config.Mode
import com.petroff.synchra.config.sourceLanguagesFor
import com.petroff.synchra.config.targetLanguagesFor
import com.petroff.synchra.history.ConversationRecord
import com.petroff.synchra.history.HistoryRepository
import com.petroff.synchra.soniox.LiveRequest
import com.petroff.synchra.soniox.LiveTranscript
import com.petroff.synchra.soniox.SpeechTranslator
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    // Gets the steps only (start, stop, errors), never the texts or contexts.
    private val crashReporter: CrashReporter = NoOpCrashReporter,
    // Free mode: whether the on-device speech model is downloaded.
    private val modelReady: () -> Boolean = { false },
    // Free mode: languages the on-device translator can translate into.
    private val onDeviceTargetCodes: () -> Set<String> = { emptySet() },
) {

    private val _uiState = MutableStateFlow(SessionUiState())
    val uiState: StateFlow<SessionUiState> = _uiState.asStateFlow()

    // Callbacks of a stopped session can arrive after the next one started; they may only save
    // their own conversation, never touch the screen of the current session.
    @Volatile private var currentSession = 0

    // One conversation in history across pauses: Continue keeps its record id.
    private class Conversation(val recordId: String, val startedAt: Long)
    private var conversation = Conversation(UUID.randomUUID().toString(), 0)
    // Text from before the last pause; the engine's new text goes after it.
    private var prefix = LiveTranscript("", "")
    private var limitJob: Job? = null
    // The session the time limit stopped, and the limit, until its onFinished arrives.
    @Volatile private var pausing: Pair<Int, Int>? = null

    init {
        refreshCanStart()
    }

    fun setViewMode(mode: ViewMode) {
        _uiState.update { it.copy(viewMode = mode) }
        refreshCanStart()
    }

    /**
     * Re-checks canStart against the current ConfigRepository state. Call this whenever config
     * may have changed outside this session — e.g. after returning from Settings, where the
     * Soniox key was saved via ConfigRepository directly.
     */
    fun refreshCanStart() {
        val mode = configRepository.getConfig().mode
        val problem = startProblem()
        _uiState.update {
            it.copy(canStart = problem == null, mode = mode, startHint = problem.takeIf { mode == Mode.FREE })
        }
    }

    /** Free mode: the on-device translation models are being made ready. */
    fun setPreparing(preparing: Boolean) {
        _uiState.update { it.copy(isPreparing = preparing) }
    }

    /** Why Start can't work right now, or null when it can. */
    private fun startProblem(): String? {
        val config = configRepository.getConfig()
        return when (config.mode) {
            // Soniox both recognizes and translates, so its key is needed in every view mode.
            Mode.PAID -> "Set your Soniox API key in Settings".takeIf { config.sonioxApiKey.isNullOrBlank() }
            Mode.FREE -> {
                val translate = _uiState.value.viewMode != ViewMode.TRANSCRIPTION_ONLY
                val sourceOk = config.sourceLanguage in sourceLanguagesFor(Mode.FREE)
                val targetOk = !translate || config.targetLanguage in targetLanguagesFor(Mode.FREE, onDeviceTargetCodes())
                when {
                    !modelReady() -> "Download the speech model in Settings"
                    !sourceOk || !targetOk -> "Pick a language the free mode supports"
                    else -> null
                }
            }
        }
    }

    fun start() {
        startProblem()?.let { problem ->
            _uiState.update { it.copy(canStart = false, errorMessage = problem) }
            return
        }
        conversation = Conversation(UUID.randomUUID().toString(), System.currentTimeMillis())
        prefix = LiveTranscript("", "")
        _uiState.update {
            it.copy(
                sourceText = "",
                translatedText = "",
                lastRecordId = null,
                startedAt = System.currentTimeMillis(),
                pausedAfterMinutes = null,
            )
        }
        launchEngine()
    }

    /** After a pause: listens again, the new text going after the text so far, same record. */
    fun continueSession() {
        if (_uiState.value.pausedAfterMinutes == null) return
        startProblem()?.let { problem ->
            _uiState.update { it.copy(errorMessage = problem) }
            return
        }
        prefix = LiveTranscript(_uiState.value.sourceText, _uiState.value.translatedText)
        _uiState.update { it.copy(pausedAfterMinutes = null) }
        launchEngine()
    }

    /** A touch in the app: the customer is here, so the time limit starts over. */
    fun onUserActivity() {
        if (_uiState.value.isListening) armLimit(currentSession)
    }

    private fun launchEngine() {
        val config = configRepository.getConfig()
        val session = ++currentSession
        val conversation = conversation
        val prefix = prefix
        _uiState.update {
            it.copy(
                isListening = true,
                errorMessage = null,
                canStart = true,
                statusMessage = null,
                isSending = false,
            )
        }
        val pair = config.selectedLanguagePair
        val translate = _uiState.value.viewMode != ViewMode.TRANSCRIPTION_ONLY
        crashReporter.log("session start ${pair.sourceCode}-${pair.targetCode} translate=$translate")
        _uiState.update { it.copy(translationRequested = translate) }
        val userContext = config.generalContext
        val conversationContext = config.conversationContext
        // Soniox gets both as one text, each under a heading so it knows what each part is.
        val sonioxContext = listOfNotNull(
            userContext.takeIf { it.isNotBlank() }?.let { "Global context:\n$it" },
            conversationContext.takeIf { it.isNotBlank() }?.let { "About this conversation:\n$it" },
        ).joinToString("\n\n")
        armLimit(session)
        speechTranslator.start(
            LiveRequest(
                sourceLang = pair.sourceCode,
                targetLang = if (translate) pair.targetCode else null,
                context = sonioxContext,
            ),
            onUpdate = { live ->
                if (session == currentSession) {
                    val shown = prefix + live
                    _uiState.update { it.copy(sourceText = shown.source, translatedText = shown.translation) }
                }
            },
            onError = { message ->
                crashReporter.log("session error: $message")
                if (session == currentSession) _uiState.update { it.copy(errorMessage = message) }
            },
            onAutoStopped = {
                crashReporter.log("session auto-stopped after silence")
                if (session == currentSession) {
                    cancelLimit()
                    _uiState.update { it.copy(statusMessage = "Stopped after 5 minutes of silence.") }
                }
            },
            onSendingChanged = { sending ->
                if (session == currentSession) _uiState.update { it.copy(isSending = sending) }
            },
            onFinished = { live ->
                val whole = prefix + live
                val isCurrent = session == currentSession
                if (isCurrent) {
                    cancelLimit()
                    // Paused by the time limit: the dialog waits for the engine's last text.
                    val paused = pausing?.takeIf { it.first == session }?.second
                    pausing = null
                    _uiState.update {
                        it.copy(
                            isListening = false,
                            isSending = false,
                            sourceText = whole.source,
                            translatedText = whole.translation,
                            pausedAfterMinutes = paused,
                        )
                    }
                }
                saveHistoryRecordIfNeeded(whole, conversation, isCurrent, userContext, conversationContext, pair)
            },
        )
    }

    fun stop() {
        cancelLimit()
        pausing = null
        if (_uiState.value.pausedAfterMinutes != null) {
            // Paused: the engine is already stopped and the text saved; Stop just closes the dialog.
            _uiState.update { it.copy(pausedAfterMinutes = null) }
            return
        }
        // History is saved from start's onFinished, once Soniox has flushed the last phrase and
        // its translation; flip isListening now so the UI responds immediately.
        crashReporter.log("session stop")
        speechTranslator.stop()
        _uiState.update { it.copy(isListening = false, isSending = false) }
    }

    // Paid mode only: a forgotten session would keep paying Soniox. The limit is read each time
    // it is armed, so a change in Settings counts from the next touch.
    private fun armLimit(session: Int) {
        cancelLimit()
        val config = configRepository.getConfig()
        val minutes = config.sessionLimitMinutes
        if (config.mode != Mode.PAID || minutes <= 0) return
        limitJob = scope.launch {
            delay(minutes * 60_000L)
            if (session != currentSession || !_uiState.value.isListening) return@launch
            crashReporter.log("session paused after limit")
            pausing = session to minutes
            // isListening stays true until onFinished: RecordingService keeps the process alive
            // while the engine hands over its last text and the record is saved.
            speechTranslator.stop()
        }
    }

    private fun cancelLimit() {
        limitJob?.cancel()
        limitJob = null
    }

    // pair and contexts are the ones the session started with: Settings may change meanwhile.
    private fun saveHistoryRecordIfNeeded(
        live: LiveTranscript,
        conversation: Conversation,
        isCurrentSession: Boolean,
        userContext: String,
        conversationContext: String,
        pair: LanguagePair,
    ) {
        if (live.source.isBlank() && live.translation.isBlank()) return
        // The same id after a pause and Continue: the record is replaced, never doubled.
        val record = ConversationRecord(
            id = conversation.recordId,
            timestamp = conversation.startedAt,
            title = null,
            sourceLanguageName = pair.sourceName,
            targetLanguageName = pair.targetName,
            sourceText = live.source,
            translatedText = live.translation,
            context = conversationContext.ifBlank { null },
            userContext = userContext.ifBlank { null },
        )
        scope.launch {
            // Saved before at a pause: a title and summary made meanwhile are kept.
            val earlier = historyRepository.getRecord(record.id)
            historyRepository.addRecord(record.copy(title = earlier?.title, summary = earlier?.summary))
            // Summary target only for the session on screen; an older one is just saved.
            if (isCurrentSession) _uiState.update { it.copy(lastRecordId = record.id) }
        }
    }

    fun refreshLastRecord() {
        val id = _uiState.value.lastRecordId ?: return
        scope.launch {
            if (historyRepository.getRecord(id) == null) _uiState.update { it.copy(lastRecordId = null) }
        }
    }
}

/** Text before a pause, a blank line, then the text after it. */
private operator fun LiveTranscript.plus(next: LiveTranscript) = LiveTranscript(
    source = listOf(source, next.source).filter { it.isNotBlank() }.joinToString("\n\n"),
    translation = listOf(translation, next.translation).filter { it.isNotBlank() }.joinToString("\n\n"),
)
