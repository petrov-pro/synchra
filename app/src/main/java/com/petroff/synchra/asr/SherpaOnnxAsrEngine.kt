package com.petroff.synchra.asr

import com.k2fsa.sherpa.onnx.OnlineRecognizer
import kotlin.concurrent.thread

/**
 * Streaming recognition on the phone with sherpa-onnx. [language] is the spoken language's
 * code (e.g. "en"); empty lets the model detect it.
 */
class SherpaOnnxAsrEngine(
    private val audioSource: AudioSource,
    private val recognizerFactory: () -> OnlineRecognizer,
    private val language: String,
) : SpeechRecognizer {

    @Volatile
    private var stopRequested = false

    override fun start(onSegment: (AsrSegment) -> Unit, onError: (Throwable) -> Unit) {
        stopRequested = false
        // Loading the ~650 MB model takes seconds: never on the caller's (main) thread.
        thread(name = "SherpaOnnxAsrEngine") {
            try {
                val recognizer = recognizerFactory()
                val stream = recognizer.createStream()
                if (language.isNotBlank()) stream.setOption("language", language)
                // Stop pressed while the model was loading: the microphone is never opened.
                if (stopRequested) {
                    stream.release()
                    recognizer.release()
                    return@thread
                }
                audioSource.start { samples ->
                    stream.acceptWaveform(samples, MicAudioSource.SAMPLE_RATE)
                    while (recognizer.isReady(stream)) recognizer.decode(stream)
                    val isEndpoint = recognizer.isEndpoint(stream)
                    val text = recognizer.getResult(stream).text
                    if (text.isNotBlank() || isEndpoint) onSegment(AsrSegment(text, isEndpoint))
                    if (isEndpoint) recognizer.reset(stream)
                }
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }

    override fun stop() {
        stopRequested = true
        audioSource.stop()
    }
}
