package com.petroff.synchra.asr

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxAsrEngineTest {

    private class FakeDecoder : StreamingDecoder {
        var released = 0
        var text = ""
        var endpoint = false
        override fun accept(samples: FloatArray) = Unit
        override fun result(): Pair<String, Boolean> = text to endpoint
        override fun reset() = Unit
        override fun release() { released++ }
    }

    /** Audio the test drives by hand; start() only remembers the callback, like the real mic's thread. */
    private class ManualAudio : AudioSource {
        var onSamples: ((FloatArray) -> Unit)? = null
        var started = 0
        var stopped = 0
        override fun start(onSamples: (FloatArray) -> Unit) { started++; this.onSamples = onSamples }
        override fun stop() { stopped++ }
    }

    @Test
    fun `stop releases the native decoder`() {
        val decoder = FakeDecoder()
        val audio = ManualAudio()
        val ready = CountDownLatch(1)
        val engine = SherpaOnnxAsrEngine(audio, { decoder.also { ready.countDown() } })
        engine.start(onSegment = {}, onError = {})
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        waitUntil { audio.started == 1 }

        engine.stop()

        assertEquals(1, decoder.released)
        assertEquals(1, audio.stopped)
    }

    @Test
    fun `audio after stop is ignored`() {
        val decoder = FakeDecoder().apply { text = "late" }
        val audio = ManualAudio()
        val segments = mutableListOf<AsrSegment>()
        val engine = SherpaOnnxAsrEngine(audio, { decoder })
        engine.start(onSegment = { segments += it }, onError = {})
        waitUntil { audio.started == 1 }
        engine.stop()

        audio.onSamples!!(FloatArray(160))

        assertTrue(segments.isEmpty())
    }

    @Test
    fun `stop while the model loads never opens the microphone and frees the decoder`() {
        val decoder = FakeDecoder()
        val audio = ManualAudio()
        val loading = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val engine = SherpaOnnxAsrEngine(audio, { loading.countDown(); proceed.await(); decoder })
        engine.start(onSegment = {}, onError = {})
        assertTrue(loading.await(5, TimeUnit.SECONDS))

        engine.stop()
        proceed.countDown()
        waitUntil { decoder.released == 1 }

        assertEquals(0, audio.started)
    }

    @Test
    fun `a failing model load is reported`() {
        val errors = mutableListOf<Throwable>()
        val done = CountDownLatch(1)
        val engine = SherpaOnnxAsrEngine(ManualAudio(), { throw IllegalStateException("bad model") })
        engine.start(onSegment = {}, onError = { errors += it; done.countDown() })
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(errors.isEmpty())
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out" }
            Thread.sleep(5)
        }
    }
}
