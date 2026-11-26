package com.petroff.synchra.local

import com.petroff.synchra.soniox.LiveRequest
import com.petroff.synchra.soniox.LiveTranscript
import com.petroff.synchra.soniox.SilenceGate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalSpeechTranslatorTest {

    private val translator = FakeTranslator()
    private var recognizer: FakeSpeechRecognizer? = null
    private val audio = FakeAudioSource()
    private val finished = mutableListOf<LiveTranscript>()
    private val errors = mutableListOf<String>()
    private val updates = mutableListOf<LiveTranscript>()
    private var autoStopped = 0
    private val preparing = mutableListOf<Boolean>()
    // Runs while the recognizer is being created: lets a test press Stop at that moment.
    private var whileCreatingRecognizer: () -> Unit = {}

    private fun TestScope.engine(
        gate: () -> SilenceGate = { SilenceGate() },
        embedder: () -> SpeakerEmbedder? = { null },
    ) = LocalSpeechTranslator(
        audioSourceFactory = { audio },
        recognizerFactory = { language, source ->
            whileCreatingRecognizer()
            FakeSpeechRecognizer(source).also { it.language = language; recognizer = it }
        },
        translatorFactory = { translator },
        scope = this,
        onPreparing = { preparing += it },
        silenceGateFactory = gate,
        speakerEmbedderFactory = embedder,
    )

    private fun LocalSpeechTranslator.begin(target: String? = "ru") = start(
        LiveRequest(sourceLang = "en", targetLang = target, context = ""),
        onUpdate = { updates += it },
        onError = { errors += it },
        onFinished = { finished += it },
        onAutoStopped = { autoStopped++ },
    )

    private val tenWords = "one two three four five six seven eight nine ten"

    @Test
    fun `source and translation come out in spoken order`() = runTest {
        // The first chunk takes longer than the second: order must still hold.
        translator.delays = { n -> if (n == 1) 1_000 else 10 }
        val engine = engine()
        engine.begin()
        advanceUntilIdle()
        val asr = recognizer!!
        assertEquals("en", asr.language)

        asr.emit(tenWords) // 8 stable words -> first chunk
        asr.emit("$tenWords eleven", isFinal = true) // rest flushed
        asr.emit("twelve thirteen", isFinal = true)
        advanceUntilIdle()
        engine.stop()
        advanceUntilIdle()

        assertEquals(1, finished.size)
        assertEquals("$tenWords eleven twelve thirteen", finished[0].source)
        assertEquals("$tenWords eleven twelve thirteen".uppercase(), finished[0].translation)
        assertEquals(listOf(true, false), preparing)
        assertTrue(translator.closed)
    }

    @Test
    fun `the partial phrase shows and is kept at stop`() = runTest {
        val engine = engine()
        engine.begin()
        advanceUntilIdle()
        recognizer!!.emit("hello there", isFinal = true)
        recognizer!!.emit("how are")
        advanceUntilIdle()
        assertEquals("hello there how are", updates.last().source)

        engine.stop()
        advanceUntilIdle()
        assertEquals("hello there how are", finished.single().source)
        assertEquals("HELLO THERE HOW ARE", finished.single().translation)
    }

    @Test
    fun `transcription only never prepares or translates`() = runTest {
        val engine = engine()
        engine.begin(target = null)
        advanceUntilIdle()
        recognizer!!.emit(tenWords, isFinal = true)
        engine.stop()
        advanceUntilIdle()

        assertEquals(0, translator.prepareCalls)
        assertEquals(tenWords, finished.single().source)
        assertEquals("", finished.single().translation)
        assertTrue(preparing.isEmpty())
    }

    @Test
    fun `stop while preparing never starts the recognizer`() = runTest {
        val gate = CompletableDeferred<Result<Unit>>()
        translator.prepareGate = gate
        val engine = engine()
        engine.begin()
        advanceUntilIdle()

        engine.stop()
        gate.complete(Result.success(Unit))
        advanceUntilIdle()

        assertEquals(null, recognizer)
        assertEquals(1, finished.size)
        assertEquals(listOf(true, false), preparing)
    }

    @Test
    fun `a failed model download is reported and the session ends`() = runTest {
        translator.prepareGate = CompletableDeferred(Result.failure(RuntimeException("no network")))
        val engine = engine()
        engine.begin()
        advanceUntilIdle()

        assertEquals(1, errors.size)
        assertTrue(errors[0], errors[0].startsWith("Couldn't download the translation model"))
        assertEquals(1, finished.size)
        assertEquals(null, recognizer)
    }

    @Test
    fun `onFinished comes once even after two stops`() = runTest {
        val engine = engine()
        engine.begin()
        advanceUntilIdle()
        engine.stop()
        engine.stop()
        advanceUntilIdle()
        assertEquals(1, finished.size)
    }

    @Test
    fun `a recognizer error is reported and the session ends`() = runTest {
        val engine = engine()
        engine.begin()
        advanceUntilIdle()
        recognizer!!.fail(IllegalStateException("model broken"))
        advanceUntilIdle()

        assertEquals(1, errors.size)
        assertTrue(recognizer!!.stopped)
        assertEquals(1, finished.size)
    }

    @Test
    fun `a long silence stops the session by itself`() = runTest {
        val engine = engine(gate = { SilenceGate(autoStopMs = 1_000) })
        engine.begin()
        advanceUntilIdle()
        recognizer!!.audio.start { }
        repeat(30) { audio.push(FloatArray(1_600)) } // 3 s of silence in 100 ms chunks
        advanceUntilIdle()

        assertEquals(1, autoStopped)
        assertEquals(1, finished.size)
        assertFalse(errors.any())
    }

    @Test
    fun `stop while the recognizer is being created never starts it`() = runTest {
        lateinit var engine: LocalSpeechTranslator
        whileCreatingRecognizer = { engine.stop() }
        engine = engine()
        engine.begin()
        advanceUntilIdle()

        assertFalse(recognizer!!.started)
        assertEquals(1, finished.size)
    }

    // Voice prints by loudness: 0.1 is one speaker, 0.2 another.
    private val voices = object : SpeakerEmbedder {
        var released = false
        override fun embed(samples: FloatArray) =
            if (samples[0] < 0.15f) floatArrayOf(1f, 0.1f) else floatArrayOf(0.1f, 1f)
        override fun release() {
            released = true
        }
    }

    private fun speak(voice: Float, ms: Int) = repeat(ms / 100) { audio.push(FloatArray(1_600) { voice }) }
    private fun pause(ms: Int) = speak(0f, ms)

    @Test
    fun `two voices get speaker labels in source and translation`() = runTest {
        val engine = engine(embedder = { voices })
        engine.begin()
        advanceUntilIdle()
        val asr = recognizer!!
        asr.audio.start { }

        speak(0.1f, 1_000)
        asr.emit("hello there", isFinal = true)
        pause(700)
        speak(0.2f, 1_000)
        asr.emit("hi", isFinal = true)
        pause(700)
        advanceUntilIdle()
        assertEquals("Speaker 1: hello there\n\nSpeaker 2: hi", updates.last().source)

        engine.stop()
        advanceUntilIdle()
        assertEquals("Speaker 1: hello there\n\nSpeaker 2: hi", finished.single().source)
        assertEquals("Speaker 1: HELLO THERE\n\nSpeaker 2: HI", finished.single().translation)
        assertTrue(voices.released)
    }

    @Test
    fun `a new voice mid-phrase is labeled where the recognizer closes the phrase`() = runTest {
        val engine = engine(embedder = { voices })
        engine.begin()
        advanceUntilIdle()
        val asr = recognizer!!
        asr.audio.start { }

        speak(0.1f, 1_000)
        asr.emit("hello")
        pause(700)
        speak(0.2f, 1_000) // the recognizer still holds "hello …" open
        asr.emit("hello there", isFinal = true)
        asr.emit("hi")
        engine.stop()
        advanceUntilIdle()

        assertEquals("Speaker 1: hello there\n\nSpeaker 2: hi", finished.single().source)
        assertEquals("Speaker 1: HELLO THERE\n\nSpeaker 2: HI", finished.single().translation)
    }

    @Test
    fun `one voice, or no speaker model, leaves the text unlabeled`() = runTest {
        for (embedder in listOf<() -> SpeakerEmbedder?>({ voices }, { null })) {
            finished.clear()
            val engine = engine(embedder = embedder)
            engine.begin(target = null)
            advanceUntilIdle()
            val asr = recognizer!!
            asr.audio.start { }
            speak(0.1f, 1_000)
            asr.emit("hello there", isFinal = true)
            pause(700)
            speak(0.1f, 1_000)
            asr.emit("again", isFinal = true)
            engine.stop()
            advanceUntilIdle()
            assertEquals("hello there again", finished.single().source)
        }
    }
}
