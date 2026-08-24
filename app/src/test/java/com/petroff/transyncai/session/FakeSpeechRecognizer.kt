package com.petroff.transyncai.session

import com.petroff.transyncai.asr.AsrSegment
import com.petroff.transyncai.asr.SpeechRecognizer

class FakeSpeechRecognizer : SpeechRecognizer {
    var started = false
    var stopped = false
    private var listener: ((AsrSegment) -> Unit)? = null

    override fun start(onSegment: (AsrSegment) -> Unit) {
        started = true
        listener = onSegment
    }

    override fun stop() {
        stopped = true
    }

    fun emit(text: String, isFinal: Boolean) {
        listener?.invoke(AsrSegment(text, isFinal))
    }
}
