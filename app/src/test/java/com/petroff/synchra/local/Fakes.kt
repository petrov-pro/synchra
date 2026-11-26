package com.petroff.synchra.local

import com.petroff.synchra.asr.AsrSegment
import com.petroff.synchra.asr.AudioSource
import com.petroff.synchra.asr.SpeechRecognizer
import com.petroff.synchra.translate.Translator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

class FakeSpeechRecognizer(val audio: AudioSource) : SpeechRecognizer {
    var started = false
    var stopped = false
    var language: String? = null
    private var onSegment: ((AsrSegment) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null

    override fun start(onSegment: (AsrSegment) -> Unit, onError: (Throwable) -> Unit) {
        started = true
        this.onSegment = onSegment
        this.onError = onError
    }

    override fun stop() {
        stopped = true
    }

    fun emit(text: String, isFinal: Boolean = false) = onSegment!!(AsrSegment(text, isFinal))
    fun fail(e: Throwable) = onError!!(e)
}

class FakeTranslator : Translator {
    var prepareCalls = 0
    var closed = false
    /** Completes prepare(); left null it succeeds at once. */
    var prepareGate: CompletableDeferred<Result<Unit>>? = null
    /** Chunk number (1-based) -> virtual delay, to shuffle completion order. */
    var delays: (Int) -> Long = { 0 }
    private var calls = 0

    override suspend fun prepare(source: String, target: String): Result<Unit> {
        prepareCalls++
        return prepareGate?.await() ?: Result.success(Unit)
    }

    override suspend fun translate(text: String): Result<String> {
        delay(delays(++calls))
        return Result.success(text.uppercase())
    }

    override fun close() {
        closed = true
    }
}

/** Audio that the test pushes chunks through by hand. */
class FakeAudioSource : AudioSource {
    private var onSamples: ((FloatArray) -> Unit)? = null
    var stopped = false
    override fun start(onSamples: (FloatArray) -> Unit) {
        this.onSamples = onSamples
    }
    override fun stop() {
        stopped = true
    }
    fun push(chunk: FloatArray) = onSamples!!(chunk)
}
