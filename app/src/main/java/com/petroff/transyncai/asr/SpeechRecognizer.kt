package com.petroff.transyncai.asr

data class AsrSegment(val text: String, val isFinal: Boolean)

interface SpeechRecognizer {
    fun start(onSegment: (AsrSegment) -> Unit)
    fun stop()
}
