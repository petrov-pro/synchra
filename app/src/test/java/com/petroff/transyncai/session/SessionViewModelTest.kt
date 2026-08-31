package com.petroff.transyncai.session

import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.config.DefaultConfigRepository
import com.petroff.transyncai.config.FakeSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var recognizer: FakeSpeechRecognizer
    private lateinit var translator: FakeTranslator
    private lateinit var configRepository: ConfigRepository
    private lateinit var historyRepository: FakeHistoryRepository
    private lateinit var viewModel: SessionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        recognizer = FakeSpeechRecognizer()
        translator = FakeTranslator()
        configRepository = DefaultConfigRepository(
            store = FakeSettingsStore(),
            defaultBufferSize = 6,
            defaultApiBaseUrl = "https://api.moonshot.ai/v1",
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
            defaultChunkSizeWords = 8,
        )
        configRepository.setApiKey("sk-test")
        historyRepository = FakeHistoryRepository()
        viewModel = SessionViewModel(recognizer, translator, configRepository, historyRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start with a key in Both mode starts the recognizer`() {
        viewModel.start()
        assertTrue(recognizer.started)
        assertTrue(viewModel.uiState.value.isListening)
    }

    @Test
    fun `Both mode calls the translator for each final segment`() = runTest(dispatcher) {
        viewModel.start()
        recognizer.emit("hello world", isFinal = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf("hello world"), translator.calls)
        assertEquals("translated", viewModel.uiState.value.translatedText)
    }

    @Test
    fun `Transcription-only mode never calls the translator`() = runTest(dispatcher) {
        viewModel.setViewMode(ViewMode.TRANSCRIPTION_ONLY)
        viewModel.start()
        recognizer.emit("hello world", isFinal = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(translator.calls.isEmpty())
        assertTrue(viewModel.uiState.value.sourceText.contains("hello world"))
    }

    @Test
    fun `Transcription-only mode can start even without an api key`() {
        configRepository.setApiKey("")
        viewModel.setViewMode(ViewMode.TRANSCRIPTION_ONLY)

        viewModel.start()

        assertTrue(recognizer.started)
    }

    @Test
    fun `Both mode blocks start when the api key is missing`() {
        configRepository.setApiKey("")
        viewModel.setViewMode(ViewMode.BOTH)

        viewModel.start()

        assertFalse(recognizer.started)
        assertFalse(viewModel.uiState.value.canStart)
    }

    @Test
    fun `canStart is false proactively, before any start attempt, when Both mode has no key`() {
        val noKeyRepo = DefaultConfigRepository(
            store = FakeSettingsStore(),
            defaultBufferSize = 6,
            defaultApiBaseUrl = "https://api.moonshot.ai/v1",
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
            defaultChunkSizeWords = 8,
        )
        val freshViewModel = SessionViewModel(FakeSpeechRecognizer(), FakeTranslator(), noKeyRepo, FakeHistoryRepository())

        assertFalse(freshViewModel.uiState.value.canStart)
    }

    @Test
    fun `refreshCanStart picks up a key saved directly via ConfigRepository`() {
        configRepository.setApiKey("")
        viewModel.setViewMode(ViewMode.BOTH)
        assertFalse(viewModel.uiState.value.canStart)

        configRepository.setApiKey("sk-saved-in-settings")
        viewModel.refreshCanStart()

        assertTrue(viewModel.uiState.value.canStart)
    }

    @Test
    fun `stop saves a history record with the final transcript and translation`() = runTest(dispatcher) {
        viewModel.start()
        recognizer.emit("hello world", isFinal = true)
        dispatcher.scheduler.advanceUntilIdle()

        viewModel.stop()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, historyRepository.addedRecords.size)
        val saved = historyRepository.addedRecords.single()
        assertEquals("English", saved.sourceLanguageName)
        assertEquals("Russian", saved.targetLanguageName)
        assertTrue(saved.sourceText.contains("hello world"))
        assertEquals("translated", saved.translatedText)
    }

    @Test
    fun `stop does not save a history record when nothing was said`() = runTest(dispatcher) {
        viewModel.start()

        viewModel.stop()
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(historyRepository.addedRecords.isEmpty())
    }

    @Test
    fun `stop stops the recognizer and updates state`() {
        viewModel.start()
        viewModel.stop()

        assertTrue(recognizer.stopped)
        assertFalse(viewModel.uiState.value.isListening)
    }

    @Test
    fun `Both mode translates a chunk once enough new words accumulate, without waiting for isFinal`() = runTest(dispatcher) {
        viewModel.start()
        // Default chunker threshold is 8 stable words with a 2-word unstable tail, so 11+
        // growing words in a single (non-final) partial update crosses it.
        recognizer.emit("we need to move this consumer over to the new worker service", isFinal = false)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(translator.calls.isNotEmpty())
        assertTrue(viewModel.uiState.value.isListening) // still listening, session not stopped by a partial
    }

    @Test
    fun `translated text appends across chunks instead of overwriting`() = runTest(dispatcher) {
        translator.resultFor = { phrase -> Result.success("[$phrase]") }
        viewModel.start()

        recognizer.emit("we need to move this consumer over to the new worker service", isFinal = false)
        dispatcher.scheduler.advanceUntilIdle()
        val afterFirstChunk = viewModel.uiState.value.translatedText

        recognizer.emit(
            "we need to move this consumer over to the new worker service because it is overloaded now",
            isFinal = true,
        )
        dispatcher.scheduler.advanceUntilIdle()
        val afterSecondChunk = viewModel.uiState.value.translatedText

        assertTrue(afterFirstChunk.isNotBlank())
        assertTrue(afterSecondChunk.startsWith(afterFirstChunk))
        assertTrue(afterSecondChunk.length > afterFirstChunk.length)
    }

    @Test
    fun `general context is merged into what is sent to the translator`() = runTest(dispatcher) {
        configRepository.setGeneralContext("We're discussing a billing support case")
        viewModel.start()
        recognizer.emit("hello world", isFinal = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(translator.contexts.last().contains("We're discussing a billing support case"))
    }

    @Test
    fun `conversation context overrides general context when both are set`() = runTest(dispatcher) {
        configRepository.setGeneralContext("General topic ABC")
        configRepository.setConversationContext("Specific topic XYZ")
        viewModel.start()
        recognizer.emit("hello world", isFinal = true)
        dispatcher.scheduler.advanceUntilIdle()

        val ctx = translator.contexts.last()
        assertTrue(ctx.contains("Specific topic XYZ"))
        assertFalse(ctx.contains("General topic ABC"))
    }

    @Test
    fun `blank conversation context falls back to general context`() = runTest(dispatcher) {
        configRepository.setGeneralContext("General topic ABC")
        configRepository.setConversationContext("")
        viewModel.start()
        recognizer.emit("hello world", isFinal = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(translator.contexts.last().contains("General topic ABC"))
    }

    @Test
    fun `translator failure sets an error but keeps the session listening`() = runTest(dispatcher) {
        translator.result = Result.failure(RuntimeException("network down"))
        viewModel.start()
        recognizer.emit("hello world", isFinal = true)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("network down", viewModel.uiState.value.errorMessage)
        assertTrue(viewModel.uiState.value.isListening)
    }
}
