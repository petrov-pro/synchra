package com.petroff.transyncai

import android.content.Context
import com.petroff.transyncai.asr.MicAudioSource
import com.petroff.transyncai.asr.ModelFileProvider
import com.petroff.transyncai.asr.OkHttpFileDownloader
import com.petroff.transyncai.asr.SherpaOnnxAsrEngine
import com.petroff.transyncai.asr.SpeechRecognizer
import com.petroff.transyncai.config.ConfigRepository
import com.petroff.transyncai.config.DEFAULT_LANGUAGE_PAIRS
import com.petroff.transyncai.config.DefaultConfigRepository
import com.petroff.transyncai.config.EncryptedPrefsSettingsStore
import com.petroff.transyncai.translate.KimiTranslationClient
import com.petroff.transyncai.translate.Translator
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import okhttp3.OkHttpClient

class AppContainer(private val context: Context) {

    private val httpClient = OkHttpClient()

    val configRepository: ConfigRepository = DefaultConfigRepository(
        store = EncryptedPrefsSettingsStore(context),
        defaultBufferSize = BuildConfig.DEFAULT_BUFFER_SIZE,
        defaultApiBaseUrl = BuildConfig.DEFAULT_API_BASE_URL,
        defaultLanguagePairs = DEFAULT_LANGUAGE_PAIRS,
    )

    val translator: Translator = KimiTranslationClient(
        httpClient = httpClient,
        baseUrl = configRepository.getConfig().apiBaseUrl,
        apiKeyProvider = { configRepository.getConfig().apiKey },
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
                        modelType = "zipformer2",
                        numThreads = 2,
                    ),
                )
            )
        }
        return SherpaOnnxAsrEngine(MicAudioSource(), recognizerFactory)
    }
}
