package com.petroff.transyncai.asr

interface AudioSource {
    /** sampleRate is always 16000 Hz, mono, matching the model's expected input. */
    fun start(onSamples: (FloatArray) -> Unit)
    fun stop()
}
