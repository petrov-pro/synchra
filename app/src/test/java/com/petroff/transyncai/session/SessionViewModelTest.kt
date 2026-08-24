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
        )
        configRepository.setApiKey("sk-test")
        viewModel = SessionViewModel(recognizer, translator, configRepository)
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
        )
        val freshViewModel = SessionViewModel(FakeSpeechRecognizer(), FakeTranslator(), noKeyRepo)

        assertFalse(freshViewModel.uiState.value.canStart)
    }

    @Test
    fun `stop stops the recognizer and updates state`() {
        viewModel.start()
        viewModel.stop()

        assertTrue(recognizer.stopped)
        assertFalse(viewModel.uiState.value.isListening)
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
