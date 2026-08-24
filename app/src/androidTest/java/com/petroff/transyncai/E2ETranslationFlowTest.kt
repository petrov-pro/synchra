package com.petroff.transyncai

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.petroff.transyncai.asr.ModelFileProvider
import com.petroff.transyncai.asr.OkHttpFileDownloader
import com.petroff.transyncai.asr.SherpaOnnxAsrEngine
import com.petroff.transyncai.asr.WavFileAudioSource
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.config.DefaultConfigRepository
import com.petroff.transyncai.config.FakeSettingsStore
import com.petroff.transyncai.session.SessionViewModel
import com.petroff.transyncai.translate.KimiTranslationClient
import com.petroff.transyncai.ui.TransyncAiApp
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import android.Manifest
import java.io.File
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import androidx.test.rule.GrantPermissionRule
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E2ETranslationFlowTest {

    private lateinit var server: MockWebServer

    // Same requirement as MainActivitySmokeTest (Task 2): MainActivity is permission-gated
    // (Task 11), must be granted before composeRule launches the Activity.
    @get:Rule(order = 0)
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"role":"assistant","content":"После раннего наступления темноты"}}]}"""
            )
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun fullPipelineProducesTranslatedTextFromBundledAudio() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsStore = FakeSettingsStore()
        val configRepository = DefaultConfigRepository(
            store = settingsStore,
            defaultBufferSize = 6,
            defaultApiBaseUrl = server.url("/v1").toString().trimEnd('/'),
            defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
        )
        configRepository.setApiKey("sk-e2e-test")

        val translator = KimiTranslationClient(
            httpClient = OkHttpClient(),
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
                        modelType = "zipformer2",
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
        val sessionViewModel = SessionViewModel(engine, translator, configRepository)

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

        assert(sessionViewModel.uiState.value.translatedText.contains("наступления"))
        sessionViewModel.stop()
    }
}
