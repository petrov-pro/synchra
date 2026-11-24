package com.petroff.transyncai

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.petroff.transyncai.asr.ModelFileProvider
import com.petroff.transyncai.asr.OkHttpFileDownloader
import com.petroff.transyncai.asr.SherpaOnnxAsrEngine
import com.petroff.transyncai.asr.WavFileAudioSource
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.config.DefaultConfigRepository
import com.petroff.transyncai.config.FakeSettingsStore
import com.petroff.transyncai.history.RoomHistoryRepository
import com.petroff.transyncai.history.TransyncDatabase
import com.petroff.transyncai.session.SessionViewModel
import com.petroff.transyncai.translate.ClaudeTranslationClient
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import android.Manifest
import androidx.room.Room
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E2ETranslationFlowTest {

    private lateinit var server: MockWebServer
    private lateinit var database: TransyncDatabase

    // Same requirement as MainActivitySmokeTest (Task 2): MainActivity is permission-gated
    // (Task 11), must be granted before composeRule launches the Activity.
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        // In-memory so the stop()-time history save never touches the app's real database.
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            TransyncDatabase::class.java,
        ).build()
        server = MockWebServer()
        server.start()
        // Soft-commit chunking (Task: real-time translation buffer) means this WAV's ~18-word
        // transcript can legitimately produce multiple translate() calls, not just one — a
        // one-shot enqueue() would leave later requests hanging against an empty queue. A
        // dispatcher answers any number of requests with the same canned response.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"id":"msg_e2e","type":"message","role":"assistant","model":"claude-haiku-5-5",
                       "content":[{"type":"text","text":"После раннего наступления темноты"}],
                       "stop_reason":"end_turn","stop_sequence":null,
                       "usage":{"input_tokens":40,"output_tokens":8}}"""
                )
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        database.close()
    }

    @Test
    fun fullPipelineProducesTranslatedTextFromBundledAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsStore = FakeSettingsStore()
        val configRepository = DefaultConfigRepository(
            store = settingsStore,
            defaultBufferSize = 6,
            defaultApiBaseUrl = server.url("/").toString().trimEnd('/'),
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
            defaultChunkSizeWords = 8,
        )
        configRepository.setApiKey("sk-e2e-test")

        val translator = ClaudeTranslationClient(
            baseUrl = configRepository.getConfig().apiBaseUrl,
            apiKeyProvider = { configRepository.getConfig().apiKey },
        )

        val modelDir = File(context.filesDir, "model")
        val paths = ModelFileProvider(OkHttpFileDownloader(OkHttpClient()), modelDir).ensureModelFiles()
        val recognizerFactory = {
            OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = paths.encoder, decoder = paths.decoder, joiner = paths.joiner,
                        ),
                        tokens = paths.tokens,
                        modelType = "nemo_transducer",
                        numThreads = 2,
                    ),
                    // Same test-only endpoint tuning as Task 7's instrumented test (including
                    // the mustContainNonSilence=true fix on rule1 that prevents a false-positive
                    // endpoint on the WAV's leading silence, needed once start()/stop() became
                    // genuinely asynchronous). See Task 7 for the root-cause writeup.
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 0.3f, minUtteranceLength = 0.0f),
                        rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 0.3f, minUtteranceLength = 0.0f),
                    ),
                )
            )
        }
        val audioSource = WavFileAudioSource(
            InstrumentationRegistry.getInstrumentation().context.assets,
            "test_wavs/0.wav",
        )
        val engine = SherpaOnnxAsrEngine(audioSource, recognizerFactory)
        val sessionViewModel = SessionViewModel(
            engine,
            translator,
            configRepository,
            RoomHistoryRepository(database.conversationRecordDao()),
        )

        // MainActivity.onCreate() (Task 11) already calls setContent { TransyncAiApp(container) }
        // with its own production AppContainer as soon as createAndroidComposeRule launches it,
        // so calling composeRule.setContent(...) again here throws IllegalStateException
        // ("has already set content"). The Activity's own composition is sufficient to host the
        // Compose test environment that composeRule.waitUntil below depends on; this test's
        // assertions read sessionViewModel.uiState directly rather than the Activity's Compose
        // tree, so no separate setContent call is needed.

        // Drive the already-constructed pipeline directly rather than via AppContainer's
        // real mic-backed recognizer, since this test injects the WAV-backed engine above.
        sessionViewModel.start()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            sessionViewModel.uiState.value.translatedText.isNotBlank()
        }

        assertTrue(sessionViewModel.uiState.value.translatedText.contains("наступления"))
        sessionViewModel.stop()
    }
}
