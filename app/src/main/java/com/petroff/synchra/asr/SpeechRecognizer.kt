package com.petroff.synchra.asr

/** The current utterance so far; isFinal once the recognizer saw its end (a pause). */
data class AsrSegment(val text: String, val isFinal: Boolean)

interface SpeechRecognizer {
    /** [onError]: the recognizer could not be created or failed; no more segments follow. */
    fun start(onSegment: (AsrSegment) -> Unit, onError: (Throwable) -> Unit)
    fun stop()
}
