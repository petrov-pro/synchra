package com.petroff.synchra.asr

import kotlin.concurrent.thread

/** One recognition stream over a loaded model; native memory until [release]. */
interface StreamingDecoder {
    fun accept(samples: FloatArray)
    /** The current utterance and whether it just ended. */
    fun result(): Pair<String, Boolean>
    fun reset()
    fun release()
}

/** Streaming recognition on the phone: microphone → [StreamingDecoder] (sherpa-onnx). */
class SherpaOnnxAsrEngine(
    private val audioSource: AudioSource,
    // Loads the model (seconds, ~650 MB): runs on the engine's own thread.
    private val decoderFactory: () -> StreamingDecoder,
) : SpeechRecognizer {

    private val lock = Any()
    private var stopRequested = false // guarded by lock
    private var decoder: StreamingDecoder? = null // guarded by lock; null once released

    override fun start(onSegment: (AsrSegment) -> Unit, onError: (Throwable) -> Unit) {
        synchronized(lock) { stopRequested = false }
        thread(name = "SherpaOnnxAsrEngine") {
            try {
                val loaded = decoderFactory()
                // The check and the microphone start happen under the lock stop() takes, so a
                // Stop arriving meanwhile either prevents the start or sees the running mic.
                synchronized(lock) {
                    if (stopRequested) {
                        loaded.release()
                        return@thread
                    }
                    decoder = loaded
                    audioSource.start { samples ->
                        val segment = synchronized(lock) {
                            // Released by stop(): never touch freed native memory.
                            val d = decoder ?: return@start
                            d.accept(samples)
                            val (text, isEndpoint) = d.result()
                            if (isEndpoint) d.reset()
                            if (text.isNotBlank() || isEndpoint) AsrSegment(text, isEndpoint) else null
                        }
                        segment?.let(onSegment)
                    }
                }
            } catch (e: Throwable) {
                onError(e)
            }
        }
    }

    override fun stop() {
        synchronized(lock) { stopRequested = true }
        // Waits for the microphone thread, then frees the model's native memory, which the
        // garbage collector doesn't know about.
        audioSource.stop()
        synchronized(lock) {
            decoder?.release()
            decoder = null
        }
    }
}
