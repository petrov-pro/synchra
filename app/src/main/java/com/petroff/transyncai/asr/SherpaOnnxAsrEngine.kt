package com.petroff.transyncai.asr

import com.k2fsa.sherpa.onnx.OnlineRecognizer

class SherpaOnnxAsrEngine(
    private val audioSource: AudioSource,
    private val recognizerFactory: () -> OnlineRecognizer,
) : SpeechRecognizer {

    override fun start(onSegment: (AsrSegment) -> Unit) {
        val recognizer = recognizerFactory()
        val stream = recognizer.createStream()

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

    override fun stop() {
        audioSource.stop()
    }
}
