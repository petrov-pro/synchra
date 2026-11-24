package com.petroff.transyncai.session

import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.config.DefaultConfigRepository
import com.petroff.transyncai.config.FakeSettingsStore
import com.petroff.transyncai.soniox.LiveRequest
import com.petroff.transyncai.soniox.LiveTranscript
import com.petroff.transyncai.soniox.SpeechTranslator
import kotlinx.coroutines.Dispatchers
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
    var request: LiveRequest? = null
    var stopped = false
    private var onUpdate: ((LiveTranscript) -> Unit)? = null
    private var onError: ((String) -> Unit)? = null
    private var onFinished: ((LiveTranscript) -> Unit)? = null

    override fun start(
        request: LiveRequest,
        onUpdate: (LiveTranscript) -> Unit,
        onError: (String) -> Unit,
        onFinished: (LiveTranscript) -> Unit,
    ) {
        this.request = request
        this.onUpdate = onUpdate
        this.onError = onError
        this.onFinished = onFinished
    }

    override fun stop() {
        stopped = true
    }

    fun emit(source: String, translation: String) = onUpdate!!(LiveTranscript(source, translation))
    fun fail(message: String) = onError!!(message)
    fun finish(source: String, translation: String) = onFinished!!(LiveTranscript(source, translation))
}

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private lateinit var speechTranslator: FakeSpeechTranslator
    private lateinit var configRepository: ConfigRepository
    private lateinit var historyRepository: FakeHistoryRepository
    private lateinit var viewModel: SessionViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        speechTranslator = FakeSpeechTranslator()
        configRepository = DefaultConfigRepository(
            store = FakeSettingsStore(),
            defaultApiBaseUrl = "https://api.anthropic.com",
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
        )
        configRepository.setSonioxApiKey("snx-test")
        historyRepository = FakeHistoryRepository()
        viewModel = SessionViewModel(speechTranslator, configRepository, historyRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `start streams through Soniox with the language pair and context`() {
        configRepository.setGeneralContext("Cardiology lecture")

        viewModel.start()

        assertEquals(LiveRequest(sourceLang = "en", targetLang = "ru", context = "Cardiology lecture"), speechTranslator.request)
        assertTrue(viewModel.uiState.value.isListening)
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
}
