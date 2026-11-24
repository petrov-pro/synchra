package com.petroff.transyncai

import android.content.Context
import androidx.room.Room
import com.petroff.transyncai.asr.MicAudioSource
import com.petroff.transyncai.asr.ModelFileProvider
import com.petroff.transyncai.asr.OkHttpFileDownloader
import com.petroff.transyncai.asr.SherpaOnnxAsrEngine
import com.petroff.transyncai.asr.SpeechRecognizer
import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.config.DefaultConfigRepository
import com.petroff.transyncai.config.EncryptedPrefsSettingsStore
import com.petroff.transyncai.history.HistoryRepository
import com.petroff.transyncai.history.RoomHistoryRepository
import com.petroff.transyncai.history.TransyncDatabase
import com.petroff.transyncai.soniox.SonioxSpeechTranslator
import com.petroff.transyncai.soniox.SpeechTranslator
import com.petroff.transyncai.translate.ClaudeTranslationClient
import com.petroff.transyncai.translate.Translator
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import okhttp3.OkHttpClient

class AppContainer(private val context: Context) {

    private val httpClient = OkHttpClient()

    private val database: TransyncDatabase = Room.databaseBuilder(
        context,
        TransyncDatabase::class.java,
        "transyncai.db",
    ).build()

    val historyRepository: HistoryRepository = RoomHistoryRepository(database.conversationRecordDao())

    val configRepository: ConfigRepository = DefaultConfigRepository(
        store = EncryptedPrefsSettingsStore(context),
        defaultBufferSize = BuildConfig.DEFAULT_BUFFER_SIZE,
        defaultApiBaseUrl = BuildConfig.DEFAULT_API_BASE_URL,
        defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
        defaultChunkSizeWords = BuildConfig.DEFAULT_CHUNK_SIZE_WORDS,
    )

    val translator: Translator = ClaudeTranslationClient(
        baseUrl = configRepository.getConfig().apiBaseUrl,
        apiKeyProvider = { configRepository.getConfig().apiKey },
    )

    fun buildSpeechTranslator(): SpeechTranslator = SonioxSpeechTranslator(
        audioSource = MicAudioSource(),
        httpClient = httpClient,
        apiKeyProvider = { configRepository.getConfig().sonioxApiKey },
    )

    fun buildSpeechRecognizer(): SpeechRecognizer {
        val modelDir = File(context.filesDir, "model")
        // ensureModelFiles() does blocking network I/O — it must NOT run here.
        // buildSpeechRecognizer() is called from SessionViewModelFactory.create(),
        // which Compose's viewModel() invokes synchronously during composition
        // (main thread). Deferring it into the lazy recognizerFactory below means
        // it only runs inside SherpaOnnxAsrEngine's background init thread (Task 7),
        // when the user actually taps Start — never on the main thread.
        val recognizerFactory = {
            val paths = ModelFileProvider(OkHttpFileDownloader(httpClient), modelDir).ensureModelFiles()
            OnlineRecognizer(
                config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = paths.encoder,
                            decoder = paths.decoder,
                            joiner = paths.joiner,
                        ),
                        tokens = paths.tokens,
                        modelType = "nemo_transducer",
                        numThreads = 4,
                    ),
                    // Without this, the library defaults (rule1: 2.4s, rule2: 1.4s trailing
                    // silence) essentially never fire for normal conversational speech —
                    // verified empirically: isEndpoint() stayed false for 25+ seconds of
                    // continuous speech, meaning SessionViewModel never saw a final segment
                    // and translation was never triggered at all. 0.6s/1.2s trailing silence
                    // (both requiring real speech first, avoiding the leading-silence
                    // false-positive) balances responsiveness against cutting off mid-thought.
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0.0f),
                        rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 0.6f, minUtteranceLength = 0.0f),
                    ),
                )
            )
        }
        return SherpaOnnxAsrEngine(MicAudioSource(), recognizerFactory)
    }
}
