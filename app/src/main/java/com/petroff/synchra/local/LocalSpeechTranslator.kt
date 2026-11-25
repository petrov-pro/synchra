package com.petroff.synchra.local

import com.petroff.synchra.asr.AsrSegment
import com.petroff.synchra.asr.AudioSource
import com.petroff.synchra.asr.SpeechRecognizer
import com.petroff.synchra.session.TranslationChunker
import com.petroff.synchra.soniox.LiveRequest
import com.petroff.synchra.soniox.LiveTranscript
import com.petroff.synchra.soniox.SilenceGate
import com.petroff.synchra.soniox.SpeechTranslator
import com.petroff.synchra.translate.Translator
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Free mode: speech is recognized and translated on the phone, nothing is sent anywhere.
 * Microphone → recognizer → stable pieces of text (TranslationChunker) → translator, one piece
 * at a time so the translation keeps the spoken order.
 */
class LocalSpeechTranslator(
    // A fresh microphone per session, as for Soniox.
    private val audioSourceFactory: () -> AudioSource,
    private val recognizerFactory: (language: String, audio: AudioSource) -> SpeechRecognizer,
    private val translatorFactory: () -> Translator,
    private val scope: CoroutineScope,
    // True while the translation models are being made ready, before listening starts.
    private val onPreparing: (Boolean) -> Unit = {},
    // Here only for the auto-stop after a long silence: nothing is billed in Free mode.
    private val silenceGateFactory: () -> SilenceGate = { SilenceGate() },
) : SpeechTranslator {

    @Volatile private var current: Session? = null

    override fun start(
        request: LiveRequest,
        onUpdate: (LiveTranscript) -> Unit,
        onError: (String) -> Unit,
        onFinished: (LiveTranscript) -> Unit,
        onAutoStopped: () -> Unit,
        onSendingChanged: (Boolean) -> Unit,
    ) {
        val session = Session(request, onUpdate, onError, onFinished, onAutoStopped)
        current = session
        session.begin()
    }

    override fun stop() {
        current?.end()
    }

    /** One start()…onFinished run; a later session never touches an earlier one's state. */
    private inner class Session(
        private val request: LiveRequest,
        private val onUpdate: (LiveTranscript) -> Unit,
        private val onError: (String) -> Unit,
        private val onFinished: (LiveTranscript) -> Unit,
        private val onAutoStopped: () -> Unit,
    ) {
        private val translate = request.targetLang != null
        private val translator: Translator? = if (translate) translatorFactory() else null
        private val chunker = TranslationChunker()
        private val chunks = Channel<String>(Channel.UNLIMITED)
        private val lock = Any()
        private val ending = AtomicBoolean(false)
        private val finished = AtomicBoolean(false)
        private val autoStopped = AtomicBoolean(false)
        @Volatile private var recognizer: SpeechRecognizer? = null

        // Guarded by lock.
        private val finals = mutableListOf<String>()
        private var partial = ""
        private val translations = mutableListOf<String>()

        fun begin() {
            scope.launch {
                if (translator != null) {
                    onPreparing(true)
                    val ready = translator.prepare(request.sourceLang, request.targetLang!!)
                    onPreparing(false)
                    ready.onFailure { e ->
                        onError("Couldn't download the translation model: ${e.message ?: e.javaClass.simpleName}")
                        end()
                        return@launch
                    }
                }
                // Stop pressed while the models were getting ready: the microphone stays closed.
                if (ending.get()) return@launch
                val audio = GatedAudio(audioSourceFactory())
                val asr = recognizerFactory(request.sourceLang, audio)
                // Checked and published together with end(), so a Stop in between can't miss
                // the recognizer and leave the microphone on.
                synchronized(lock) {
                    if (ending.get()) return@launch
                    recognizer = asr
                }
                asr.start(onSegment = ::onSegment, onError = { e ->
                    onError("Speech recognition failed: ${e.message ?: e.javaClass.simpleName}")
                    end()
                })
            }
            if (translator != null) scope.launch { translateInOrder(translator) }
        }

        private fun onSegment(segment: AsrSegment) {
            val chunk: String?
            synchronized(lock) {
                if (ending.get()) return
                if (segment.isFinal) {
                    if (segment.text.isNotBlank()) finals += segment.text.trim()
                    partial = ""
                    chunk = chunker.onFinal(segment.text)
                } else {
                    partial = segment.text.trim()
                    chunk = chunker.onPartialUpdate(segment.text)
                }
            }
            if (translate && !chunk.isNullOrBlank()) chunks.trySend(chunk)
            onUpdate(snapshot())
        }

        private suspend fun translateInOrder(translator: Translator) {
            for (chunk in chunks) {
                translator.translate(chunk)
                    .onSuccess { text -> synchronized(lock) { translations += text.trim() } ; onUpdate(snapshot()) }
                    .onFailure { e -> onError("Translation failed: ${e.message ?: e.javaClass.simpleName}") }
            }
            finish()
        }

        /** Stops listening; onFinished follows once the last pieces are translated. */
        fun end() {
            val rest: String?
            val running: SpeechRecognizer?
            synchronized(lock) {
                if (!ending.compareAndSet(false, true)) return
                running = recognizer
                // The phrase in progress counts: the recognizer won't finish it any more.
                if (partial.isNotBlank()) finals += partial
                rest = chunker.onFinal(partial)
                partial = ""
            }
            // Outside the lock: stopping waits for the microphone thread, which may be
            // waiting for the lock in onSegment.
            running?.stop()
            if (translate) {
                if (!rest.isNullOrBlank()) chunks.trySend(rest)
                chunks.close() // translateInOrder drains what's queued, then finishes
            } else {
                finish()
            }
        }

        private fun finish() {
            if (!finished.compareAndSet(false, true)) return
            translator?.close()
            onFinished(snapshot())
        }

        private fun snapshot(): LiveTranscript = synchronized(lock) {
            LiveTranscript(
                source = (finals + partial).filter { it.isNotBlank() }.joinToString(" "),
                translation = translations.filter { it.isNotBlank() }.joinToString(" "),
            )
        }

        /** The microphone as the recognizer sees it, watched for a long silence. */
        private inner class GatedAudio(private val inner: AudioSource) : AudioSource {
            private val gate = silenceGateFactory()

            override fun start(onSamples: (FloatArray) -> Unit) = inner.start { samples ->
                onSamples(samples)
                if (gate.process(samples).autoStop && autoStopped.compareAndSet(false, true)) {
                    onAutoStopped()
                    end()
                }
            }

            override fun stop() = inner.stop()
        }
    }
}
