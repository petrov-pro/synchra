package com.petroff.transyncai.asr

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SherpaOnnxAsrEngineInstrumentedTest {

    @Test
    fun recognizesKnownPhraseFromBundledWav() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(context.filesDir, "model")
        val paths = ModelFileProvider(OkHttpFileDownloader(OkHttpClient()), modelDir).ensureModelFiles()

        val recognizerFactory = {
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
                    // The bundled test WAV (0.wav) has only ~0.4s of trailing near-silence,
                    // shorter than the library's default endpoint rules (1.4s / 2.4s), so the
                    // default config never reports isEndpoint for this short clip. Lower the
                    // trailing-silence threshold for this test only; production behavior
                    // (SherpaOnnxAsrEngine, MicAudioSource) is unchanged.
                    endpointConfig = EndpointConfig(
                        rule1 = EndpointRule(false, 0.3f, 0.0f),
                        rule2 = EndpointRule(true, 0.3f, 0.0f),
                    ),
                )
            )
        }

        val audioSource = WavFileAudioSource(
            InstrumentationRegistry.getInstrumentation().context.assets,
            "test_wavs/0.wav",
        )
        val engine = SherpaOnnxAsrEngine(audioSource, recognizerFactory)

        val finalTexts = mutableListOf<String>()
        val latch = CountDownLatch(1)
        engine.start { segment ->
            if (segment.isFinal) {
                finalTexts.add(segment.text)
                latch.countDown()
            }
        }

        latch.await(30, TimeUnit.SECONDS)
        engine.stop()

        val recognized = finalTexts.joinToString(" ").uppercase()
        assertTrue(
            "Expected recognized text to contain a known phrase from 0.wav, got: $recognized",
            recognized.contains("YELLOW LAMPS") || recognized.contains("NIGHTFALL"),
        )
    }
}
