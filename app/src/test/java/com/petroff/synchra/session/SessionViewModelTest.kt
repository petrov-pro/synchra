package com.petroff.synchra.session

import com.petroff.synchra.config.ConfigRepository
import com.petroff.synchra.config.DefaultConfigRepository
import com.petroff.synchra.config.FakeSettingsStore
import com.petroff.synchra.soniox.LiveRequest
import com.petroff.synchra.soniox.LiveTranscript
import com.petroff.synchra.soniox.SpeechTranslator
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import com.petroff.synchra.config.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FakeSpeechTranslator : SpeechTranslator {
    private class Session(
        val onUpdate: (LiveTranscript) -> Unit,
        val onError: (String) -> Unit,
        val onFinished: (LiveTranscript) -> Unit,
        val onAutoStopped: () -> Unit,
        val onSendingChanged: (Boolean) -> Unit,
    )

    var request: LiveRequest? = null
    var stopped = false
    private val sessions = mutableListOf<Session>()
    val startCount get() = sessions.size

    override fun start(
        request: LiveRequest,
        onUpdate: (LiveTranscript) -> Unit,
        onError: (String) -> Unit,
        onFinished: (LiveTranscript) -> Unit,
        onAutoStopped: () -> Unit,
        onSendingChanged: (Boolean) -> Unit,
    ) {
        this.request = request
        sessions += Session(onUpdate, onError, onFinished, onAutoStopped, onSendingChanged)
    }

    override fun stop() {
        stopped = true
    }

    fun emit(source: String, translation: String) = sessions.last().onUpdate(LiveTranscript(source, translation))
    fun fail(message: String) = sessions.last().onError(message)
    fun autoStop() = sessions.last().onAutoStopped()
    fun sending(on: Boolean) = sessions.last().onSendingChanged(on)
    fun finish(source: String, translation: String) = finishSession(sessions.lastIndex, source, translation)
    fun finishSession(index: Int, source: String, translation: String) =
        sessions[index].onFinished(LiveTranscript(source, translation))
    fun emitToSession(index: Int, source: String, translation: String) =
        sessions[index].onUpdate(LiveTranscript(source, translation))
}

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingCrashReporter : com.petroff.synchra.diagnostics.CrashReporter {
    val events = mutableListOf<String>()
    override fun setEnabled(enabled: Boolean) = Unit
    override fun log(event: String) { events += event }
}

class SessionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var speechTranslator: FakeSpeechTranslator
    private lateinit var configRepository: ConfigRepository
    private lateinit var historyRepository: FakeHistoryRepository
    private lateinit var liveSession: LiveSession
    private lateinit var viewModel: SessionViewModel
    private lateinit var crashReporter: RecordingCrashReporter
    private var modelReady = false
    private var onDeviceTargets = setOf("ru", "de", "en")

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        speechTranslator = FakeSpeechTranslator()
        configRepository = DefaultConfigRepository(
            store = FakeSettingsStore(),
            defaultApiBaseUrl = "https://api.anthropic.com",
        )
        configRepository.setSonioxApiKey("snx-test")
        // Most tests run the scheduler idle while listening; the time limit has its own tests.
        configRepository.setSessionLimitMinutes(0)
        historyRepository = FakeHistoryRepository()
        crashReporter = RecordingCrashReporter()
        liveSession = LiveSession(
            speechTranslator, configRepository, historyRepository, CoroutineScope(dispatcher), crashReporter,
            modelReady = { modelReady },
            onDeviceTargetCodes = { onDeviceTargets },
        )
        viewModel = SessionViewModel(liveSession)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start streams through Soniox with the language pair and context`() {
        configRepository.setGeneralContext("Cardiology lecture")

        viewModel.start()

        assertEquals(
            LiveRequest(sourceLang = "en", targetLang = "ru", context = "Global context:\nCardiology lecture"),
            speechTranslator.request,
        )
        assertTrue(viewModel.uiState.value.isListening)
    }

    @Test
    fun `start uses the languages picked on the main screen`() {
        configRepository.setSourceLanguage("hi")
        configRepository.setTargetLanguage("uk")

        viewModel.start()

        assertEquals("hi", speechTranslator.request!!.sourceLang)
        assertEquals("uk", speechTranslator.request!!.targetLang)
    }

    @Test
    fun `Soniox updates replace the shown source and translation`() {
        viewModel.start()

        speechTranslator.emit("Hello", "Привет")
        speechTranslator.emit("Hello world", "Привет мир")

        assertEquals("Hello world", viewModel.uiState.value.sourceText)
        assertEquals("Привет мир", viewModel.uiState.value.translatedText)
    }

    @Test
    fun `Transcription-only asks Soniox for no translation`() {
        viewModel.setViewMode(ViewMode.TRANSCRIPTION_ONLY)

        viewModel.start()

        assertNull(speechTranslator.request!!.targetLang)
        assertFalse(viewModel.uiState.value.translationRequested)
    }

    @Test
    fun `the view can switch while recording without touching the stream`() {
        viewModel.start()
        val request = speechTranslator.request

        viewModel.setViewMode(ViewMode.TRANSLATION_ONLY)
        viewModel.setViewMode(ViewMode.TRANSCRIPTION_ONLY)
        speechTranslator.emit("Hello", "Привет")

        assertTrue(viewModel.uiState.value.isListening)
        assertTrue(viewModel.uiState.value.translationRequested)
        assertEquals(1, speechTranslator.startCount)
        assertEquals(request, speechTranslator.request)
        assertEquals("Привет", viewModel.uiState.value.translatedText)
    }

    @Test
    fun `Soniox needs only the Soniox key, in every view mode`() {
        // No Anthropic key at all: Soniox translates by itself.
        assertTrue(viewModel.uiState.value.canStart)

        configRepository.setSonioxApiKey("")
        viewModel.setViewMode(ViewMode.TRANSCRIPTION_ONLY)
        assertFalse(viewModel.uiState.value.canStart)

        viewModel.start()
        assertNull(speechTranslator.request)
    }

    @Test
    fun `stop saves history once Soniox delivers the final transcript`() = runTest(dispatcher) {
        viewModel.start()
        speechTranslator.emit("Good morn", "Доброе")

        viewModel.stop()
        assertTrue(speechTranslator.stopped)
        assertFalse(viewModel.uiState.value.isListening)
        dispatcher.scheduler.advanceUntilIdle()
        assertTrue(historyRepository.addedRecords.isEmpty())

        speechTranslator.finish("Good morning", "Доброе утро")
        dispatcher.scheduler.advanceUntilIdle()

        val record = historyRepository.addedRecords.single()
        assertEquals("Good morning", record.sourceText)
        assertEquals("Доброе утро", record.translatedText)
    }

    @Test
    fun `a Soniox error is shown`() {
        viewModel.start()

        speechTranslator.fail("Soniox 402: Organization balance exhausted.")

        assertEquals("Soniox 402: Organization balance exhausted.", viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `finished session exposes the saved record id`() = runTest(dispatcher) {
        viewModel.start()
        viewModel.stop()
        speechTranslator.finish("Good morning", "Доброе утро")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(historyRepository.addedRecords.single().id, viewModel.uiState.value.lastRecordId)
    }

    @Test
    fun `silent session leaves no record id`() = runTest(dispatcher) {
        viewModel.start()
        viewModel.stop()
        speechTranslator.finish("", "")
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.lastRecordId)
    }

    @Test
    fun `start clears the previous record id`() = runTest(dispatcher) {
        viewModel.start()
        speechTranslator.finish("Hi", "Привет")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.start()

        assertNull(viewModel.uiState.value.lastRecordId)
    }

    @Test
    fun `refreshLastRecord clears a deleted record`() = runTest(dispatcher) {
        viewModel.start()
        speechTranslator.finish("Hi", "Привет")
        dispatcher.scheduler.advanceUntilIdle()
        historyRepository.deleteRecord(viewModel.uiState.value.lastRecordId!!)

        viewModel.refreshLastRecord()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(viewModel.uiState.value.lastRecordId)
    }

    @Test
    fun `a late finish from the previous session saves its own text and leaves the new session alone`() = runTest(dispatcher) {
        viewModel.start()
        speechTranslator.emit("First", "Первый")
        viewModel.stop()
        viewModel.start()
        speechTranslator.emit("Second", "Второй")

        speechTranslator.emitToSession(0, "First talk", "Первый разговор")
        speechTranslator.finishSession(0, "First talk", "Первый разговор")
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isListening)
        assertEquals("Second", viewModel.uiState.value.sourceText)
        assertEquals("Второй", viewModel.uiState.value.translatedText)
        val saved = historyRepository.addedRecords.single()
        assertEquals("First talk", saved.sourceText)
        assertEquals("Первый разговор", saved.translatedText)
    }

    @Test
    fun `an automatic stop after silence is explained and cleared on the next start`() {
        viewModel.start()

        speechTranslator.autoStop()
        assertEquals("Stopped after 5 minutes of silence.", viewModel.uiState.value.statusMessage)

        viewModel.start()
        assertNull(viewModel.uiState.value.statusMessage)
    }

    @Test
    fun `the session outlives its screen and a new screen shows the whole conversation`() {
        viewModel.start()
        speechTranslator.emit("Hello", "Привет")
        viewModel.viewModelScope.cancel() // the screen is gone (Back, or the system closed it)

        speechTranslator.emit("Hello, how are you", "Привет, как дела")
        val newScreen = SessionViewModel(liveSession)

        assertTrue(newScreen.uiState.value.isListening)
        assertEquals("Hello, how are you", newScreen.uiState.value.sourceText)
        assertEquals("Привет, как дела", newScreen.uiState.value.translatedText)
    }

    @Test
    fun `whether audio is being sent to Soniox is part of the state`() {
        viewModel.start()

        speechTranslator.sending(true)
        assertTrue(viewModel.uiState.value.isSending)
        speechTranslator.sending(false)
        assertFalse(viewModel.uiState.value.isSending)

        viewModel.stop()
        assertFalse(viewModel.uiState.value.isSending)
    }

    @Test
    fun `both contexts go to Soniox, the general one first`() {
        configRepository.setGeneralContext("Cardiology lecture")
        configRepository.setConversationContext("Follow-up with patient Ravi")

        viewModel.start()

        assertEquals(
            "Global context:\nCardiology lecture\n\nAbout this conversation:\nFollow-up with patient Ravi",
            speechTranslator.request!!.context,
        )
    }

    @Test
    fun `the context is saved with the conversation`() = runTest(dispatcher) {
        configRepository.setGeneralContext("Cardiology lecture")
        configRepository.setConversationContext("Follow-up with patient Ravi")
        viewModel.start()

        speechTranslator.finish("Hi", "Привет")
        dispatcher.scheduler.advanceUntilIdle()

        val record = historyRepository.addedRecords.single()
        assertEquals("Cardiology lecture", record.userContext)
        assertEquals("Follow-up with patient Ravi", record.context)
    }

    @Test
    fun `the conversation keeps the languages it was recorded in`() = runTest(dispatcher) {
        viewModel.start()
        configRepository.setTargetLanguage("de") // changed mid-recording

        speechTranslator.finish("Hi", "Привет")
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("Russian", historyRepository.addedRecords.single().targetLanguageName)
    }

    @Test
    fun `crash reports get the session steps but never what was said or the context`() {
        configRepository.setGeneralContext("Secret project Falcon")
        configRepository.setConversationContext("Meeting with Maria")

        viewModel.start()
        speechTranslator.emit("Top secret words", "Совершенно секретно")
        speechTranslator.fail("Soniox: connection lost")
        viewModel.stop()
        speechTranslator.finish("Top secret words", "Совершенно секретно")

        assertEquals(
            listOf("session start en-ru translate=true", "session error: Soniox: connection lost", "session stop"),
            crashReporter.events,
        )
    }

    // --- Free mode ---

    private fun freeMode() {
        configRepository.setMode(com.petroff.synchra.config.Mode.FREE)
        liveSession.refreshCanStart()
    }

    @Test
    fun `free mode without the speech model cannot start`() {
        freeMode()
        assertFalse(viewModel.uiState.value.canStart)

        viewModel.start()

        assertEquals("Download the speech model in Settings", viewModel.uiState.value.errorMessage)
        assertEquals(0, speechTranslator.startCount)
    }

    @Test
    fun `free mode with an unsupported speech language cannot start, paid can`() {
        modelReady = true
        configRepository.setSourceLanguage("af")
        freeMode()
        assertFalse(viewModel.uiState.value.canStart)
        viewModel.start()
        assertEquals("Pick a language the free mode supports", viewModel.uiState.value.errorMessage)

        configRepository.setMode(com.petroff.synchra.config.Mode.PAID)
        liveSession.refreshCanStart()

        assertTrue(viewModel.uiState.value.canStart)
        assertEquals("af", configRepository.getConfig().sourceLanguage.code)
    }

    @Test
    fun `free transcription only ignores the target language`() {
        modelReady = true
        onDeviceTargets = emptySet()
        freeMode()
        assertFalse(viewModel.uiState.value.canStart)

        viewModel.setViewMode(ViewMode.TRANSCRIPTION_ONLY)

        assertTrue(viewModel.uiState.value.canStart)
    }

    @Test
    fun `free mode needs no Soniox key`() {
        modelReady = true
        configRepository.setSonioxApiKey("")
        freeMode()

        assertTrue(viewModel.uiState.value.canStart)
        assertEquals(com.petroff.synchra.config.Mode.FREE, viewModel.uiState.value.mode)
        viewModel.start()
        assertEquals(1, speechTranslator.startCount)
    }

    @Test
    fun `preparing shows while translation models get ready`() {
        liveSession.setPreparing(true)
        assertTrue(viewModel.uiState.value.isPreparing)
        liveSession.setPreparing(false)
        assertFalse(viewModel.uiState.value.isPreparing)
    }

    @Test
    fun `the reason Start is off is shown without pressing it`() {
        freeMode()
        assertEquals("Download the speech model in Settings", viewModel.uiState.value.startHint)

        modelReady = true
        liveSession.refreshCanStart()
        assertNull(viewModel.uiState.value.startHint)
    }

    // --- Session time limit ---

    private fun passMinutes(minutes: Long) {
        dispatcher.scheduler.advanceTimeBy(minutes * 60_000)
        dispatcher.scheduler.runCurrent()
    }

    private fun startWithLimit(minutes: Int = 60) {
        configRepository.setSessionLimitMinutes(minutes)
        viewModel.start()
        dispatcher.scheduler.runCurrent()
    }

    private val state get() = viewModel.uiState.value

    @Test
    fun `paid session pauses after the limit without activity`() {
        startWithLimit()
        passMinutes(59)
        assertFalse(speechTranslator.stopped)
        passMinutes(1)
        assertTrue(speechTranslator.stopped)

        speechTranslator.finish("a", "A")
        dispatcher.scheduler.runCurrent()
        assertEquals(60, state.pausedAfterMinutes)
        assertEquals("a", historyRepository.records.single().sourceText)
    }

    @Test
    fun `pause waits for the engine to finish`() {
        startWithLimit()
        passMinutes(60)
        assertTrue(speechTranslator.stopped)
        assertNull(state.pausedAfterMinutes)
    }

    @Test
    fun `user activity resets the limit`() {
        startWithLimit()
        passMinutes(50)
        liveSession.onUserActivity()
        passMinutes(50)
        assertFalse(speechTranslator.stopped)
        passMinutes(10)
        assertTrue(speechTranslator.stopped)
    }

    @Test
    fun `free mode and an off limit never pause`() {
        startWithLimit(0)
        passMinutes(180)
        assertFalse(speechTranslator.stopped)
        viewModel.stop()
        speechTranslator.finish("x", "X")

        speechTranslator.stopped = false
        modelReady = true
        configRepository.setMode(Mode.FREE)
        liveSession.refreshCanStart()
        startWithLimit(60)
        passMinutes(180)
        assertFalse(speechTranslator.stopped)
    }

    private fun pauseWith(source: String, translation: String) {
        startWithLimit()
        speechTranslator.emit(source, translation)
        passMinutes(60)
        speechTranslator.finish(source, translation)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `continue appends to the earlier text`() {
        pauseWith("a", "A")
        liveSession.continueSession()
        assertEquals(2, speechTranslator.startCount)
        assertNull(state.pausedAfterMinutes)
        assertTrue(state.isListening)
        assertEquals("a", state.sourceText)

        speechTranslator.emit("b", "B")
        assertEquals("a\n\nb", state.sourceText)
        assertEquals("A\n\nB", state.translatedText)
    }

    @Test
    fun `continue keeps one history record with the whole text`() {
        pauseWith("a", "A")
        liveSession.continueSession()
        viewModel.stop()
        speechTranslator.finish("b", "B")
        dispatcher.scheduler.advanceUntilIdle()

        val record = historyRepository.records.single()
        assertEquals("a\n\nb", record.sourceText)
        assertEquals("A\n\nB", record.translatedText)
    }

    @Test
    fun `continue without a key stays paused`() {
        pauseWith("a", "A")
        configRepository.setSonioxApiKey("")
        liveSession.continueSession()

        assertEquals(1, speechTranslator.startCount)
        assertEquals(60, state.pausedAfterMinutes)
        assertNotNull(state.errorMessage)
        assertEquals("a", state.sourceText)
    }

    @Test
    fun `stop while paused ends the conversation`() {
        pauseWith("a", "A")
        viewModel.stop()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(state.pausedAfterMinutes)
        assertEquals(1, speechTranslator.startCount)
        assertEquals("a", historyRepository.records.single().sourceText)
        assertEquals("a", state.sourceText)
    }

    @Test
    fun `silence auto-stop cancels the limit`() {
        startWithLimit()
        passMinutes(5)
        speechTranslator.autoStop()
        speechTranslator.finish("a", "A")
        speechTranslator.stopped = false
        passMinutes(120)
        assertNull(state.pausedAfterMinutes)
        assertFalse(speechTranslator.stopped)
    }

    @Test
    fun `limit is read when re-armed`() {
        startWithLimit(60)
        passMinutes(10)
        configRepository.setSessionLimitMinutes(30)
        liveSession.onUserActivity()
        passMinutes(30)
        assertTrue(speechTranslator.stopped)
    }

    @Test
    fun `a new start clears the earlier text`() {
        pauseWith("a", "A")
        liveSession.continueSession()
        viewModel.stop()
        speechTranslator.finish("b", "B")
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.start()
        speechTranslator.emit("c", "C")
        assertEquals("c", state.sourceText)
        viewModel.stop()
        speechTranslator.finish("c", "C")
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(2, historyRepository.records.size)
    }

    @Test
    fun `recording holds until the paused engine finishes`() {
        // RecordingService keeps the process alive while isListening: Soniox's last text and
        // the save must not run in an unprotected background process.
        startWithLimit()
        passMinutes(60)
        assertTrue(speechTranslator.stopped)
        assertTrue(state.isListening)
        speechTranslator.finish("a", "A")
        dispatcher.scheduler.runCurrent()
        assertFalse(state.isListening)
        assertEquals(60, state.pausedAfterMinutes)
    }

    @Test
    fun `continue keeps a summary made during the pause`() {
        pauseWith("a", "A")
        val id = historyRepository.records.single().id
        kotlinx.coroutines.runBlocking { historyRepository.setSummary(id, "Billing call", "They agreed.") }
        liveSession.continueSession()
        viewModel.stop()
        speechTranslator.finish("b", "B")
        dispatcher.scheduler.advanceUntilIdle()

        val record = historyRepository.records.single()
        assertEquals("a\n\nb", record.sourceText)
        assertEquals("Billing call", record.title)
        assertEquals("They agreed.", record.summary)
    }
}
