package com.petroff.transyncai.asr

import com.k2fsa.sherpa.onnx.OnlineRecognizer
import kotlin.concurrent.thread

class SherpaOnnxAsrEngine(
    private val audioSource: AudioSource,
    private val recognizerFactory: () -> OnlineRecognizer,
) : SpeechRecognizer {

    @Volatile
    private var stopRequested = false

    override fun start(onSegment: (AsrSegment) -> Unit) {
        stopRequested = false
        // recognizerFactory() may trigger a blocking model-file download (see
        // ModelFileProvider, Task 6) — start() must return immediately without
        // blocking the caller's thread, since callers include Compose's viewModel()
        // factory path (main thread) via AppContainer/SessionViewModelFactory (Task 11).
        thread(name = "SherpaOnnxAsrEngine-init") {
            val recognizer = recognizerFactory()
            val stream = recognizer.createStream()

            if (stopRequested) return@thread

            audioSource.start { samples ->
                stream.acceptWaveform(samples, MicAudioSource.SAMPLE_RATE)
                while (recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                }
                val isEndpoint = recognizer.isEndpoint(stream)
                val text = recognizer.getResult(stream).text
                if (text.isNotBlank() || isEndpoint) {
                    onSegment(AsrSegment(text, isEndpoint))
                }
                if (isEndpoint) {
                    recognizer.reset(stream)
                }
            }
        }
    }

    override fun stop() {
        stopRequested = true
        audioSource.stop()
    }
}
