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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class SessionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var speechTranslator: FakeSpeechTranslator
    private lateinit var configRepository: ConfigRepository
    private lateinit var historyRepository: FakeHistoryRepository
    private lateinit var liveSession: LiveSession
    private lateinit var viewModel: SessionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        speechTranslator = FakeSpeechTranslator()
        configRepository = DefaultConfigRepository(
            store = FakeSettingsStore(),
            defaultApiBaseUrl = "https://api.anthropic.com",
        )
        configRepository.setSonioxApiKey("snx-test")
        historyRepository = FakeHistoryRepository()
        liveSession = LiveSession(speechTranslator, configRepository, historyRepository, CoroutineScope(dispatcher))
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
            LiveRequest(sourceLang = "en", targetLang = "ru", context = "Background:\nCardiology lecture"),
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
            "Background:\nCardiology lecture\n\nAbout this conversation:\nFollow-up with patient Ravi",
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
}
