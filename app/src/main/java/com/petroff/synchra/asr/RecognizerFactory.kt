package com.petroff.synchra.asr

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

/** NVIDIA Nemotron 3.5 ASR streaming 0.6B (multilingual, int8, 560 ms chunks). */
fun createNemotronRecognizer(paths: ModelPaths): OnlineRecognizer = OnlineRecognizer(
    config = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = MicAudioSource.SAMPLE_RATE, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = paths.encoder,
                decoder = paths.decoder,
                joiner = paths.joiner,
            ),
            tokens = paths.tokens,
            modelType = NEMOTRON_MODEL_TYPE,
            numThreads = 4,
        ),
        // The library defaults (2.4 s / 1.4 s of trailing silence) almost never end an
        // utterance in conversation, so translation would wait for long pauses.
        endpointConfig = EndpointConfig(
            rule1 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 1.2f, minUtteranceLength = 0.0f),
            rule2 = EndpointRule(mustContainNonSilence = true, minTrailingSilence = 0.6f, minUtteranceLength = 0.0f),
        ),
    ),
)

/** A decoder for [language] ("en", "ru"…; empty = detect) over the Nemotron model. */
fun createNemotronDecoder(paths: ModelPaths, language: String): StreamingDecoder {
    val recognizer = createNemotronRecognizer(paths)
    val stream = recognizer.createStream()
    if (language.isNotBlank()) stream.setOption("language", language)
    return object : StreamingDecoder {
        override fun accept(samples: FloatArray) {
            stream.acceptWaveform(samples, MicAudioSource.SAMPLE_RATE)
            while (recognizer.isReady(stream)) recognizer.decode(stream)
        }

        override fun result(): Pair<String, Boolean> = recognizer.getResult(stream).text to recognizer.isEndpoint(stream)

        override fun reset() = recognizer.reset(stream)

        override fun release() {
            stream.release()
            recognizer.release()
        }
    }
}

internal const val NEMOTRON_MODEL_TYPE = "nemo_transducer"
