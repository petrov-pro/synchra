package com.petroff.transyncai.asr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/**
 * Upgrade path, which connectedAndroidTest never exercises on its own (it installs fresh and
 * uninstalls afterwards): a previous app version left the old zipformer model in the model
 * directory. Its tokens.txt has the same name as Nemotron's, and a mismatched vocabulary makes
 * sherpa-onnx call exit(-1) while building the recognizer — the whole app process dies, so this
 * test fails by crashing the instrumentation if the stale files are kept.
 */
@RunWith(AndroidJUnit4::class)
class ModelUpgradeInstrumentedTest {

    @Test
    fun recognizerStartsAfterUpgradingFromTheOldZipformerModel() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val modelDir = File(context.filesDir, "model")
        modelDir.deleteRecursively()
        modelDir.mkdirs()
        // What the zipformer-era app left behind (no model-id marker existed back then).
        File(modelDir, "tokens.txt").writeText("<blk> 0\n<sos/eos> 1\n<unk> 2\n")
        File(modelDir, "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx").writeText("old encoder")

        val paths = ModelFileProvider(OkHttpFileDownloader(OkHttpClient()), modelDir).ensureModelFiles()

        val recognizer = OnlineRecognizer(
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
            )
        )
        recognizer.release()

        assertTrue(File(paths.tokens).readText().lines().none { it == "<blk> 0" })
        assertTrue(modelDir.listFiles()!!.none { it.name.startsWith("encoder-epoch") })
    }
}
