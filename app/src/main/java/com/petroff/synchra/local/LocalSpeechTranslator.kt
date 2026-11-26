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
    // Watches for pauses (a new paragraph, the silence icon) and the auto-stop after a long
    // silence: nothing is billed in Free mode.
    private val silenceGateFactory: () -> SilenceGate = { SilenceGate() },
    // Voice prints for "Speaker N:" labels; null (no speaker model yet, or it failed to load)
    // leaves the text unlabeled.
    private val speakerEmbedderFactory: () -> SpeakerEmbedder? = { null },
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
        val session = Session(request, onUpdate, onError, onFinished, onAutoStopped, onSendingChanged)
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
        // Nothing is sent anywhere: true while someone speaks, false during a pause.
        private val onSpeakingChanged: (Boolean) -> Unit,
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

        // Speaker labels: pieces of speech go to voiceprints one by one, off the microphone thread.
        @Volatile private var embedder: SpeakerEmbedder? = null
        private val segmenter = SpeechSegmenter()
        private val pieces = Channel<SpeechSegmenter.Event.Ended>(Channel.UNLIMITED)
        private var speakerJob: kotlinx.coroutines.Job? = null
        // Guarded by lock: piece id -> speaker, and piece starts still waiting for the
        // recognizer to close the phrase in progress (so text and translation split alike).
        private val speakers = mutableMapOf<Int, Int>()
        private val pendingMarks = mutableListOf<String>()

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
                val prints = runCatching { speakerEmbedderFactory() }.getOrNull()
                synchronized(lock) {
                    // Stop pressed while the speaker model loaded.
                    if (ending.get()) {
                        prints?.release()
                        return@launch
                    }
                    embedder = prints
                    if (prints != null) speakerJob = scope.launch { labelPieces(prints) }
                }
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
            synchronized(lock) {
                if (ending.get()) return
                val chunk = if (segment.isFinal) {
                    if (segment.text.isNotBlank()) finals += segment.text.trim()
                    partial = ""
                    chunker.onFinal(segment.text)
                } else {
                    partial = segment.text.trim()
                    chunker.onPartialUpdate(segment.text)
                }
                // Queued under the lock, so a paragraph break can't overtake it.
                if (translate && !chunk.isNullOrBlank()) chunks.trySend(chunk)
                if (segment.isFinal) flushPendingMarks()
            }
            onUpdate(snapshot())
        }

        /** Someone speaks again after a pause: a new paragraph on both sides. */
        private fun paragraphBreak() {
            synchronized(lock) {
                // Mid-phrase (the recognizer hasn't closed it yet) or nothing said: no break.
                if (ending.get() || partial.isNotBlank() || finals.lastOrNull() in setOf(null, PARAGRAPH)) return
                finals += PARAGRAPH
                if (translate) chunks.trySend(PARAGRAPH)
            }
        }

        /** A new piece of speech starts: marked where the phrase in progress ends. */
        private fun pieceStarted(id: Int) {
            synchronized(lock) {
                if (ending.get()) return
                pendingMarks += speakerMark(id)
                if (partial.isBlank()) flushPendingMarks()
            }
        }

        // Called under lock.
        private fun flushPendingMarks() {
            for (mark in pendingMarks) {
                finals += mark
                if (translate) chunks.trySend(mark)
            }
            pendingMarks.clear()
        }

        private suspend fun labelPieces(prints: SpeakerEmbedder) {
            val clusters = SpeakerClusters()
            for (piece in pieces) {
                val speaker = runCatching { clusters.assign(piece.samples, prints::embed) }.getOrNull() ?: continue
                synchronized(lock) { speakers[piece.id] = speaker }
                onUpdate(snapshot())
            }
        }

        private suspend fun translateInOrder(translator: Translator) {
            for (chunk in chunks) {
                if (chunk == PARAGRAPH || isSpeakerMark(chunk)) {
                    synchronized(lock) { translations += chunk }
                    continue
                }
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
            // The microphone is quiet now: the last piece of speech gets its speaker too.
            if (embedder != null) segmenter.flush().forEach { if (it is SpeechSegmenter.Event.Ended) pieces.trySend(it) }
            pieces.close()
            synchronized(lock) {
                // Marks that came after the last text don't label anything.
                pendingMarks.clear()
            }
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
            val labeling = speakerJob
            if (labeling == null) {
                embedder?.release()
                onFinished(snapshot())
                return
            }
            // The last pieces' speakers are still being worked out: the saved text waits for them.
            scope.launch {
                labeling.join()
                embedder?.release()
                onFinished(snapshot())
            }
        }

        private fun snapshot(): LiveTranscript = synchronized(lock) {
            LiveTranscript(
                source = joinPieces(labelSpeakers(finals + partial, speakers)),
                translation = joinPieces(labelSpeakers(translations, speakers)),
            )
        }

        /** The microphone as the recognizer sees it, watched for a long silence. */
        private inner class GatedAudio(private val inner: AudioSource) : AudioSource {
            private val gate = silenceGateFactory()
            // Only the microphone thread touches it.
            private var speaking = false

            override fun start(onSamples: (FloatArray) -> Unit) = inner.start { samples ->
                onSamples(samples)
                val decision = gate.process(samples)
                val speakingNow = decision.send.isNotEmpty()
                if (speakingNow != speaking) {
                    speaking = speakingNow
                    if (speakingNow) paragraphBreak()
                    onSpeakingChanged(speakingNow)
                }
                if (embedder != null && !ending.get()) {
                    for (event in segmenter.process(samples, decision.speech)) when (event) {
                        is SpeechSegmenter.Event.Started -> pieceStarted(event.id)
                        is SpeechSegmenter.Event.Ended -> pieces.trySend(event)
                    }
                }
                if (decision.autoStop && autoStopped.compareAndSet(false, true)) {
                    onAutoStopped()
                    end()
                }
            }

            override fun stop() = inner.stop()
        }
    }
}

private const val PARAGRAPH = "\n\n"

/** Recognized or translated pieces as text: spaces between them, a blank line at a pause. */
internal fun joinPieces(pieces: List<String>): String = buildString {
    var newParagraph = false
    for (piece in pieces) {
        when {
            piece == PARAGRAPH -> newParagraph = isNotEmpty()
            piece.isBlank() -> Unit
            else -> {
                if (isNotEmpty()) append(if (newParagraph) PARAGRAPH else " ")
                append(piece.trim())
                newParagraph = false
            }
        }
    }
}
