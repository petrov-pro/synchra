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
                        modelType = "nemo_transducer",
                        numThreads = 2,
                    ),
                    // Test-only: the bundled 0.wav has only ~0.4s of trailing silence,
                    // well under the library's default endpoint thresholds (1.4s/2.4s),
                    // so isEndpoint() never fires with defaults and the test hangs on an
                    // empty result. Lowering the thresholds here is scoped to this test's
                    // config only — SherpaOnnxAsrEngine/MicAudioSource and their default
                    // (production, real-mic) endpoint behavior are untouched.
                    // mustContainNonSilence = true on BOTH rules (not just rule2): with
                    // start()/stop() now genuinely asynchronous (see SherpaOnnxAsrEngine's
                    // background-thread fix), a rule that can fire on trailing-silence
                    // duration alone — without requiring any speech to have been detected
                    // first — false-positives on the WAV's ~0.3-0.5s of LEADING silence,
                    // producing a premature empty final segment before "YELLOW LAMPS"/
                    // "NIGHTFALL" is ever decoded. Requiring non-silence first prevents that.
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

        val finalTexts = mutableListOf<String>()
        engine.start { segment ->
            if (segment.isFinal) {
                finalTexts.add(segment.text)
            }
        }

        // Do NOT stop() after the first final segment: the recognizer can legitimately
        // emit more than one final segment for this clip (e.g. a natural mid-sentence
        // pause between "EARLY" and "NIGHTFALL" also satisfies the tuned endpoint rule
        // above) — stopping early would cut off the rest of the WAV before the target
        // phrase is decoded. 0.wav is ~6.6s; WavFileAudioSource's feed loop terminates
        // naturally on its own once the file is exhausted, independent of stop() — wait
        // generously for that instead of racing an early stop() against in-progress
        // decoding. Accumulating every final segment and asserting only after the full
        // clip has played is robust to exactly how many times/where endpoints fire
        // internally.
        Thread.sleep(20_000)
        engine.stop()

        val recognized = finalTexts.joinToString(" ").uppercase()
        assertTrue(
            "Expected recognized text to contain a known phrase from 0.wav, got: $recognized",
            recognized.contains("YELLOW LAMPS") || recognized.contains("NIGHTFALL"),
        )
    }
}
